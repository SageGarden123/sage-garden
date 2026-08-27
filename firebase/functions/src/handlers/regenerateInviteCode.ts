import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { generateInviteCode, verifyOwner } from "../gardenMembers";

/** Owner-only: invalidates the garden's current invite code and issues a new one — e.g. after accidentally sharing it too widely. */
export const regenerateInviteCode = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }

  const ownerDeviceId = typeof req.body?.ownerDeviceId === "string" ? req.body.ownerDeviceId.trim() : "";
  const ownerMemberToken = typeof req.body?.ownerMemberToken === "string" ? req.body.ownerMemberToken.trim() : "";
  const gardenId = typeof req.body?.gardenId === "string" ? req.body.gardenId.trim() : "";

  if (!ownerDeviceId || !ownerMemberToken || !gardenId) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  const db = getFirestore();
  if (!(await verifyOwner(db, gardenId, ownerDeviceId, ownerMemberToken))) {
    res.status(403).json({ error: "not_authorized" });
    return;
  }

  const gardenRef = db.collection("gardens").doc(gardenId);

  try {
    const inviteCode = await db.runTransaction(async (tx) => {
      const gardenSnap = await tx.get(gardenRef);
      if (!gardenSnap.exists) throw new Error("garden_not_found");
      const oldCode = (gardenSnap.data() as { inviteCode?: string }).inviteCode;

      let newCode = "";
      for (let attempt = 0; attempt < 5; attempt++) {
        const candidate = generateInviteCode();
        const codeSnap = await tx.get(db.collection("inviteCodes").doc(candidate));
        if (!codeSnap.exists) {
          newCode = candidate;
          break;
        }
      }
      if (!newCode) throw new Error("could_not_allocate_invite_code");

      if (oldCode) tx.delete(db.collection("inviteCodes").doc(oldCode));
      tx.set(db.collection("inviteCodes").doc(newCode), { gardenId });
      tx.update(gardenRef, { inviteCode: newCode });
      return newCode;
    });

    res.status(200).json({ inviteCode });
  } catch (err) {
    console.error("regenerateInviteCode failed", err);
    res.status(500).json({ error: "internal_error" });
  }
});
