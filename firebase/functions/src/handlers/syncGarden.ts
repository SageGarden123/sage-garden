import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { emptyGardenDoc, mergeGarden, GardenDoc, GardenPayload, SyncRecord, Tombstone } from "../gardenSync";
import { DeviceGardensDoc, emptyDeviceGardensDoc, generateMemberToken, MemberDoc, MemberPermission, MemberRole } from "../gardenMembers";

// Generous ceiling for a personal garden — guards against a malformed/oversized payload, not a
// real multi-tenant quota. A single Firestore document tops out at 1MB, which comfortably fits
// several thousand plant/care-log records at this shape.
const MAX_ITEMS = 2000;

function isValidRecord(x: unknown): x is SyncRecord {
  return !!x && typeof x === "object" && typeof (x as SyncRecord).id === "string" && typeof (x as SyncRecord).updatedAt === "number";
}

function isValidTombstone(x: unknown): x is Tombstone {
  return !!x && typeof x === "object" && typeof (x as Tombstone).id === "string" && typeof (x as Tombstone).deletedAt === "number";
}

class SyncAuthError extends Error {}
class SyncNotFoundError extends Error {}

/**
 * Syncs a device's local plant/care-log data against a shared Firestore document (now keyed by
 * an explicit gardenId, defaulting to the caller's own deviceId for backward compatibility with
 * pre-sharing clients) — see the desktop app's GardenStore/GardenSyncClient and the Android app's
 * GardenSyncClient for the two client halves. Both send their full current local state every call
 * and simply overwrite local state with whatever this returns — all merge logic lives here (see
 * gardenSync.ts), not duplicated on each client.
 *
 * Authorization: gardens/{gardenId}/members/{deviceId} must exist, be "approved", and the caller
 * must present the matching server-issued memberToken (see gardenMembers.ts) — this is the only
 * access control on this endpoint, there being no Firebase Auth in this app. A "read"-permission
 * member's own pushed plants/care-log/tombstones are discarded before the merge runs; they still
 * get back the current merged state so their device can display it.
 *
 * Legacy bridge: if no member doc exists yet for this (gardenId, deviceId) pair, the caller is
 * auto-provisioned — as owner if gardenId === deviceId (a device syncing against its own default
 * garden for the first time, including every pre-sharing-feature install), otherwise as a
 * grandfathered "write" member (a second device, e.g. desktop, that already knew this exact
 * foreign gardenId from before this feature existed). This is a one-time trust-the-UUID exception
 * for the transition; every subsequent call for that pair goes through the normal token check.
 * A gardenId that doesn't already exist and isn't the caller's own id is rejected outright — you
 * can't grandfather your way into a garden you never had prior knowledge of.
 *
 * Self-healing for a lost token: if a member doc already exists for gardenId === deviceId (the
 * device's own default garden) but the presented token is missing/wrong — most likely because the
 * device's local cache was wiped (e.g. an app reinstall) while its Firestore membership survived —
 * a fresh token is reissued rather than permanently locking the device out of its own garden. This
 * does NOT apply to a foreign gardenId (someone else's shared garden): a lost token there still
 * correctly requires re-requesting access from the owner, since that IS a real security boundary.
 *
 * Deliberately does NOT call verifyAppCheck: the desktop client is a plain JVM app with no Play
 * Integrity attestation available, so requiring it here would permanently lock desktop out the
 * moment APP_CHECK_ENFORCED is ever flipped to true elsewhere.
 */
