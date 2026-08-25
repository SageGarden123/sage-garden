import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { emptyGardenDoc, mergeGarden, GardenDoc, GardenPayload, SyncRecord, Tombstone } from "../gardenSync";

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

/**
 * Syncs a device's local plant/care-log data against a shared Firestore document keyed by
 * deviceId, so the same garden can be edited from multiple devices (phone + desktop) without a
 * real-time listener or account system — see the desktop app's GardenStore/GardenSyncClient and
 * the Android app's GardenSyncClient for the two client halves. Both send their full current
 * local state every call and simply overwrite local state with whatever this returns — all merge
 * logic lives here (see gardenSync.ts), not duplicated on each client.
 *
 * Deliberately does NOT call verifyAppCheck: the desktop client is a plain JVM app with no Play
 * Integrity attestation available, so requiring it here would permanently lock desktop out the
 * moment APP_CHECK_ENFORCED is ever flipped to true elsewhere. deviceId itself (a random UUID
 * minted client-side) is the only access control on this endpoint — same trust model already
 * used for Sage prompt tracking and AI photo-ID limits.
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

  const db = getFirestore();
  const ref = db.collection("gardens").doc(deviceId);

  try {
    const merged = await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      const stored: GardenDoc = snap.exists ? (snap.data() as GardenDoc) : emptyGardenDoc();
      const result = mergeGarden(stored, incoming);
      tx.set(ref, result);
      return result;
    });

    res.status(200).json({
      plants: Object.values(merged.plants),
      plantTombstones: Object.entries(merged.plantTombstones).map(([id, deletedAt]) => ({ id, deletedAt })),
      careLog: Object.values(merged.careLog),
      careLogTombstones: Object.entries(merged.careLogTombstones).map(([id, deletedAt]) => ({ id, deletedAt })),
    });
  } catch (err) {
    console.error("syncGarden failed", err);
    res.status(500).json({ error: "internal_error" });
  }
});
