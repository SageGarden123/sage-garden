import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { JoinRequestDoc, verifyOwner } from "../gardenMembers";

/** Owner-only: lists the join requests currently awaiting approval for a garden — respondToJoinRequest acts on one of these. */
export const listPendingRequests = onRequest({ cors: false }, async (req, res) => {
  const ownerDeviceId = typeof req.query?.ownerDeviceId === "string" ? req.query.ownerDeviceId.trim() : "";
  const ownerMemberToken = typeof req.query?.ownerMemberToken === "string" ? req.query.ownerMemberToken.trim() : "";
  const gardenId = typeof req.query?.gardenId === "string" ? req.query.gardenId.trim() : "";

  if (!ownerDeviceId || !ownerMemberToken || !gardenId) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  const db = getFirestore();
  if (!(await verifyOwner(db, gardenId, ownerDeviceId, ownerMemberToken))) {
    res.status(403).json({ error: "not_authorized" });
    return;
  }

  const snap = await db.collection("gardens").doc(gardenId).collection("joinRequests").where("status", "==", "pending").get();
  const requests = snap.docs.map((doc) => {
    const data = doc.data() as JoinRequestDoc;
    return {
      requestingDeviceId: doc.id,
      requestedPermission: data.requestedPermission,
      requestedAt: data.requestedAt,
      displayName: data.displayName ?? null,
    };
  });

  res.status(200).json({ requests });
});
