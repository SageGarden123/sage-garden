import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { DeviceGardensDoc, MemberDoc, MemberPermission, verifyOwner } from "../gardenMembers";

/** Owner-only: changes an existing member's read/write access after the fact (e.g. upgrading a viewer to editor, or the reverse). */
export const updateMemberPermission = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }

  const ownerDeviceId = typeof req.body?.ownerDeviceId === "string" ? req.body.ownerDeviceId.trim() : "";
  const ownerMemberToken = typeof req.body?.ownerMemberToken === "string" ? req.body.ownerMemberToken.trim() : "";
  const gardenId = typeof req.body?.gardenId === "string" ? req.body.gardenId.trim() : "";
  const targetDeviceId = typeof req.body?.targetDeviceId === "string" ? req.body.targetDeviceId.trim() : "";
  const permission: MemberPermission | null = req.body?.permission === "read" || req.body?.permission === "write" ? req.body.permission : null;

  if (!ownerDeviceId || !ownerMemberToken || !gardenId || !targetDeviceId || !permission) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  const db = getFirestore();
  if (!(await verifyOwner(db, gardenId, ownerDeviceId, ownerMemberToken))) {
    res.status(403).json({ error: "not_authorized" });
    return;
  }
  if (targetDeviceId === ownerDeviceId) {
    res.status(400).json({ error: "cannot_change_owner" });
    return;
  }

  const memberRef = db.collection("gardens").doc(gardenId).collection("members").doc(targetDeviceId);

  try {
    const deviceGardensRef = db.collection("deviceGardens").doc(targetDeviceId);
    await db.runTransaction(async (tx) => {
      // Firestore transactions require every read before any write — see the identical fix in
      // requestJoinGarden.ts/createGarden.ts for why this ordering matters.
      const [memberSnap, deviceSnap] = await Promise.all([tx.get(memberRef), tx.get(deviceGardensRef)]);
      if (!memberSnap.exists) throw new Error("member_not_found");

      tx.update(memberRef, { permission });

      if (deviceSnap.exists) {
        const deviceDoc = deviceSnap.data() as DeviceGardensDoc;
        if (deviceDoc.gardens[gardenId]) {
          tx.update(deviceGardensRef, { [`gardens.${gardenId}.permission`]: permission });
        }
      }
    });

    res.status(200).json({ success: true });
  } catch (err) {
    if (err instanceof Error && err.message === "member_not_found") {
      res.status(404).json({ error: "member_not_found" });
      return;
    }
    console.error("updateMemberPermission failed", err);
    res.status(500).json({ error: "internal_error" });
  }
});
