import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { emptyGardenDoc } from "../gardenSync";
import { generateInviteCode, generateMemberToken, emptyDeviceGardensDoc, DeviceGardensDoc } from "../gardenMembers";

/**
 * Creates a brand-new, empty garden owned by the calling device, with that device as its sole
 * "write" member. Distinct from a device's default/legacy garden (gardenId === installId,
 * auto-provisioned the first time syncGarden sees it) — this is for a device that already has a
 * garden and wants an additional one, or wants a fresh garden with a proper name to share out.
 */
export const createGarden = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }

  const deviceId = typeof req.body?.deviceId === "string" ? req.body.deviceId.trim() : "";
  const name = typeof req.body?.name === "string" ? req.body.name.trim().slice(0, 80) : "";
  if (!deviceId || !name) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  const db = getFirestore();
  const gardenId = db.collection("gardens").doc().id;
  const memberToken = generateMemberToken();
  const now = Date.now();

  try {
    let inviteCode = "";
    await db.runTransaction(async (tx) => {
      // Regenerate on the rare collision rather than failing the whole create.
      for (let attempt = 0; attempt < 5; attempt++) {
        const candidate = generateInviteCode();
        const codeSnap = await tx.get(db.collection("inviteCodes").doc(candidate));
        if (!codeSnap.exists) {
          inviteCode = candidate;
          break;
        }
      }
      if (!inviteCode) throw new Error("could_not_allocate_invite_code");

      const gardenRef = db.collection("gardens").doc(gardenId);
      tx.set(gardenRef, {
        ...emptyGardenDoc(),
        name,
        ownerDeviceId: deviceId,
        createdAt: now,
        inviteCode,
      });
      tx.set(gardenRef.collection("members").doc(deviceId), {
        status: "approved",
        role: "owner",
        permission: "write",
        memberToken,
        joinedAt: now,
      });
      tx.set(db.collection("inviteCodes").doc(inviteCode), { gardenId });

      const deviceGardensRef = db.collection("deviceGardens").doc(deviceId);
      const deviceSnap = await tx.get(deviceGardensRef);
      const deviceDoc: DeviceGardensDoc = deviceSnap.exists ? (deviceSnap.data() as DeviceGardensDoc) : emptyDeviceGardensDoc();
      deviceDoc.gardens[gardenId] = { gardenId, name, role: "owner", permission: "write", memberToken };
      tx.set(deviceGardensRef, deviceDoc);
    });

    res.status(200).json({ gardenId, memberToken, inviteCode, name });
  } catch (err) {
    console.error("createGarden failed", err);
    res.status(500).json({ error: "internal_error" });
  }
});
