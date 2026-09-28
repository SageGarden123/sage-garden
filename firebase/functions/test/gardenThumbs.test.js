// Tests for thumbnail bucketing (lib/gardenThumbs.js is the compiled src/gardenThumbs.ts).
const test = require("node:test");
const assert = require("node:assert/strict");
const { splitThumbs, joinThumbs, bucketFor, hasInlineThumbs, THUMB_BUCKETS } = require("../lib/gardenThumbs.js");

const plants = {
  P0001: { id: "P0001", updatedAt: 1, name: "Rosemary", photoThumbnail: "AAAA" },
  P0002: { id: "P0002", updatedAt: 2, name: "Basil", photoThumbnail: null },
  Q0001: { id: "Q0001", updatedAt: 3, name: "Lemon", photoThumbnail: "BBBB" },
};

test("bucket choice is stable and in range", () => {
  for (const id of Object.keys(plants)) {
    const b = bucketFor(id);
    assert.equal(b, bucketFor(id));
    assert.ok(b >= 0 && b < THUMB_BUCKETS);
  }
});

test("split moves thumbnails out of the records; join puts them back", () => {
  const { plants: stripped, buckets } = splitThumbs(plants);
  assert.equal(stripped.P0001.photoThumbnail, undefined);
  assert.equal(stripped.P0002.photoThumbnail, null); // no thumbnail stays as it was
  assert.equal(hasInlineThumbs(stripped), false);
  const joined = joinThumbs(stripped, buckets);
  assert.equal(joined.P0001.photoThumbnail, "AAAA");
  assert.equal(joined.Q0001.photoThumbnail, "BBBB");
  assert.equal(joined.P0002.photoThumbnail, null);
  assert.equal(Object.keys(joined).length, 3);
});

test("pre-migration inline thumbnails are detected and kept when no bucket has one", () => {
  assert.equal(hasInlineThumbs(plants), true);
  assert.equal(joinThumbs(plants, {}).P0001.photoThumbnail, "AAAA");
});
