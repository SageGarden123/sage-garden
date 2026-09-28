import type { Request } from "firebase-functions/v2/https";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, Firestore, Transaction, WriteBatch } from "firebase-admin/firestore";

/**
 * Realtime change signals — lets a phone find out that a garden changed without polling syncGarden
 * every minute. gardenSignals/{gardenId} is a tiny doc ({ rev, membershipRev, updatedAt }) that the
 * Android app listens to with a Firestore snapshot listener; when `rev` moves past the last rev that
 * device itself synced to, it calls syncGarden as before. All merge logic and all real data still go
 * exclusively through syncGarden — the signal doc carries no plant data at all, only "something
 * changed", so a listener never re-downloads a garden doc that can be ~900KB with thumbnails.
 *
 * Access: this is the ONE collection clients may read directly (see firestore.rules). A device gets
 * read access by presenting a Firebase (anonymous) Auth ID token to syncGarden, which — only after
 * its normal memberToken check passes — records gardenSignals/{gardenId}/readers/{uid}. The uid is
 * also remembered on the member doc (listenerUids) so removeMember/leaveGarden/deleteGarden can
 * revoke it; a revoked listener gets PERMISSION_DENIED and the app falls back to polling.
 */

export interface GardenSignalDoc {
  rev: number;
  membershipRev?: number;
  /** Bumped when the garden plan (map image, irrigation paths, sun zones) changes — see gardenPlan.ts. */
  planRev?: number;
  updatedAt: number;
}

export function signalRef(db: Firestore, gardenId: string) {
  return db.collection("gardenSignals").doc(gardenId);
}

export function readerRef(db: Firestore, gardenId: string, uid: string) {
  return signalRef(db, gardenId).collection("readers").doc(uid);
}

/** The caller's Firebase Auth uid from an `X-Firebase-Id-Token` header, or null if absent/invalid — optional, so older app versions and the desktop client (no Firebase Auth) keep working unchanged. */
export async function verifiedListenerUid(req: Request): Promise<string | null> {
  const token = req.get("X-Firebase-Id-Token")?.trim();
  if (!token) return null;
  try {
    return (await getAuth().verifyIdToken(token)).uid;
  } catch {
    return null;
  }
}

/** Revokes every listener uid a member doc has accumulated (a reinstall mints a new anonymous uid, so there can be several). */
export function revokeListeners(db: Firestore, writer: Transaction | WriteBatch, gardenId: string, listenerUids: unknown) {
  if (!Array.isArray(listenerUids)) return;
  for (const uid of listenerUids) {
    if (typeof uid === "string" && uid) writer.delete(readerRef(db, gardenId, uid));
  }
}

/** Tells listening devices to re-fetch their known-gardens list (name/permission changed) — distinct from `rev`, which means the garden's plant/care-log data changed. */
export async function bumpMembershipRev(db: Firestore, gardenId: string) {
  await signalRef(db, gardenId).set({ membershipRev: FieldValue.increment(1), updatedAt: Date.now() }, { merge: true });
}
