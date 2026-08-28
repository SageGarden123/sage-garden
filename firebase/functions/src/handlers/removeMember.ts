import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { DeviceGardensDoc, verifyOwner } from "../gardenMembers";

/** Owner-only: revokes another member's access to this garden. The removed device keeps its own local cache until it next calls listMyGardens/syncGarden, at which point the missing membership/token surfaces as "no longer has access". */
export const removeMember = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }

  const ownerDeviceId = typeof req.body?.ownerDeviceId === "string" ? req.body.ownerDeviceId.trim() : "";
  const ownerMemberToken = typeof req.body?.ownerMemberToken === "string" ? req.body.ownerMemberToken.trim() : "";
  const gardenId = typeof req.body?.gardenId === "string" ? req.body.gardenId.trim() : "";
  const targetDeviceId = typeof req.body?.targetDeviceId === "string" ? req.body.targetDeviceId.trim() : "";

  if (!ownerDeviceId || !ownerMemberToken || !gardenId || !targetDeviceId) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }
  if (targetDeviceId === ownerDeviceId) {
    res.status(400).json({ error: "cannot_remove_owner" });
    return;
  }

  const db = getFirestore();
  if (!(await verifyOwner(db, gardenId, ownerDeviceId, ownerMemberToken))) {
    res.status(403).json({ error: "not_authorized" });
    return;
  }

  const memberRef = db.collection("gardens").doc(gardenId).collection("members").doc(targetDeviceId);
  const deviceGardensRef = db.collection("deviceGardens").doc(targetDeviceId);

  try {
    await db.runTransaction(async (tx) => {
      // Read before write — see requestJoinGarden.ts for why this ordering matters in a transaction.
      const deviceSnap = await tx.get(deviceGardensRef);
      tx.delete(memberRef);
      if (deviceSnap.exists) {
        const deviceDoc = deviceSnap.data() as DeviceGardensDoc;
        if (deviceDoc.gardens[gardenId]) {
          delete deviceDoc.gardens[gardenId];
          tx.set(deviceGardensRef, deviceDoc);
        }
      }
    });
    res.status(200).json({ success: true });
  } catch (err) {
    console.error("removeMember failed", err);
    res.status(500).json({ error: "internal_error" });
  }
});
