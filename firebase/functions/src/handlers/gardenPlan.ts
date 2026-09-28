import { onRequest } from "firebase-functions/v2/https";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import type { Firestore } from "firebase-admin/firestore";
import { MemberDoc } from "../gardenMembers";
import { GardenSignalDoc, signalRef } from "../gardenSignals";

/**
 * The garden "plan": the owner's uploaded garden map image plus what's drawn on it (irrigation
 * paths, sun zones) and the garden's irrigation zone names. It used to live only on the owner's
 * phone, so members, the desktop app and the car display never saw it. Only the OWNER's device
 * writes it (like the garden address); every approved member can read it.
 *
 * Storage: gardens/{gardenId}/plan/current holds the drawing data and the image's hash;
 * gardens/{gardenId}/plan/image holds the image itself (a downscaled, already-rotated JPEG as
 * base64, capped well under Firestore's 1MB document limit). The image is only transferred when
 * its hash differs from what the caller already has. A plan change bumps planRev on the garden's
 * change signal (gardenSignals/{gardenId}) so listening devices know to fetch it.
 */

const MAX_ITEMS = 500;
const MAX_PLAN_BYTES = 900_000;
const MAX_IMAGE_CHARS = 950_000;

export interface GardenPlanDoc {
  paths: unknown[];
  sunZones: unknown[];
  irrigationZones: string[];
  imageHash: string | null;
  imageWidth: number | null;
  imageHeight: number | null;
  updatedAt: number;
}

export function planRef(db: Firestore, gardenId: string) {
  return db.collection("gardens").doc(gardenId).collection("plan").doc("current");
}
export function planImageRef(db: Firestore, gardenId: string) {
  return db.collection("gardens").doc(gardenId).collection("plan").doc("image");
}

/** The caller's approved membership, or null if the token doesn't match. */
export async function verifyMember(db: Firestore, gardenId: string, deviceId: string, memberToken: string): Promise<MemberDoc | null> {
  if (!gardenId || !deviceId || !memberToken) return null;
  const snap = await db.collection("gardens").doc(gardenId).collection("members").doc(deviceId).get();
  if (!snap.exists) return null;
  const member = snap.data() as MemberDoc;
  return member.status === "approved" && member.memberToken === memberToken ? member : null;
}

function str(x: unknown): string {
  return typeof x === "string" ? x.trim() : "";
}

export const syncGardenPlan = onRequest({ cors: false, memory: "512MiB" }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  const deviceId = str(req.body?.deviceId);
  const gardenId = str(req.body?.gardenId);
  const memberToken = str(req.body?.memberToken);
  const knownImageHash = str(req.body?.knownImageHash);

  const db = getFirestore();
  const member = await verifyMember(db, gardenId, deviceId, memberToken);
  if (!member) {
    res.status(403).json({ error: "not_authorized" });
    return;
  }

  try {
    const incoming = req.body?.plan;
    let planRev: number | undefined;
    if (incoming && typeof incoming === "object" && member.role === "owner") {
      const paths = Array.isArray(incoming.paths) ? incoming.paths.slice(0, MAX_ITEMS) : [];
      const sunZones = Array.isArray(incoming.sunZones) ? incoming.sunZones.slice(0, MAX_ITEMS) : [];
      const irrigationZones = Array.isArray(incoming.irrigationZones)
        ? (incoming.irrigationZones as unknown[]).filter((z): z is string => typeof z === "string").map((z) => z.slice(0, 80)).slice(0, 200)
        : [];
      const imageHash = str(incoming.imageHash) || null;
      const image = typeof req.body?.image === "string" ? (req.body.image as string) : null;
      if (image && image.length > MAX_IMAGE_CHARS) {
        res.status(400).json({ error: "image_too_large" });
        return;
      }
      const next: GardenPlanDoc = {
        paths, sunZones, irrigationZones, imageHash,
        imageWidth: typeof incoming.imageWidth === "number" ? incoming.imageWidth : null,
        imageHeight: typeof incoming.imageHeight === "number" ? incoming.imageHeight : null,
        updatedAt: Date.now(),
      };
      if (Buffer.byteLength(JSON.stringify(next), "utf8") > MAX_PLAN_BYTES) {
        res.status(400).json({ error: "plan_too_large" });
        return;
      }

      planRev = await db.runTransaction(async (tx) => {
        const [currentSnap, signalSnap] = await Promise.all([tx.get(planRef(db, gardenId)), tx.get(signalRef(db, gardenId))]);
        const current = currentSnap.exists ? (currentSnap.data() as GardenPlanDoc) : null;
        const rev = signalSnap.exists ? ((signalSnap.data() as GardenSignalDoc).planRev ?? 0) : 0;
        const same = current !== null &&
          JSON.stringify({ ...current, updatedAt: 0 }) === JSON.stringify({ ...next, updatedAt: 0 });
        if (same && !image) return rev;

        tx.set(planRef(db, gardenId), next);
        if (image && imageHash) tx.set(planImageRef(db, gardenId), { data: image, hash: imageHash });
        if (!imageHash) tx.delete(planImageRef(db, gardenId));
        tx.set(signalRef(db, gardenId), { planRev: FieldValue.increment(1), updatedAt: Date.now() }, { merge: true });
        return rev + 1;
      });
    }

    const [planSnap, signalSnap] = await Promise.all([planRef(db, gardenId).get(), signalRef(db, gardenId).get()]);
    const plan = planSnap.exists ? (planSnap.data() as GardenPlanDoc) : null;
    let image: string | null = null;
    if (plan?.imageHash && plan.imageHash !== knownImageHash) {
      const imageSnap = await planImageRef(db, gardenId).get();
      image = imageSnap.exists ? ((imageSnap.data() as { data?: string }).data ?? null) : null;
    }
    res.status(200).json({
      plan,
      image,
      planRev: planRev ?? (signalSnap.exists ? ((signalSnap.data() as GardenSignalDoc).planRev ?? 0) : 0),
    });
  } catch (err) {
    console.error("syncGardenPlan failed", err);
    res.status(500).json({ error: "internal_error" });
  }
});

/**
 * A cheap "has anything changed?" check for clients without a Firestore listener (the desktop
 * app, the car display): two small document reads instead of a full syncGarden. They poll this
 * and only run a full sync (or plan fetch) when a counter has moved.
 */
export const getGardenSignal = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  const db = getFirestore();
  const gardenId = str(req.body?.gardenId);
  const member = await verifyMember(db, gardenId, str(req.body?.deviceId), str(req.body?.memberToken));
  if (!member) {
    res.status(403).json({ error: "not_authorized" });
    return;
  }
  const snap = await signalRef(db, gardenId).get();
  const d = snap.exists ? (snap.data() as GardenSignalDoc) : null;
  res.status(200).json({ rev: d?.rev ?? 0, membershipRev: d?.membershipRev ?? 0, planRev: d?.planRev ?? 0 });
});