export const syncGarden = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }

  const deviceId = typeof req.body?.deviceId === "string" ? req.body.deviceId.trim() : "";
  if (!deviceId) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }
  const gardenId = typeof req.body?.gardenId === "string" && req.body.gardenId.trim() ? req.body.gardenId.trim() : deviceId;
  const memberToken = typeof req.body?.memberToken === "string" ? req.body.memberToken.trim() : "";

  const rawPlants = Array.isArray(req.body?.plants) ? req.body.plants : [];
  const rawPlantTombstones = Array.isArray(req.body?.plantTombstones) ? req.body.plantTombstones : [];
  const rawCareLog = Array.isArray(req.body?.careLog) ? req.body.careLog : [];
  const rawCareLogTombstones = Array.isArray(req.body?.careLogTombstones) ? req.body.careLogTombstones : [];

  // Unlike the custom map image / irrigation setup (deliberately kept device-local — see
  // isOwnerOfActiveGarden in the Android client), the garden's address/coordinates are basic
  // shared context every member should see, not something private to the owner's device. A member
  // with no garden address of their own for this gardenId had no way to see the owner's, which
  // made a shared garden's map look empty even when the owner had set an address — the actual plant
  // markers were just off-screen at the map's hardcoded fallback location. Piggybacking this onto
  // the existing sync payload avoids a whole separate endpoint.
  const incomingGardenAddress = typeof req.body?.gardenAddress === "string" ? req.body.gardenAddress.trim().slice(0, 200) : null;
  const incomingGardenLat = typeof req.body?.gardenLat === "number" && Number.isFinite(req.body.gardenLat) ? req.body.gardenLat : null;
  const incomingGardenLng = typeof req.body?.gardenLng === "number" && Number.isFinite(req.body.gardenLng) ? req.body.gardenLng : null;
  // Same rationale as gardenAddress above — the named zones ("Front garden", "Back garden") are
  // shared context describing the physical property, not a personal preference. null (vs an empty
  // array) distinguishes "this device has nothing authoritative to offer" (never explicitly visited
  // Garden zones locally) from "the owner explicitly cleared their zone list to empty".
  const incomingGardenLocations = Array.isArray(req.body?.gardenLocations)
    ? (req.body.gardenLocations as unknown[])
        .filter((x): x is string => typeof x === "string")
        .map((s) => s.trim().slice(0, 80))
        .filter((s) => s.length > 0)
        .slice(0, 200)
    : null;

  if (rawPlants.length > MAX_ITEMS || rawCareLog.length > MAX_ITEMS) {
    res.status(400).json({ error: "too_many_items" });
    return;
  }

  const incoming: GardenPayload = {
    plants: rawPlants.filter(isValidRecord),
    plantTombstones: rawPlantTombstones.filter(isValidTombstone),
    careLog: rawCareLog.filter(isValidRecord),
    careLogTombstones: rawCareLogTombstones.filter(isValidTombstone),
  };
  const emptyPayload: GardenPayload = { plants: [], plantTombstones: [], careLog: [], careLogTombstones: [] };

  const db = getFirestore();
  const gardenRef = db.collection("gardens").doc(gardenId);
  const memberRef = gardenRef.collection("members").doc(deviceId);
  const deviceGardensRef = db.collection("deviceGardens").doc(deviceId);

  try {
    const outcome = await db.runTransaction(async (tx) => {
      const [gardenSnap, memberSnap, deviceGardensSnap] = await Promise.all([tx.get(gardenRef), tx.get(memberRef), tx.get(deviceGardensRef)]);
      const existingMeta = gardenSnap.exists ? (gardenSnap.data() as Record<string, unknown>) : undefined;

      let permission: MemberPermission;
      let role: MemberRole = "member";
      let responseToken = memberToken;
      let needsMetaStamp = false;

      const tokenMismatch = memberSnap.exists && (() => {
        const member = memberSnap.data() as MemberDoc;
        return member.status !== "approved" || !memberToken || member.memberToken !== memberToken;
      })();

      if (memberSnap.exists && !tokenMismatch) {
        const member = memberSnap.data() as MemberDoc;
        permission = member.permission;
        role = member.role;
      } else if (memberSnap.exists && tokenMismatch && gardenId === deviceId) {
        // Self-healing, own-default-garden only: deviceId itself already is this app's sole
        // identity boundary everywhere else (entitlement, PlantNet limits, etc. all trust a bare
        // deviceId with no further proof), so a mismatched/missing token here isn't a real security
        // boundary crossing the way it would be for someone else's shared garden below — it almost
        // always just means this device lost its local cache (e.g. a reinstall) while its Firestore
        // membership persisted. Reissue rather than lock the rightful device out permanently.
        const member = memberSnap.data() as MemberDoc;
        permission = member.permission;
        role = member.role;
        responseToken = generateMemberToken();
        tx.set(memberRef, { ...member, status: "approved", memberToken: responseToken }, { merge: true });
        const deviceDoc: DeviceGardensDoc = deviceGardensSnap.exists ? (deviceGardensSnap.data() as DeviceGardensDoc) : emptyDeviceGardensDoc();
        const name = deviceDoc.gardens[gardenId]?.name ?? (existingMeta?.name as string | undefined) ?? "My Garden";
        deviceDoc.gardens[gardenId] = { gardenId, name, role: member.role, permission, memberToken: responseToken };
        tx.set(deviceGardensRef, deviceDoc);
      } else if (memberSnap.exists && tokenMismatch) {
        throw new SyncAuthError();
      } else {
        if (gardenId !== deviceId && !gardenSnap.exists) throw new SyncNotFoundError();
        role = gardenId === deviceId ? "owner" : "member";
        permission = "write";
        responseToken = generateMemberToken();
        needsMetaStamp = !existingMeta?.ownerDeviceId;
        tx.set(memberRef, { status: "approved", role, permission, memberToken: responseToken, joinedAt: Date.now() });

        // Mirrors this auto-provisioned membership into the caller's deviceGardens index too, so
        // listMyGardens finds it independent of the calling device's own local cache (e.g. after a
        // reinstall, or a device that calls listMyGardens before ever syncing).
        const deviceDoc: DeviceGardensDoc = deviceGardensSnap.exists ? (deviceGardensSnap.data() as DeviceGardensDoc) : emptyDeviceGardensDoc();
        const name = (existingMeta?.name as string | undefined) ?? "My Garden";
        deviceDoc.gardens[gardenId] = { gardenId, name, role, permission, memberToken: responseToken };
        tx.set(deviceGardensRef, deviceDoc);
      }

      const stored: GardenDoc = gardenSnap.exists ? (gardenSnap.data() as GardenDoc) : emptyGardenDoc();
      const result = mergeGarden(stored, permission === "read" ? emptyPayload : incoming);

      const toWrite: Record<string, unknown> = { ...result };
      if (needsMetaStamp) {
        toWrite.ownerDeviceId = gardenId;
        toWrite.createdAt = existingMeta?.createdAt ?? Date.now();
        toWrite.name = existingMeta?.name ?? "My Garden";
      }
      // Owner-only — NOT merely "not read-only". Address/coordinates/zones describe the physical
      // garden itself, unlike plants/care-log which any write-permission member may legitimately
      // edit. Gating this on permission !== "read" was a real bug: an editor (any member granted
      // write access, not just the owner) syncing with their OWN device's locally-cached address
      // from an unrelated garden would get it silently accepted here and overwrite the real shared
      // value for every member, including the owner — reported as "I see my own address/zones when
      // I'm in another user's garden, but only when I have edit access" (view-only correctly could
      // never trigger this, since permission === "read" already excluded them).
      let responseGardenAddress = (existingMeta?.gardenAddress as string | undefined) ?? "";
      let responseGardenLat = (existingMeta?.gardenLat as number | undefined) ?? null;
      let responseGardenLng = (existingMeta?.gardenLng as number | undefined) ?? null;
      if (role === "owner" && incomingGardenAddress) {
        toWrite.gardenAddress = incomingGardenAddress;
        responseGardenAddress = incomingGardenAddress;
      }
      if (role === "owner" && incomingGardenLat !== null && incomingGardenLng !== null) {
        toWrite.gardenLat = incomingGardenLat;
        toWrite.gardenLng = incomingGardenLng;
        responseGardenLat = incomingGardenLat;
        responseGardenLng = incomingGardenLng;
      }
      // null (not []) means "never explicitly set by anyone yet" — kept distinct from the incoming
      // array below so the client knows to keep seeding its own zones locally from its plants rather
      // than locking in a premature empty list (see getOrSeedGardenLocations on the Android side).
      let responseGardenLocations: string[] | null = (existingMeta?.gardenLocations as string[] | undefined) ?? null;
      if (role === "owner" && incomingGardenLocations !== null) {
        toWrite.gardenLocations = incomingGardenLocations;
        responseGardenLocations = incomingGardenLocations;
      }
      tx.set(gardenRef, toWrite, { merge: true });

      return { result, permission, responseToken, responseGardenAddress, responseGardenLat, responseGardenLng, responseGardenLocations };
    });

    res.status(200).json({
      gardenId,
      memberToken: outcome.responseToken,
      permission: outcome.permission,
      plants: Object.values(outcome.result.plants),
      plantTombstones: Object.entries(outcome.result.plantTombstones).map(([id, deletedAt]) => ({ id, deletedAt })),
      careLog: Object.values(outcome.result.careLog),
      careLogTombstones: Object.entries(outcome.result.careLogTombstones).map(([id, deletedAt]) => ({ id, deletedAt })),
      gardenAddress: outcome.responseGardenAddress,
      gardenLat: outcome.responseGardenLat,
      gardenLng: outcome.responseGardenLng,
      gardenLocations: outcome.responseGardenLocations,
    });
  } catch (err) {
    if (err instanceof SyncAuthError) {
      res.status(403).json({ error: "not_authorized" });
      return;
    }
    if (err instanceof SyncNotFoundError) {
      res.status(404).json({ error: "garden_not_found" });
      return;
    }
    console.error("syncGarden failed", err);
    res.status(500).json({ error: "internal_error" });
  }
});
