import { getFirestore } from "firebase-admin/firestore";
import { DAILY_REQUEST_CEILING } from "./config";

function todayKey(): string {
  return new Date().toISOString().slice(0, 10); // UTC yyyy-mm-dd
}

/**
 * Atomically checks the global daily Anthropic-call ceiling and reserves a slot if under it.
 * Returns false if the ceiling has already been reached — caller must not call Anthropic in that case.
 * This is the safety net independent of any individual device's own entitlement/prompt cap.
 */
export async function tryReserveDailySpend(): Promise<boolean> {
  const db = getFirestore();
  const ref = db.collection("dailySpend").doc(todayKey());
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const count = snap.exists ? ((snap.data()?.requestCount as number | undefined) ?? 0) : 0;
    if (count >= DAILY_REQUEST_CEILING) return false;
    tx.set(ref, { requestCount: count + 1 }, { merge: true });
    return true;
  });
}
