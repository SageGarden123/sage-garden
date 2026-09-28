import type { Firestore } from "firebase-admin/firestore";
import type { SyncRecord } from "./gardenSync";

/**
 * Plant photo thumbnails live outside the main garden document. A Firestore document is capped at
 * 1MB, and thumbnails (small base64 JPEGs, one per photographed plant) made up almost all of a
 * garden doc's size — a large, well-photographed garden would eventually have hit the cap and
 * stopped syncing. They're now spread across up to [THUMB_BUCKETS] "bucket" docs at
 * gardens/{gardenId}/thumbs/b{n}, chosen by a stable hash of the plant id.
 *
 * This is purely a storage detail of syncGarden: it re-attaches thumbnails before merging and
 * returns full plant records, so no client (Android, desktop, car) sees any difference. Gardens
 * stored the old way (thumbnails inline) are migrated on their next sync.
 */
export const THUMB_BUCKETS = 16;
export const THUMB_FIELD = "photoThumbnail";

export type ThumbBuckets = Record<number, Record<string, string>>;

/** FNV-1a — stable across deploys and runtimes, unlike anything based on object ordering. */
export function bucketFor(plantId: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < plantId.length; i++) {
    h ^= plantId.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h % THUMB_BUCKETS;
}

export function thumbBucketRef(db: Firestore, gardenId: string, bucket: number) {
  return db.collection("gardens").doc(gardenId).collection("thumbs").doc(`b${bucket}`);
}

/** Full records → records without inline thumbnails, plus the thumbnails grouped by bucket. */
export function splitThumbs(plants: Record<string, SyncRecord>): { plants: Record<string, SyncRecord>; buckets: ThumbBuckets } {
  const stripped: Record<string, SyncRecord> = {};
  const buckets: ThumbBuckets = {};
  for (const [id, record] of Object.entries(plants)) {
    const thumb = record[THUMB_FIELD];
    if (typeof thumb === "string" && thumb.length > 0) {
      const { [THUMB_FIELD]: _omit, ...rest } = record;
      stripped[id] = rest as SyncRecord;
      (buckets[bucketFor(id)] ??= {})[id] = thumb;
    } else {
      stripped[id] = record;
    }
  }
  return { plants: stripped, buckets };
}

/** Re-attaches bucketed thumbnails. A thumbnail still stored inline (pre-migration) is kept unless a bucket has one. */
export function joinThumbs(plants: Record<string, SyncRecord>, buckets: ThumbBuckets): Record<string, SyncRecord> {
  const joined: Record<string, SyncRecord> = {};
  for (const [id, record] of Object.entries(plants)) {
    const thumb = buckets[bucketFor(id)]?.[id];
    joined[id] = thumb === undefined ? record : { ...record, [THUMB_FIELD]: thumb };
  }
  return joined;
}

/** True while a garden doc still carries thumbnails inline (the pre-bucket layout). */
export function hasInlineThumbs(plants: Record<string, SyncRecord>): boolean {
  return Object.values(plants).some((r) => typeof r[THUMB_FIELD] === "string" && (r[THUMB_FIELD] as string).length > 0);
}
