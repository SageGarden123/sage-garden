// End-to-end check of syncGarden against the Firebase emulators (never production):
//   firebase emulators:exec --only functions,firestore --project demo-sage "node test/integration/syncGarden.emulator.js"
// Covers: thumbnails bucketed out of the garden doc, full records returned, no-op syncs don't
// write or bump the change signal, pre-bucket (inline) gardens migrate, and deletes really
// remove plants from storage, and photo records sync (and survive photo-less clients).
const assert = require("node:assert/strict");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");

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

  // 6. Photos: Dropbox-linked photos sync; phone-local content:// ones and unknown kinds are dropped.
  const photo = (id, updatedAt, extra = {}) => ({ id, updatedAt, kind: "progress", location: "Back Garden", uri: `https://dl.dropboxusercontent.com/${id}.jpg`, takenAt: 5, label: "", ...extra });
  const withPhotos = await sync({
    ...base, memberToken: token, plants: [],
    photos: [photo("LP-1", 20), photo("EP-1", 21, { kind: "extra", plantId: "P0001", location: undefined }),
      photo("LP-local", 22, { uri: "content://media/external/images/1" }), photo("XX-1", 23, { kind: "bogus" })],
    photoTombstones: [],
  });
  assert.deepEqual(withPhotos.photos.map((p) => p.id).sort(), ["EP-1", "LP-1"], "only valid shareable photos stored");
  const revWithPhotos = withPhotos.signalRev;

  // 7. A client that predates photo sync (desktop / car / older phone — no photos keys) neither wipes
  //    them nor counts as a change.
  const legacyClient = await sync({ ...base, memberToken: token, plants: [] });
  assert.deepEqual(legacyClient.photos.map((p) => p.id).sort(), ["EP-1", "LP-1"], "photos survive a photo-less sync");
  assert.equal(legacyClient.signalRev, revWithPhotos, "photo-less no-op sync must not bump the change signal");

  // 8. A photo delete removes it and is not resurrected by a device still holding the old copy.
  await sync({ ...base, memberToken: token, plants: [], photos: [], photoTombstones: [{ id: "LP-1", deletedAt: 30 }] });
  const stale = await sync({ ...base, memberToken: token, plants: [], photos: [photo("LP-1", 20)], photoTombstones: [] });
  assert.deepEqual(stale.photos.map((p) => p.id), ["EP-1"], "deleted photo stays deleted");

  // 9. A garden stored before photo sync existed (no photos fields) must not be rewritten on its
  //    next no-op sync — otherwise deploying this would write every garden once per open app.
  const legacyRevBefore = (await sync({ deviceId: legacyId, gardenId: legacyId, memberToken: legacy.memberToken, plants: [], plantTombstones: [], careLog: [], careLogTombstones: [] })).signalRev;
  await db.collection("gardens").doc(legacyId).update({ photos: FieldValue.delete(), photoTombstones: FieldValue.delete() });
  const legacyAfter = await sync({ deviceId: legacyId, gardenId: legacyId, memberToken: legacy.memberToken, plants: [], plantTombstones: [], careLog: [], careLogTombstones: [] });
  assert.equal(legacyAfter.signalRev, legacyRevBefore, "pre-photo garden: no-op sync is still a no-op");
  assert.deepEqual(legacyAfter.photos, []);

  console.log("syncGarden emulator integration: all checks passed");
})().catch((e) => { console.error(e); process.exit(1); });
