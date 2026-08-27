import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { DeviceGardensDoc, emptyDeviceGardensDoc } from "../gardenMembers";

/** Single-doc read backing the garden picker + pending-requests UI — no per-garden fan-out queries needed since createGarden/respondToJoinRequest/requestJoinGarden all keep deviceGardens/{deviceId} in sync as they go. */
export const listMyGardens = onRequest({ cors: false }, async (req, res) => {
  const deviceId = typeof req.query?.deviceId === "string" ? req.query.deviceId.trim() : "";
  if (!deviceId) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  const db = getFirestore();
  const snap = await db.collection("deviceGardens").doc(deviceId).get();
  const doc: DeviceGardensDoc = snap.exists ? (snap.data() as DeviceGardensDoc) : emptyDeviceGardensDoc();

  res.status(200).json({
    gardens: Object.values(doc.gardens),
    pendingRequests: Object.values(doc.pendingRequests),
  });
});
