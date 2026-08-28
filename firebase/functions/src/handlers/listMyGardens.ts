import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { DeviceGardensDoc, emptyDeviceGardensDoc } from "../gardenMembers";

/** Backs the garden picker + pending-requests UI. Membership/token/role come from the cached deviceGardens/{deviceId} doc (kept in sync by createGarden/respondToJoinRequest/requestJoinGarden as they go), but each garden's *name* is re-read live from gardens/{gardenId} rather than trusted from that cache — otherwise a rename (see renameGarden) would only ever be visible to the owner's own device, since nothing fans a rename out to every other member's cached copy. */
export const listMyGardens = onRequest({ cors: false }, async (req, res) => {
  const deviceId = typeof req.query?.deviceId === "string" ? req.query.deviceId.trim() : "";
  if (!deviceId) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  const db = getFirestore();
  const snap = await db.collection("deviceGardens").doc(deviceId).get();
  const doc: DeviceGardensDoc = snap.exists ? (snap.data() as DeviceGardensDoc) : emptyDeviceGardensDoc();

  const gardens = await Promise.all(
    Object.values(doc.gardens).map(async (entry) => {
      const gardenSnap = await db.collection("gardens").doc(entry.gardenId).get();
      const liveName = gardenSnap.exists ? (gardenSnap.data() as { name?: string }).name : undefined;
      return { ...entry, name: liveName ?? entry.name };
    })
  );

  res.status(200).json({
    gardens,
    pendingRequests: Object.values(doc.pendingRequests),
  });
});
