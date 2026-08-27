import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { DeviceGardensDoc, emptyDeviceGardensDoc, MemberDoc, MemberPermission } from "../gardenMembers";

/** Resolves a human-typed invite code to a pending join request against that garden — the owner still has to approve it in respondToJoinRequest before any data is shared. */
export const requestJoinGarden = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }

  const deviceId = typeof req.body?.deviceId === "string" ? req.body.deviceId.trim() : "";
  const inviteCode = typeof req.body?.inviteCode === "string" ? req.body.inviteCode.trim().toUpperCase() : "";
  const requestedPermission: MemberPermission = req.body?.requestedPermission === "read" ? "read" : "write";
  const displayName = typeof req.body?.displayName === "string" ? req.body.displayName.trim().slice(0, 60) : null;

  if (!deviceId || !inviteCode) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  const db = getFirestore();
  const codeSnap = await db.collection("inviteCodes").doc(inviteCode).get();
  if (!codeSnap.exists) {
    res.status(404).json({ error: "invalid_code" });
    return;
  }
  const gardenId = (codeSnap.data() as { gardenId: string }).gardenId;
  const gardenRef = db.collection("gardens").doc(gardenId);

  const gardenSnap = await gardenRef.get();
  if (!gardenSnap.exists) {
    res.status(404).json({ error: "garden_not_found" });
    return;
  }
  const gardenName = (gardenSnap.data() as { name?: string }).name ?? "Shared garden";

  // Already an approved member — treat as a no-op success rather than erroring, since the
  // requester has no way of knowing that ahead of time.
  const existingMember = await gardenRef.collection("members").doc(deviceId).get();
  if (existingMember.exists && (existingMember.data() as MemberDoc).status === "approved") {
    res.status(200).json({ gardenId, status: "approved" });
    return;
  }

  const now = Date.now();
  try {
    await db.runTransaction(async (tx) => {
      tx.set(gardenRef.collection("joinRequests").doc(deviceId), {
        status: "pending",
        requestedPermission,
        requestedAt: now,
        displayName,
      });

      const deviceGardensRef = db.collection("deviceGardens").doc(deviceId);
      const deviceSnap = await tx.get(deviceGardensRef);
      const deviceDoc: DeviceGardensDoc = deviceSnap.exists ? (deviceSnap.data() as DeviceGardensDoc) : emptyDeviceGardensDoc();
      deviceDoc.pendingRequests[gardenId] = { gardenId, name: gardenName, requestedPermission, requestedAt: now };
      tx.set(deviceGardensRef, deviceDoc);
    });

    res.status(200).json({ gardenId, status: "pending" });
  } catch (err) {
    console.error("requestJoinGarden failed", err);
    res.status(500).json({ error: "internal_error" });
  }
});
