import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { FREE_PROMPT_LIMIT, TRIAL_DAYS } from "./config";

export type EntitlementSource = "trial" | "promo" | "override" | "none";

export interface EntitlementSnapshot {
  isPro: boolean;
  source: EntitlementSource;
  trialExpiresAt: number | null; // epoch millis
  promoCode: string | null;
  sagePromptCount: number;
  sagePromptLimit: number;
}

interface DeviceDoc {
  installedAt?: Timestamp;
  trialExpiresAt?: Timestamp;
  promoCode?: string | null;
  isProOverride?: boolean | null;
  sagePromptCount?: number;
  lastSeenAt?: Timestamp;
}

/**
 * Ensures a device doc exists (creating it with a fresh 14-day trial on first sight)
 * and returns its current entitlement snapshot.
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
    const trialExpiresAt = Timestamp.fromMillis(now.toMillis() + TRIAL_DAYS * 24 * 60 * 60 * 1000);
    data = {
      installedAt: now,
      trialExpiresAt,
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

  const inTrial = !!data.trialExpiresAt && data.trialExpiresAt.toMillis() > now.toMillis();
  const hasPromo = !!data.promoCode;

  let isPro = false;
  let source: EntitlementSource = "none";
  if (data.isProOverride === true) {
    isPro = true;
    source = "override";
  } else if (hasPromo) {
    isPro = true;
    source = "promo";
  } else if (inTrial) {
    isPro = true;
    source = "trial";
  }

  return {
    isPro,
    source,
    trialExpiresAt: data.trialExpiresAt ? data.trialExpiresAt.toMillis() : null,
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
