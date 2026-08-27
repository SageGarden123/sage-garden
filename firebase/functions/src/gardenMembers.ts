/**
 * Shared types + helpers for multi-user garden sharing, layered on top of the existing
 * per-device sync in gardenSync.ts/handlers/syncGarden.ts. There is no Firebase Auth in this
 * app (client talks to Cloud Functions over plain HTTPS) so "authorization" here means: only a
 * device holding the server-issued `memberToken` for a given gardenId's `members/{deviceId}` doc
 * may sync data as that member. Knowing a gardenId alone (e.g. from a shared invite code) is
 * NOT sufficient — it only lets a device request to join, which the garden's owner must approve.
 */

import { randomUUID } from "crypto";
import { Firestore } from "firebase-admin/firestore";

export type MemberRole = "owner" | "member";
export type MemberPermission = "read" | "write";
export type JoinRequestStatus = "pending" | "approved" | "rejected";

export interface MemberDoc {
  status: "approved";
  role: MemberRole;
  permission: MemberPermission;
  memberToken: string;
  joinedAt: number;
}

export interface JoinRequestDoc {
  status: JoinRequestStatus;
  requestedPermission: MemberPermission;
  requestedAt: number;
  displayName?: string | null;
}

export interface GardenMetaDoc {
  name: string;
  ownerDeviceId: string;
  createdAt: number;
  inviteCode: string;
}

/** A single device's view across every garden it belongs to / has a pending request against — keyed by gardenId (not an array) so individual entries can be added/removed atomically without exact-value array matching. */
export interface DeviceGardenEntry {
  gardenId: string;
  name: string;
  role: MemberRole;
  permission: MemberPermission;
  memberToken: string;
}

export interface DevicePendingRequestEntry {
  gardenId: string;
  name: string;
  requestedPermission: MemberPermission;
  requestedAt: number;
}

export interface DeviceGardensDoc {
  gardens: Record<string, DeviceGardenEntry>;
  pendingRequests: Record<string, DevicePendingRequestEntry>;
}

export function emptyDeviceGardensDoc(): DeviceGardensDoc {
  return { gardens: {}, pendingRequests: {} };
}

export function generateMemberToken(): string {
  return randomUUID();
}

const INVITE_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I — avoids look-alike confusion when read aloud/typed in

export function generateInviteCode(): string {
  let code = "";
  for (let i = 0; i < 8; i++) {
    code += INVITE_CODE_ALPHABET[Math.floor(Math.random() * INVITE_CODE_ALPHABET.length)];
  }
  return code;
}

/** Confirms the caller is the approved owner member of a garden, holding the token that was issued for that membership. Every owner-only action (approving/rejecting join requests, changing a member's permission, regenerating the invite code) gates on this. */
export async function verifyOwner(db: Firestore, gardenId: string, ownerDeviceId: string, ownerMemberToken: string): Promise<boolean> {
  const snap = await db.collection("gardens").doc(gardenId).collection("members").doc(ownerDeviceId).get();
  if (!snap.exists) return false;
  const member = snap.data() as MemberDoc;
  return member.status === "approved" && member.role === "owner" && member.memberToken === ownerMemberToken;
}
