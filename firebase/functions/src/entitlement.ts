import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { FREE_PROMPT_LIMIT } from "./config";

export type EntitlementSource = "promo" | "override" | "none";

export interface EntitlementSnapshot {
  isPro: boolean;
  source: EntitlementSource;
  promoCode: string | null;
  sagePromptCount: number;
  sagePromptLimit: number;
}

interface DeviceDoc {
  installedAt?: Timestamp;
  promoCode?: string | null;
  isProOverride?: boolean | null;
  sagePromptCount?: number;
  lastSeenAt?: Timestamp;
}

/**
 * Ensures a device doc exists and returns its current entitlement snapshot. The app itself is
 * entirely free — a redeemed promo code just removes the Sage question limit and raises the daily
 * AI plant-ID limit, nothing more.
 *
 * A redeemed promo code grants PERMANENT Pro access to that device — once
 * `promoCode` is set on the device doc, it is never re-validated against the
 * promoCodes collection here. Deactivating/expiring a code only stops *new*
 * redemptions (enforced in redeemPromoCode.ts); it never revokes devices that
 * already redeemed it.
 */
export async function resolveEntitlement(deviceId: string, touch = true): Promise<EntitlementSnapshot> {
  const db = getFirestore();
  const ref = db.collection("devices").doc(deviceId);
  const snap = await ref.get();
  const now = Timestamp.now();

  let data: DeviceDoc;
  if (!snap.exists) {
    data = {
      installedAt: now,
      promoCode: null,
      isProOverride: null,
      sagePromptCount: 0,
      lastSeenAt: now,
    };
    await ref.set(data);
  } else {
    data = snap.data() as DeviceDoc;
    if (touch) {
      await ref.update({ lastSeenAt: now });
    }
  }

  const hasPromo = !!data.promoCode;

  let isPro = false;
  let source: EntitlementSource = "none";
  if (data.isProOverride === true) {
    isPro = true;
    source = "override";
  } else if (hasPromo) {
    isPro = true;
    source = "promo";
  }

  return {
    isPro,
    source,
    promoCode: data.promoCode ?? null,
    sagePromptCount: data.sagePromptCount ?? 0,
    sagePromptLimit: FREE_PROMPT_LIMIT,
  };
}

/** Called once per successful Sage call (chat or auto-fill) — shared counter, see sageAutoFill.ts's "assumption" note. */
export async function incrementSagePromptCount(deviceId: string): Promise<void> {
  const db = getFirestore();
  await db.collection("devices").doc(deviceId).update({
    sagePromptCount: FieldValue.increment(1),
  });
}
