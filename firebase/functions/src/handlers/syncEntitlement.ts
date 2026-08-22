import { onRequest } from "firebase-functions/v2/https";
import { resolveEntitlement } from "../entitlement";
import { verifyAppCheck } from "../verifyAppCheck";

export const syncEntitlement = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  if (!(await verifyAppCheck(req, res))) return;

  const deviceId = typeof req.body?.deviceId === "string" ? req.body.deviceId : null;
  if (!deviceId) {
    res.status(400).json({ error: "invalid_request" });
    return;
  }

  const entitlement = await resolveEntitlement(deviceId);
  res.status(200).json(entitlement);
});
