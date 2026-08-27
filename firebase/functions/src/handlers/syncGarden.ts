import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { emptyGardenDoc, mergeGarden, GardenDoc, GardenPayload, SyncRecord, Tombstone } from "../gardenSync";
import { generateMemberToken, MemberDoc, MemberPermission, MemberRole } from "../gardenMembers";

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

  try {
    const outcome = await db.runTransaction(async (tx) => {
      const [gardenSnap, memberSnap] = await Promise.all([tx.get(gardenRef), tx.get(memberRef)]);
      const existingMeta = gardenSnap.exists ? (gardenSnap.data() as Record<string, unknown>) : undefined;

      let permission: MemberPermission;
      let responseToken = memberToken;
      let needsMetaStamp = false;

      if (memberSnap.exists) {
        const member = memberSnap.data() as MemberDoc;
        if (member.status !== "approved" || !memberToken || member.memberToken !== memberToken) {
          throw new SyncAuthError();
        }
        permission = member.permission;
      } else {
        if (gardenId !== deviceId && !gardenSnap.exists) throw new SyncNotFoundError();
        const role: MemberRole = gardenId === deviceId ? "owner" : "member";
        permission = "write";
        responseToken = generateMemberToken();
        needsMetaStamp = !existingMeta?.ownerDeviceId;
        tx.set(memberRef, { status: "approved", role, permission, memberToken: responseToken, joinedAt: Date.now() });
      }

      const stored: GardenDoc = gardenSnap.exists ? (gardenSnap.data() as GardenDoc) : emptyGardenDoc();
      const result = mergeGarden(stored, permission === "read" ? emptyPayload : incoming);

      const toWrite: Record<string, unknown> = { ...result };
      if (needsMetaStamp) {
        toWrite.ownerDeviceId = gardenId;
        toWrite.createdAt = existingMeta?.createdAt ?? Date.now();
        toWrite.name = existingMeta?.name ?? "My Garden";
      }
      tx.set(gardenRef, toWrite, { merge: true });

      return { result, permission, responseToken };
    });

    res.status(200).json({
      gardenId,
      memberToken: outcome.responseToken,
      permission: outcome.permission,
      plants: Object.values(outcome.result.plants),
      plantTombstones: Object.entries(outcome.result.plantTombstones).map(([id, deletedAt]) => ({ id, deletedAt })),
      careLog: Object.values(outcome.result.careLog),
      careLogTombstones: Object.entries(outcome.result.careLogTombstones).map(([id, deletedAt]) => ({ id, deletedAt })),
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
