import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import {
  DeviceGardensDoc,
  emptyDeviceGardensDoc,
  generateMemberToken,
  JoinRequestDoc,
  MemberPermission,
  verifyOwner,
} from "../gardenMembers";

/** Owner-only: approves or rejects a pending join request. On approval, the owner may accept the requester's requested permission as-is or override it (grant view-only instead of the edit access someone asked for, etc). */
export const respondToJoinRequest = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }

  const ownerDeviceId = typeof req.body?.ownerDeviceId === "string" ? req.body.ownerDeviceId.trim() : "";
  const ownerMemberToken = typeof req.body?.ownerMemberToken === "string" ? req.body.ownerMemberToken.trim() : "";
  const gardenId = typeof req.body?.gardenId === "string" ? req.body.gardenId.trim() : "";
  const requestingDeviceId = typeof req.body?.requestingDeviceId === "string" ? req.body.requestingDeviceId.trim() : "";
  const approve = req.body?.approve === true;
  const overridePermission: MemberPermission | null = req.body?.permission === "read" || req.body?.permission === "write" ? req.body.permission : null;

  if (!ownerDeviceId || !ownerMemberToken || !gardenId || !requestingDeviceId) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  const db = getFirestore();
  if (!(await verifyOwner(db, gardenId, ownerDeviceId, ownerMemberToken))) {
    res.status(403).json({ error: "not_authorized" });
    return;
  }

  const gardenRef = db.collection("gardens").doc(gardenId);
  const requestRef = gardenRef.collection("joinRequests").doc(requestingDeviceId);

  try {
    const result = await db.runTransaction(async (tx) => {
      const requestSnap = await tx.get(requestRef);
      if (!requestSnap.exists) throw new Error("request_not_found");
      const request = requestSnap.data() as JoinRequestDoc;
      if (request.status !== "pending") throw new Error("request_already_resolved");

      const gardenSnap = await tx.get(gardenRef);
      const gardenName = (gardenSnap.data() as { name?: string } | undefined)?.name ?? "Shared garden";

      const deviceGardensRef = db.collection("deviceGardens").doc(requestingDeviceId);
      const deviceSnap = await tx.get(deviceGardensRef);
      const deviceDoc: DeviceGardensDoc = deviceSnap.exists ? (deviceSnap.data() as DeviceGardensDoc) : emptyDeviceGardensDoc();
      delete deviceDoc.pendingRequests[gardenId];

      if (approve) {
        const permission = overridePermission ?? request.requestedPermission;
        const memberToken = generateMemberToken();
        tx.set(gardenRef.collection("members").doc(requestingDeviceId), {
          status: "approved",
          role: "member",
          permission,
          memberToken,
          joinedAt: Date.now(),
        });
        deviceDoc.gardens[gardenId] = { gardenId, name: gardenName, role: "member", permission, memberToken };
        tx.set(requestRef, { ...request, status: "approved" });
      } else {
        tx.set(requestRef, { ...request, status: "rejected" });
      }
      tx.set(deviceGardensRef, deviceDoc);
      return { approved: approve };
    });

    res.status(200).json(result);
  } catch (err) {
    const message = err instanceof Error ? err.message : "internal_error";
    if (message === "request_not_found" || message === "request_already_resolved") {
      res.status(400).json({ error: message });
      return;
    }
    console.error("respondToJoinRequest failed", err);
    res.status(500).json({ error: "internal_error" });
  }
});
