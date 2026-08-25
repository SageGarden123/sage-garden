import { onRequest } from "firebase-functions/v2/https";
import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { resolveEntitlement } from "../entitlement";
import { verifyAppCheck } from "../verifyAppCheck";

class PromoError extends Error {
  constructor(public code: string) {
    super(code);
  }
}

export const redeemPromoCode = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  if (!(await verifyAppCheck(req, res))) return;

  const deviceId = typeof req.body?.deviceId === "string" ? req.body.deviceId : null;
  const rawCode = typeof req.body?.code === "string" ? req.body.code : null;
  if (!deviceId || !rawCode) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  const code = rawCode.trim().toUpperCase();
  if (!code) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  // Ensures the device doc exists before the transaction touches it.
  await resolveEntitlement(deviceId, false);

  const db = getFirestore();
  const codeRef = db.collection("promoCodes").doc(code);
  const deviceRef = db.collection("devices").doc(deviceId);

  try {
    await db.runTransaction(async (tx) => {
      const codeSnap = await tx.get(codeRef);
      if (!codeSnap.exists) throw new PromoError("not_found");

      const data = codeSnap.data()!;
      const now = Timestamp.now();
      if (!data.active) throw new PromoError("inactive");
      if (data.activeFrom && (data.activeFrom as Timestamp).toMillis() > now.toMillis()) {
        throw new PromoError("inactive");
      }
      if (data.activeUntil && (data.activeUntil as Timestamp).toMillis() < now.toMillis()) {
        throw new PromoError("expired");
      }
      const maxRedemptions = data.maxRedemptions as number | null | undefined;
      const redemptionCount = (data.redemptionCount as number | undefined) ?? 0;
      if (maxRedemptions != null && redemptionCount >= maxRedemptions) {
        throw new PromoError("redemption_cap_reached");
      }

      tx.update(codeRef, { redemptionCount: FieldValue.increment(1) });
      tx.update(deviceRef, { promoCode: code });
    });
  } catch (err) {
    if (err instanceof PromoError) {
      res.status(400).json({ error: err.code });
      return;
    }
    console.error("redeemPromoCode failed", err);
    res.status(500).json({ error: "internal_error" });
    return;
  }

  const entitlement = await resolveEntitlement(deviceId, false);
  res.status(200).json(entitlement);
});
