import { onRequest } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { verifyMember } from "./gardenPlan";

/**
 * Satellite imagery for the desktop app's map exports, from Google's Maps Static API. The API key
 * lives only here (Secret Manager: MAPS_STATIC_KEY) — never in an app — and only an approved member
 * of the garden can ask, with a daily cap per device so a bug or misuse can't run up a bill.
 *
 * Google's attribution and logo are part of the returned image, as its terms require. The desktop
 * draws plant markers itself on top, using the same Web Mercator maths as the image.
 */
const MAPS_STATIC_KEY = defineSecret("MAPS_STATIC_KEY");
const DAILY_LIMIT_PER_DEVICE = 60;

function num(x: unknown): number | null {
  return typeof x === "number" && Number.isFinite(x) ? x : null;
}

export const satelliteMap = onRequest({ cors: false, secrets: [MAPS_STATIC_KEY], memory: "256MiB" }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  const deviceId = typeof req.body?.deviceId === "string" ? req.body.deviceId.trim() : "";
  const gardenId = typeof req.body?.gardenId === "string" ? req.body.gardenId.trim() : "";
  const memberToken = typeof req.body?.memberToken === "string" ? req.body.memberToken.trim() : "";
  const lat = num(req.body?.lat);
  const lng = num(req.body?.lng);
  const zoom = num(req.body?.zoom);
  const width = num(req.body?.width);
  const height = num(req.body?.height);
  if (lat === null || lng === null || zoom === null || width === null || height === null ||
      Math.abs(lat) > 85 || Math.abs(lng) > 180 || zoom < 1 || zoom > 21 || width < 64 || width > 640 || height < 64 || height > 640) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  const db = getFirestore();
  if (!(await verifyMember(db, gardenId, deviceId, memberToken))) {
    res.status(403).json({ error: "not_authorized" });
    return;
  }

  const key = MAPS_STATIC_KEY.value();
  if (!key) {
    res.status(503).json({ error: "not_configured" });
    return;
  }

  // Daily cap per device.
  const day = new Date().toISOString().slice(0, 10);
  const usageRef = db.collection("satelliteUsage").doc(`${deviceId}_${day}`);
  const allowed = await db.runTransaction(async (tx) => {
    const snap = await tx.get(usageRef);
    const count = snap.exists ? ((snap.data() as { count?: number }).count ?? 0) : 0;
    if (count >= DAILY_LIMIT_PER_DEVICE) return false;
    tx.set(usageRef, { count: FieldValue.increment(1), expiresAt: Date.now() + 3 * 86_400_000 }, { merge: true });
    return true;
  });
  if (!allowed) {
    res.status(429).json({ error: "daily_limit" });
    return;
  }

  const url = "https://maps.googleapis.com/maps/api/staticmap" +
    `?center=${lat.toFixed(7)},${lng.toFixed(7)}&zoom=${Math.round(zoom)}` +
    `&size=${Math.round(width)}x${Math.round(height)}&scale=2&maptype=satellite&format=jpg&key=${encodeURIComponent(key)}`;
  try {
    const response = await fetch(url);
    if (!response.ok) {
      console.error("satelliteMap: Google returned", response.status, (await response.text()).slice(0, 300));
      res.status(502).json({ error: "upstream_error", status: response.status });
      return;
    }
    const bytes = Buffer.from(await response.arrayBuffer());
    res.status(200).json({ image: bytes.toString("base64"), scale: 2 });
  } catch (err) {
    console.error("satelliteMap failed", err);
    res.status(502).json({ error: "upstream_error" });
  }
});
