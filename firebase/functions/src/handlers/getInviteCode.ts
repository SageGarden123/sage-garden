import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { verifyOwner } from "../gardenMembers";

/** Owner-only: reads the garden's current invite code without changing it — regenerateInviteCode is the only thing that should ever invalidate the existing one. */
export const getInviteCode = onRequest({ cors: false }, async (req, res) => {
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

  const snap = await db.collection("gardens").doc(gardenId).get();
  const inviteCode = snap.exists ? ((snap.data() as { inviteCode?: string }).inviteCode ?? null) : null;
  res.status(200).json({ inviteCode });
});
