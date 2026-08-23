import { onRequest } from "firebase-functions/v2/https";
import { androidpublisher } from "@googleapis/androidpublisher";
import { GoogleAuth } from "google-auth-library";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { resolveEntitlement } from "../entitlement";
import { verifyAppCheck } from "../verifyAppCheck";

const PACKAGE_NAME = "com.example.sagegarden";

// Anything else (SUBSCRIPTION_STATE_CANCELED/EXPIRED/ON_HOLD/PAUSED/PENDING) is not paid access —
// grace period is the one non-obvious inclusion: Play keeps billing but access should continue.
const ACTIVE_STATES = new Set(["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"]);

/**
 * Re-verifies a Play Billing purchase token against the Google Play Developer API before granting
 * any entitlement — a client-reported purchase state is never trusted on its own, since both the
 * app and the device it runs on could be compromised. Auth is Application Default Credentials
 * (the Cloud Function's own runtime service account) — no key file needed, but that service
 * account must be linked in Play Console (Setup → API access) with access to this app's financial
 * data before this will succeed. See SETUP.md for the one-time manual steps.
 */
export const verifyPurchase = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  if (!(await verifyAppCheck(req, res))) return;

  const deviceId = typeof req.body?.deviceId === "string" ? req.body.deviceId : null;
  const productId = typeof req.body?.productId === "string" ? req.body.productId : null;
  const purchaseToken = typeof req.body?.purchaseToken === "string" ? req.body.purchaseToken : null;
  if (!deviceId || !productId || !purchaseToken) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  try {
    const auth = new GoogleAuth({ scopes: ["https://www.googleapis.com/auth/androidpublisher"] });
    const publisher = androidpublisher({ version: "v3", auth });
    const { data: subscription } = await publisher.purchases.subscriptionsv2.get({
      packageName: PACKAGE_NAME,
      token: purchaseToken,
    });

    const isActive = !!subscription.subscriptionState && ACTIVE_STATES.has(subscription.subscriptionState);
    const expiryTimeStr = subscription.lineItems?.[0]?.expiryTime;
    const expiryMillis = expiryTimeStr ? Date.parse(expiryTimeStr) : NaN;

    await getFirestore()
      .collection("devices")
      .doc(deviceId)
      .set(
        {
          purchaseProductId: productId,
          purchaseToken,
          purchaseExpiresAt:
            isActive && !Number.isNaN(expiryMillis) ? Timestamp.fromMillis(expiryMillis) : Timestamp.fromMillis(0),
        },
        { merge: true }
      );

    if (!isActive) {
      res.status(400).json({ error: "not_active" });
      return;
    }

    const entitlement = await resolveEntitlement(deviceId, false);
    res.status(200).json(entitlement);
  } catch (err) {
    console.error("verifyPurchase: Google Play Developer API call failed", err);
    res.status(502).json({ error: "verification_failed" });
  }
});
