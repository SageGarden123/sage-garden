import Anthropic from "@anthropic-ai/sdk";
import { z } from "zod";
import { ANTHROPIC_MODEL, SAGE_SYSTEM_PROMPT } from "./config";

let client: Anthropic | null = null;

/** Lazily constructed so it only reads ANTHROPIC_API_KEY once the function invocation has the secret injected — never at module load time. */
function getClient(): Anthropic {
  if (!client) {
    client = new Anthropic();
  }
  return client;
}

export async function askSage(message: string): Promise<string> {
  const response = await getClient().messages.create({
    model: ANTHROPIC_MODEL,
    max_tokens: 512,
    system: SAGE_SYSTEM_PROMPT,
    messages: [{ role: "user", content: message }],
  });

  const textBlock = response.content.find(
    (block): block is Anthropic.TextBlock => block.type === "text"
  );
  return textBlock?.text?.trim() || "Sorry, I couldn't come up with an answer to that.";
}

const FrequencySuggestionSchema = z.object({
  wateringFrequencyDays: z.number().int().positive().nullable(),
  fertiliseFrequencyDays: z.number().int().positive().nullable(),
  pruneFrequencyDays: z.number().int().positive().nullable(),
  feedFrequencyDays: z.number().int().positive().nullable(),
});

export type FrequencySuggestion = z.infer<typeof FrequencySuggestionSchema>;

const FREQUENCY_SYSTEM_PROMPT =
  "You suggest typical home-garden care frequencies (in days) for a given plant species. " +
  "Use null for any value you are not reasonably confident about rather than guessing wildly. " +
  "These are general starting-point defaults a home gardener can adjust, not scientific claims. " +
  "Respond with ONLY a single JSON object, no markdown code fences, no extra text, matching exactly this shape: " +
  '{"wateringFrequencyDays": number|null, "fertiliseFrequencyDays": number|null, "pruneFrequencyDays": number|null, "feedFrequencyDays": number|null}';

export async function suggestFrequencies(sciName: string): Promise<FrequencySuggestion | null> {
  const response = await getClient().messages.create({
    model: ANTHROPIC_MODEL,
    max_tokens: 512,
    system: FREQUENCY_SYSTEM_PROMPT,
    messages: [
      {
        role: "user",
        content: `Scientific name: "${sciName}". Suggest typical watering, fertilising, pruning, and feeding frequency in days for this plant in a home garden setting.`,
      },
    ],
  });

  const textBlock = response.content.find(
    (block): block is Anthropic.TextBlock => block.type === "text"
  );
  if (!textBlock) return null;

  // Models occasionally wrap JSON in ```json fences despite instructions not to — strip if present.
  const raw = textBlock.text.trim().replace(/^```(?:json)?\s*/i, "").replace(/```\s*$/i, "");

  let parsedJson: unknown;
  try {
    parsedJson = JSON.parse(raw);
  } catch {
    return null;
  }

  const result = FrequencySuggestionSchema.safeParse(parsedJson);
  return result.success ? result.data : null;
}
