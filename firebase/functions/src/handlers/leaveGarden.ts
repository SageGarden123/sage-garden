import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { DeviceGardensDoc, MemberDoc } from "../gardenMembers";

/** Self-service: a member removes their own access to someone else's shared garden. The owner can't leave their own garden this way (there's no ownership transfer) — they'd need to delete/stop sharing it instead. */
export const leaveGarden = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }

  const deviceId = typeof req.body?.deviceId === "string" ? req.body.deviceId.trim() : "";
  const memberToken = typeof req.body?.memberToken === "string" ? req.body.memberToken.trim() : "";
  const gardenId = typeof req.body?.gardenId === "string" ? req.body.gardenId.trim() : "";

  if (!deviceId || !memberToken || !gardenId) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  const db = getFirestore();
  const memberRef = db.collection("gardens").doc(gardenId).collection("members").doc(deviceId);
  const deviceGardensRef = db.collection("deviceGardens").doc(deviceId);

  try {
    await db.runTransaction(async (tx) => {
      // Read before write — see requestJoinGarden.ts for why this ordering matters in a transaction.
      const [memberSnap, deviceSnap] = await Promise.all([tx.get(memberRef), tx.get(deviceGardensRef)]);
      if (!memberSnap.exists) throw new Error("not_a_member");
      const member = memberSnap.data() as MemberDoc;
      if (member.memberToken !== memberToken) throw new Error("not_authorized");
      if (member.role === "owner") throw new Error("owner_cannot_leave");

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
    const message = err instanceof Error ? err.message : "internal_error";
    if (message === "not_a_member") {
      res.status(404).json({ error: "not_a_member" });
      return;
    }
    if (message === "not_authorized") {
      res.status(403).json({ error: "not_authorized" });
      return;
    }
    if (message === "owner_cannot_leave") {
      res.status(400).json({ error: "owner_cannot_leave" });
      return;
    }
    console.error("leaveGarden failed", err);
    res.status(500).json({ error: "internal_error" });
  }
});
