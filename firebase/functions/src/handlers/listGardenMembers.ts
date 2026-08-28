import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { MemberDoc, verifyOwner } from "../gardenMembers";

/** Owner-only: lists every approved member of a garden (including the owner) so the sharing UI can show who has access and let the owner toggle permission or remove someone. */
export const listGardenMembers = onRequest({ cors: false }, async (req, res) => {
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

  const snap = await db.collection("gardens").doc(gardenId).collection("members").get();
  const members = snap.docs.map((d) => {
    const m = d.data() as MemberDoc;
    return {
      deviceId: d.id,
      role: m.role,
      permission: m.permission,
      joinedAt: m.joinedAt,
      displayName: m.displayName ?? null,
    };
  });

  res.status(200).json({ members });
});
