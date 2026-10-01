/**
 * Merge logic for multi-device plant/care-log sync (phone + desktop), factored out of the
 * syncGarden handler so it can be reasoned about (and tested) without Firestore/HTTP in the way.
 *
 * Design: last-write-wins per record, keyed by id, using a client-set `updatedAt` millis
 * timestamp — not arrival order at the server, which matters once one device has been offline.
 * Deletes are tombstones (id -> deletedAt) rather than plain removal, since without them a
 * deleted-on-one-device record would just look like "not sent yet" and get resurrected by the
 * next device that syncs its full list. An edit newer than a pending delete resurrects the
 * record (rare, but the sane default — see mergeCollection).
 */

export interface SyncRecord {
  id: string;
  updatedAt: number;
  [key: string]: unknown;
}

export interface Tombstone {
  id: string;
  deletedAt: number;
}

export interface GardenPayload {
  plants: SyncRecord[];
  plantTombstones: Tombstone[];
  careLog: SyncRecord[];
  careLogTombstones: Tombstone[];
  /** Extra / progress (zone) / growth-timeline photo records, told apart by their `kind` field.
   * Only Dropbox-linked photos are ever sent (a phone-local image can't be shown anywhere else),
   * and clients that predate photo sync simply send none — which merges as "no change". */
  photos: SyncRecord[];
  photoTombstones: Tombstone[];
}

export interface GardenDoc {
  plants: Record<string, SyncRecord>;
  plantTombstones: Record<string, number>;
  careLog: Record<string, SyncRecord>;
  careLogTombstones: Record<string, number>;
  photos: Record<string, SyncRecord>;
  photoTombstones: Record<string, number>;
}

export function emptyGardenDoc(): GardenDoc {
  return { plants: {}, plantTombstones: {}, careLog: {}, careLogTombstones: {}, photos: {}, photoTombstones: {} };
}

/** Fills in collections a garden stored before they existed (photos, added later), so merging and
 * change detection treat "absent" as empty rather than as a difference on every sync. */
export function normalizeGardenDoc(doc: Partial<GardenDoc>): GardenDoc {
  return {
    plants: doc.plants ?? {},
    plantTombstones: doc.plantTombstones ?? {},
    careLog: doc.careLog ?? {},
    careLogTombstones: doc.careLogTombstones ?? {},
    photos: doc.photos ?? {},
    photoTombstones: doc.photoTombstones ?? {},
  };
}

function mergeCollection(
  storedItems: Record<string, SyncRecord>,
  storedTombstones: Record<string, number>,
  incomingItems: SyncRecord[],
  incomingTombstones: Tombstone[]
): { items: Record<string, SyncRecord>; tombstones: Record<string, number> } {
  const items = { ...storedItems };
  const tombstones = { ...storedTombstones };

  for (const incoming of incomingItems) {
    const tombstoneAt = tombstones[incoming.id];
    if (tombstoneAt !== undefined && tombstoneAt >= incoming.updatedAt) continue; // a delete already won for this id

    const existing = items[incoming.id];
    if (!existing || existing.updatedAt < incoming.updatedAt) {
      items[incoming.id] = incoming;
      if (tombstoneAt !== undefined) delete tombstones[incoming.id]; // edit is newer than a pending delete — resurrect
    }
  }

  for (const t of incomingTombstones) {
    const existing = items[t.id];
    if (existing && existing.updatedAt > t.deletedAt) continue; // edited after this delete was requested — edit wins
    if (existing) delete items[t.id];
    tombstones[t.id] = Math.max(tombstones[t.id] ?? 0, t.deletedAt);
  }

  return { items, tombstones };
}

export function mergeGarden(stored: GardenDoc, incoming: GardenPayload): GardenDoc {
  const plantsMerge = mergeCollection(stored.plants, stored.plantTombstones, incoming.plants, incoming.plantTombstones);
  const careLogMerge = mergeCollection(stored.careLog, stored.careLogTombstones, incoming.careLog, incoming.careLogTombstones);
  const photosMerge = mergeCollection(stored.photos ?? {}, stored.photoTombstones ?? {}, incoming.photos ?? [], incoming.photoTombstones ?? []);
  return {
    plants: plantsMerge.items,
    plantTombstones: plantsMerge.tombstones,
    careLog: careLogMerge.items,
    careLogTombstones: careLogMerge.tombstones,
    photos: photosMerge.items,
    photoTombstones: photosMerge.tombstones,
  };
}
