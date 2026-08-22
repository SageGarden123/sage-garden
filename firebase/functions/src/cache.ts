import * as crypto from "crypto";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import type { FrequencySuggestion } from "./anthropic";

function normalize(text: string): string {
  return text.trim().toLowerCase().replace(/\s+/g, " ");
}

function hashKey(text: string): string {
  return crypto.createHash("sha256").update(normalize(text)).digest("hex");
}

/** Exact-match cache — no embeddings/semantic search in v1. */
export async function getCachedChatResponse(question: string): Promise<string | null> {
  const db = getFirestore();
  const ref = db.collection("sageChatCache").doc(hashKey(question));
  const snap = await ref.get();
  if (!snap.exists) return null;
  await ref.update({ hitCount: FieldValue.increment(1) });
  return (snap.data()?.response as string | undefined) ?? null;
}

export async function setCachedChatResponse(question: string, response: string): Promise<void> {
  const db = getFirestore();
  await db.collection("sageChatCache").doc(hashKey(question)).set({
    question: normalize(question),
    response,
    hitCount: 0,
    createdAt: FieldValue.serverTimestamp(),
  });
}

/** Keyed purely on species name (not device-specific) — cache hits are shared globally across every install. */
export async function getCachedFrequencies(sciName: string): Promise<FrequencySuggestion | null> {
  const db = getFirestore();
  const ref = db.collection("speciesFrequencyCache").doc(hashKey(sciName));
  const snap = await ref.get();
  if (!snap.exists) return null;
  await ref.update({ hitCount: FieldValue.increment(1) });
  const data = snap.data()!;
  return {
    wateringFrequencyDays: data.wateringFrequencyDays ?? null,
    fertiliseFrequencyDays: data.fertiliseFrequencyDays ?? null,
    pruneFrequencyDays: data.pruneFrequencyDays ?? null,
    feedFrequencyDays: data.feedFrequencyDays ?? null,
  };
}

export async function setCachedFrequencies(sciName: string, suggestion: FrequencySuggestion): Promise<void> {
  const db = getFirestore();
  await db.collection("speciesFrequencyCache").doc(hashKey(sciName)).set({
    sciName: normalize(sciName),
    ...suggestion,
    hitCount: 0,
    createdAt: FieldValue.serverTimestamp(),
  });
}
