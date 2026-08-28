import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { verifyOwner } from "../gardenMembers";

/**
 * Owner-only: renames a garden. Only gardens/{gardenId}.name is updated — listMyGardens resolves
 * each garden's display name live from that doc rather than trusting a per-device cached copy, so
 * a rename is immediately visible to every member on their next refresh without a fan-out write.
 */
export const renameGarden = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }

  const ownerDeviceId = typeof req.body?.ownerDeviceId === "string" ? req.body.ownerDeviceId.trim() : "";
  const ownerMemberToken = typeof req.body?.ownerMemberToken === "string" ? req.body.ownerMemberToken.trim() : "";
  const gardenId = typeof req.body?.gardenId === "string" ? req.body.gardenId.trim() : "";
  const name = typeof req.body?.name === "string" ? req.body.name.trim().slice(0, 80) : "";

  if (!ownerDeviceId || !ownerMemberToken || !gardenId || !name) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  const db = getFirestore();
  if (!(await verifyOwner(db, gardenId, ownerDeviceId, ownerMemberToken))) {
    res.status(403).json({ error: "not_authorized" });
    return;
  }

  try {
    await db.collection("gardens").doc(gardenId).update({ name });
    res.status(200).json({ name });
  } catch (err) {
    console.error("renameGarden failed", err);
    res.status(500).json({ error: "internal_error" });
  }
});
