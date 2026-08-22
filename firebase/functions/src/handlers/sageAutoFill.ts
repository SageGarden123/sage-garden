import { onRequest } from "firebase-functions/v2/https";
import { anthropicApiKey } from "../config";
import { resolveEntitlement, incrementSagePromptCount } from "../entitlement";
import { getCachedFrequencies, setCachedFrequencies } from "../cache";
import { tryReserveDailySpend } from "../spend";
import { suggestFrequencies } from "../anthropic";
import { verifyAppCheck } from "../verifyAppCheck";

export const sageAutoFill = onRequest(
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
      res.status(403).json({ error: "free_limit_reached" });
      return;
    }

    const promptsRemainingAfter = entitlement.isPro
      ? null
      : Math.max(0, entitlement.sagePromptLimit - entitlement.sagePromptCount - 1);

    const cached = await getCachedFrequencies(sciName);
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
      const suggestion = await suggestFrequencies(sciName);
      if (!suggestion) {
        res.status(502).json({ error: "upstream_parse_error" });
        return;
      }
      await setCachedFrequencies(sciName, suggestion);
      await incrementSagePromptCount(deviceId);
      res.status(200).json({ suggestion, promptsRemaining: promptsRemainingAfter });
    } catch (err) {
      console.error("sageAutoFill: Anthropic call failed", err);
      res.status(502).json({ error: "upstream_error" });
    }
  }
);
