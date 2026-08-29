import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { GardenMetaDoc, DeviceGardensDoc, verifyOwner } from "../gardenMembers";

/**
 * Owner-only: permanently deletes a garden — every member (including the owner) loses access, and
 * all its plant/care-log/garden-address/zones data is gone from the server. Distinct from "Reset
 * garden" (client-side, wipes just the local plants for the active garden but keeps the garden
 * itself and its sharing setup intact). A device's own original default garden (gardenId ===
 * ownerDeviceId) can't be deleted this way — there's no "no garden" state in this app, so that
 * garden always has to exist; only additional created/joined gardens are eligible.
 */
export const deleteGarden = onRequest({ cors: false }, async (req, res) => {
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
  if (gardenId === ownerDeviceId) {
    res.status(400).json({ error: "cannot_delete_default_garden" });
    return;
  }

  const db = getFirestore();
  if (!(await verifyOwner(db, gardenId, ownerDeviceId, ownerMemberToken))) {
    res.status(403).json({ error: "not_authorized" });
    return;
  }

  const gardenRef = db.collection("gardens").doc(gardenId);

  try {
    const [gardenSnap, membersSnap, joinRequestsSnap] = await Promise.all([
      gardenRef.get(),
      gardenRef.collection("members").get(),
      gardenRef.collection("joinRequests").get(),
    ]);

    const batch = db.batch();

    const inviteCode = (gardenSnap.data() as GardenMetaDoc | undefined)?.inviteCode;
    if (inviteCode) batch.delete(db.collection("inviteCodes").doc(inviteCode));

    // Every member (including the owner) needs this garden dropped from their own deviceGardens
    // index, or listMyGardens would keep showing a garden that no longer exists.
    for (const memberDoc of membersSnap.docs) {
      const deviceId = memberDoc.id;
      const deviceGardensRef = db.collection("deviceGardens").doc(deviceId);
      const deviceSnap = await deviceGardensRef.get();
      if (deviceSnap.exists) {
        const deviceDoc = deviceSnap.data() as DeviceGardensDoc;
        if (deviceDoc.gardens[gardenId]) {
          delete deviceDoc.gardens[gardenId];
          batch.set(deviceGardensRef, deviceDoc);
        }
      }
      batch.delete(memberDoc.ref);
    }
    for (const joinRequestDoc of joinRequestsSnap.docs) {
      batch.delete(joinRequestDoc.ref);
    }
    batch.delete(gardenRef);

    await batch.commit();
    res.status(200).json({ success: true, formerMemberCount: membersSnap.size });
  } catch (err) {
    console.error("deleteGarden failed", err);
    res.status(500).json({ error: "internal_error" });
  }
});
