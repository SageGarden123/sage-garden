import { onRequest } from "firebase-functions/v2/https";
import { anthropicApiKey } from "../config";
import { resolveEntitlement, incrementSagePromptCount } from "../entitlement";
import { getCachedConditions, setCachedConditions } from "../cache";
import { tryReserveDailySpend } from "../spend";
import { suggestConditions } from "../anthropic";
import { verifyAppCheck } from "../verifyAppCheck";

/** Sibling to sageAutoFill (care frequencies) — suggests optimal sun/water/soil/frost/native/pollinator values for a species instead of care-schedule frequencies. Same entitlement/cache/spend-cap pattern. */
export const sageAutoFillConditions = onRequest(
  { secrets: [anthropicApiKey], cors: false },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ error: "method_not_allowed" });
      return;
    }
    if (!(await verifyAppCheck(req, res))) return;

    const deviceId = typeof req.body?.deviceId === "string" ? req.body.deviceId : null;
    const sciName = typeof req.body?.sciName === "string" ? req.body.sciName.trim() : "";
    if (!deviceId || !sciName) {
      res.status(400).json({ error: "invalid_request" });
      return;
    }

    const entitlement = await resolveEntitlement(deviceId);
    if (!entitlement.isPro && entitlement.sagePromptCount >= entitlement.sagePromptLimit) {
      res.status(403).json({ error: "free_limit_reached", promptsRemaining: 0 });
      return;
    }

    const promptsRemainingAfter = entitlement.isPro
      ? null
      : Math.max(0, entitlement.sagePromptLimit - entitlement.sagePromptCount - 1);

    const cached = await getCachedConditions(sciName);
    if (cached) {
      await incrementSagePromptCount(deviceId);
      res.status(200).json({ suggestion: cached, promptsRemaining: promptsRemainingAfter });
      return;
    }

    const allowed = await tryReserveDailySpend();
    if (!allowed) {
      res.status(503).json({ error: "daily_limit_reached" });
      return;
    }

    try {
      const suggestion = await suggestConditions(sciName);
      if (!suggestion) {
        res.status(502).json({ error: "upstream_parse_error" });
        return;
      }
      await setCachedConditions(sciName, suggestion);
      await incrementSagePromptCount(deviceId);
      res.status(200).json({ suggestion, promptsRemaining: promptsRemainingAfter });
    } catch (err) {
      console.error("sageAutoFillConditions: Anthropic call failed", err);
      res.status(502).json({ error: "upstream_error" });
    }
  }
);
