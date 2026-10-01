// Tests for the sync merge (lib/gardenSync.js is the compiled src/gardenSync.ts).
// Run with `npm test` — builds first, then uses Node's built-in test runner.
const test = require("node:test");
const assert = require("node:assert/strict");
const { mergeGarden, emptyGardenDoc, normalizeGardenDoc } = require("../lib/gardenSync.js");

const plant = (id, updatedAt, extra = {}) => ({ id, updatedAt, name: `Plant ${id}`, ...extra });
const payload = (over = {}) => ({ plants: [], plantTombstones: [], careLog: [], careLogTombstones: [], photos: [], photoTombstones: [], ...over });

test("newer edit wins, older edit is ignored", () => {
  const stored = { ...emptyGardenDoc(), plants: { P1: plant("P1", 100, { name: "Old" }) } };
  const newer = mergeGarden(stored, payload({ plants: [plant("P1", 200, { name: "New" })] }));
  assert.equal(newer.plants.P1.name, "New");
  const older = mergeGarden(stored, payload({ plants: [plant("P1", 50, { name: "Stale" })] }));
  assert.equal(older.plants.P1.name, "Old");
});

test("an identical timestamp does not overwrite (strictly newer wins)", () => {
  const stored = { ...emptyGardenDoc(), plants: { P1: plant("P1", 100, { name: "Server" }) } };
  const result = mergeGarden(stored, payload({ plants: [plant("P1", 100, { name: "Device" })] }));
  assert.equal(result.plants.P1.name, "Server");
});

test("a delete removes the record and leaves a tombstone", () => {
  const stored = { ...emptyGardenDoc(), plants: { P1: plant("P1", 100) } };
  const result = mergeGarden(stored, payload({ plantTombstones: [{ id: "P1", deletedAt: 150 }] }));
  assert.equal(result.plants.P1, undefined);
  assert.equal(result.plantTombstones.P1, 150);
});

test("a deleted record is not resurrected by a device that still has the old copy", () => {
  const stored = { ...emptyGardenDoc(), plantTombstones: { P1: 150 } };
  const result = mergeGarden(stored, payload({ plants: [plant("P1", 100)] }));
  assert.equal(result.plants.P1, undefined);
});

test("an edit made after the delete brings the record back", () => {
  const stored = { ...emptyGardenDoc(), plantTombstones: { P1: 150 } };
  const result = mergeGarden(stored, payload({ plants: [plant("P1", 200)] }));
  assert.ok(result.plants.P1);
  assert.equal(result.plantTombstones.P1, undefined);
});

test("an unchanged merge serialises identically (syncGarden relies on this to skip writes)", () => {
  const stored = {
    ...emptyGardenDoc(),
    plants: { P1: plant("P1", 100), P2: plant("P2", 120) },
    careLog: { C1: { id: "C1", plantId: "P1", updatedAt: 90 } },
  };
  const same = mergeGarden(stored, payload({ plants: [plant("P1", 100), plant("P2", 120)] }));
  assert.equal(JSON.stringify(same), JSON.stringify(stored));
});

test("plants and care log merge independently", () => {
  const result = mergeGarden(emptyGardenDoc(), payload({
    plants: [plant("P1", 1)],
    careLog: [{ id: "C1", plantId: "P1", updatedAt: 1 }],
  }));
  assert.deepEqual(Object.keys(result.plants), ["P1"]);
  assert.deepEqual(Object.keys(result.careLog), ["C1"]);
});

const photo = (id, updatedAt, extra = {}) => ({ id, updatedAt, kind: "progress", location: "Back Garden", uri: `https://dl.dropboxusercontent.com/${id}.jpg`, takenAt: 1, ...extra });

test("photos merge last-write-wins with tombstones, like plants", () => {
  const stored = { ...emptyGardenDoc(), photos: { LP1: photo("LP1", 100) } };
  const added = mergeGarden(stored, payload({ photos: [photo("LP2", 110, { kind: "extra", plantId: "P1" })] }));
  assert.deepEqual(Object.keys(added.photos).sort(), ["LP1", "LP2"]);
  const deleted = mergeGarden(added, payload({ photoTombstones: [{ id: "LP1", deletedAt: 150 }] }));
  assert.equal(deleted.photos.LP1, undefined);
  assert.equal(deleted.photoTombstones.LP1, 150);
});

test("a client that predates photo sync (no photos keys at all) leaves stored photos untouched", () => {
  const stored = { ...emptyGardenDoc(), photos: { LP1: photo("LP1", 100) } };
  const { photos, photoTombstones, ...legacyPayload } = payload();
  const result = mergeGarden(stored, legacyPayload);
  assert.deepEqual(result.photos, stored.photos);
});

test("a garden stored before photos existed normalises to empty photos, so an unchanged merge is still identical", () => {
  const legacyStored = { plants: { P1: plant("P1", 100) }, plantTombstones: {}, careLog: {}, careLogTombstones: {} };
  const stored = normalizeGardenDoc(legacyStored);
  assert.deepEqual(stored.photos, {});
  const same = mergeGarden(stored, payload({ plants: [plant("P1", 100)] }));
  assert.equal(JSON.stringify(same), JSON.stringify(stored));
});
