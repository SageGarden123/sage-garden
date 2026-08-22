import { getAppCheck } from "firebase-admin/app-check";
import type { Request } from "firebase-functions/v2/https";
import type { Response } from "express";
import { APP_CHECK_ENFORCED } from "./config";

/**
 * Verifies the "X-Firebase-AppCheck" header Play-Integrity-attests that this request came from a
 * genuine copy of the app, not a direct call against the bare Functions URL with a spoofed
 * deviceId. Call at the top of every handler, before touching Firestore/Anthropic.
 *
 * While APP_CHECK_ENFORCED is false, a missing/invalid token is logged but the request still
 * proceeds — the per-device/global limits already in place remain the real backstop during this
 * monitor period. Once enforced, an invalid token gets a 401 and the handler returns immediately.
 *
 * @returns true if the handler should continue, false if it already sent a response and the
 * caller must return immediately.
 */
export async function verifyAppCheck(req: Request, res: Response): Promise<boolean> {
  const token = req.header("X-Firebase-AppCheck");
  if (!token) {
    console.warn("verifyAppCheck: missing token", { enforced: APP_CHECK_ENFORCED });
    if (APP_CHECK_ENFORCED) {
      res.status(401).json({ error: "app_check_required" });
      return false;
    }
    return true;
  }

  try {
    await getAppCheck().verifyToken(token);
    return true;
  } catch (err) {
    console.warn("verifyAppCheck: invalid token", { enforced: APP_CHECK_ENFORCED, err: (err as Error).message });
    if (APP_CHECK_ENFORCED) {
      res.status(401).json({ error: "app_check_invalid" });
      return false;
    }
    return true;
  }
}
