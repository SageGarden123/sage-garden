// End-to-end check of syncGarden against the Firebase emulators (never production):
//   firebase emulators:exec --only functions,firestore --project demo-sage "node test/integration/syncGarden.emulator.js"
// Covers: thumbnails bucketed out of the garden doc, full records returned, no-op syncs don't
// write or bump the change signal, pre-bucket (inline) gardens migrate, and deletes really
// remove plants from storage.
const assert = require("node:assert/strict");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

const PROJECT = process.env.GCLOUD_PROJECT || "demo-sage";
const URL = `http://127.0.0.1:5001/${PROJECT}/us-central1/syncGarden`;
initializeApp({ projectId: PROJECT });
const db = getFirestore();

const plant = (id, updatedAt, thumb) => ({ id, updatedAt, name: `Plant ${id}`, photoThumbnail: thumb ?? null });

async function sync(body) {
  const res = await fetch(URL, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const json = await res.json();
  assert.equal(res.status, 200, JSON.stringify(json));
  return json;
}

(async () => {
  const deviceId = "device-" + Date.now();
  const base = { deviceId, gardenId: deviceId, plantTombstones: [], careLog: [], careLogTombstones: [] };

  // 1. First sync provisions the garden and buckets the thumbnails.
  const first = await sync({ ...base, plants: [plant("P0001", 1, "AAAA"), plant("P0002", 2, null), plant("P0003", 3, "CCCC")] });
  const token = first.memberToken;
  const gardenDoc = (await db.collection("gardens").doc(deviceId).get()).data();
  assert.ok(Object.values(gardenDoc.plants).every((p) => p.photoThumbnail === undefined || p.photoThumbnail === null), "thumbnails left the main doc");
  assert.ok(gardenDoc.thumbBuckets.length >= 1);
  const returned = Object.fromEntries(first.plants.map((p) => [p.id, p]));
  assert.equal(returned.P0001.photoThumbnail, "AAAA");
  assert.equal(returned.P0003.photoThumbnail, "CCCC");
  const rev1 = first.signalRev;

  // 2. Re-syncing the same data is a no-op: same rev, full thumbnails still returned.
  const again = await sync({ ...base, memberToken: token, plants: [plant("P0001", 1, "AAAA"), plant("P0002", 2, null), plant("P0003", 3, "CCCC")] });
  assert.equal(again.signalRev, rev1, "no-op sync must not bump the change signal");
  assert.equal(Object.fromEntries(again.plants.map((p) => [p.id, p])).P0001.photoThumbnail, "AAAA");

  // 3. A delete really removes the plant (and its thumbnail) from storage.
  const del = await sync({ ...base, memberToken: token, plants: [], plantTombstones: [{ id: "P0003", deletedAt: 10 }] });
  assert.ok(!del.plants.some((p) => p.id === "P0003"));
  assert.ok(del.signalRev > rev1);
  const afterDelete = (await db.collection("gardens").doc(deviceId).get()).data();
  assert.equal(afterDelete.plants.P0003, undefined, "deleted plant removed from the stored map");
  const buckets = await db.collection("gardens").doc(deviceId).collection("thumbs").get();
  assert.ok(!buckets.docs.some((d) => d.data().thumbs.P0003), "deleted plant's thumbnail removed");

  // 4. A garden stored the old way (thumbnails inline, no buckets) migrates on its next sync.
  const legacyId = "legacy-" + Date.now();
  await db.collection("gardens").doc(legacyId).set({
    plants: { P0001: plant("P0001", 1, "OLD1"), P0002: plant("P0002", 2, "OLD2") },
    plantTombstones: {}, careLog: {}, careLogTombstones: {}, ownerDeviceId: legacyId, name: "Legacy",
  });
  const legacy = await sync({ deviceId: legacyId, gardenId: legacyId, plants: [], plantTombstones: [], careLog: [], careLogTombstones: [] });
  const migrated = (await db.collection("gardens").doc(legacyId).get()).data();
  assert.equal(migrated.plants.P0001.photoThumbnail, undefined, "inline thumbnail moved out");
  assert.equal(Object.fromEntries(legacy.plants.map((p) => [p.id, p])).P0002.photoThumbnail, "OLD2", "still returned to clients");

  // 5. A desktop/car app joins the phone's garden as ITSELF and must not disturb the phone's token.
  const phoneTokenBefore = (await db.collection("gardens").doc(deviceId).collection("members").doc(deviceId).get()).data().memberToken;
  const desktopId = "desktop-" + Date.now();
  const joined = await sync({ deviceId: desktopId, gardenId: deviceId, deviceName: "Desktop app", plants: [], plantTombstones: [], careLog: [], careLogTombstones: [] });
  assert.ok(joined.memberToken && joined.memberToken !== phoneTokenBefore, "desktop gets its own token");
  const desktopMember = (await db.collection("gardens").doc(deviceId).collection("members").doc(desktopId).get()).data();
  assert.equal(desktopMember.displayName, "Desktop app");
  assert.equal(desktopMember.role, "member");
  await sync({ deviceId: desktopId, gardenId: deviceId, memberToken: joined.memberToken, plants: [], plantTombstones: [], careLog: [], careLogTombstones: [] });
  const phoneTokenAfter = (await db.collection("gardens").doc(deviceId).collection("members").doc(deviceId).get()).data().memberToken;
  assert.equal(phoneTokenAfter, phoneTokenBefore, "phone's token untouched by desktop syncs");

  console.log("syncGarden emulator integration: all checks passed");
})().catch((e) => { console.error(e); process.exit(1); });
