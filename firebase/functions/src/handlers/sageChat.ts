import { onRequest } from "firebase-functions/v2/https";
import { anthropicApiKey } from "../config";
import { resolveEntitlement, incrementSagePromptCount } from "../entitlement";
import { getCachedChatResponse, setCachedChatResponse } from "../cache";
import { tryReserveDailySpend } from "../spend";
import { askSage } from "../anthropic";

export const sageChat = onRequest(
  { secrets: [anthropicApiKey], cors: false },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ error: "method_not_allowed" });
      return;
    }

    const deviceId = typeof req.body?.deviceId === "string" ? req.body.deviceId : null;
    const message = typeof req.body?.message === "string" ? req.body.message.trim() : "";
    if (!deviceId || !message) {
      res.status(400).json({ error: "invalid_request" });
      return;
    }
    if (message.length > 2000) {
      res.status(400).json({ error: "message_too_long" });
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

    const cached = await getCachedChatResponse(message);
    if (cached) {
      await incrementSagePromptCount(deviceId);
      res.status(200).json({ reply: cached, promptsRemaining: promptsRemainingAfter });
      return;
    }

    const allowed = await tryReserveDailySpend();
    if (!allowed) {
      res.status(503).json({ error: "daily_limit_reached" });
      return;
    }

    try {
      const reply = await askSage(message);
      await setCachedChatResponse(message, reply);
      await incrementSagePromptCount(deviceId);
      res.status(200).json({ reply, promptsRemaining: promptsRemainingAfter });
    } catch (err) {
      console.error("sageChat: Anthropic call failed", err);
      res.status(502).json({ error: "upstream_error" });
    }
  }
);
