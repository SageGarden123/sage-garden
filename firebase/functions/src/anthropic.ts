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

// These enums must stay in sync with the option lists in the Android app (MainActivity.kt:
// sunOptions, waterOptions, soilOptions, frostOptions, nativeOptions, pollinatorOptions) — a
// suggestion outside these exact strings can't be selected in the app's closed dropdowns.
const SUN_OPTIONS = ["Full", "Full-Partial", "Partial", "Partial-Shade", "Shade", "Unknown"] as const;
const WATER_OPTIONS = ["Low", "Moderate", "High", "Unknown"] as const;
const SOIL_OPTIONS = ["Sandy", "Loamy", "Clay", "Silty", "Peaty", "Chalky", "Rocky/Stony", "Potting Mix", "Other", "Unknown"] as const;
const SOIL_PH_OPTIONS = ["Acidic", "Neutral", "Alkaline", "Unknown"] as const;
const FROST_OPTIONS = ["Hardy", "Half-hardy", "Tender", "Tender (indoor only)", "Unknown"] as const;
const NATIVE_OPTIONS = ["Native (Aus)", "Exotic"] as const;
const POLLINATOR_OPTIONS = ["Yes - bees", "Yes - butterflies", "Yes - bees & butterflies", "Yes - birds", "No"] as const;

const ConditionsSuggestionSchema = z.object({
  sun: z.enum(SUN_OPTIONS).nullable(),
  water: z.enum(WATER_OPTIONS).nullable(),
  soil: z.enum(SOIL_OPTIONS).nullable(),
  soilPh: z.enum(SOIL_PH_OPTIONS).nullable(),
  frost: z.enum(FROST_OPTIONS).nullable(),
  native: z.enum(NATIVE_OPTIONS).nullable(),
  pollinator: z.enum(POLLINATOR_OPTIONS).nullable(),
});

export type ConditionsSuggestion = z.infer<typeof ConditionsSuggestionSchema>;

// Each field's value must be copied verbatim from its option list, not an index into it — spelled
// out explicitly below because a `["a","b"][number]`-style TS shape hint (the previous phrasing)
// was misread by the model as "put a number here", producing responses like {"sun": 0, "water": 1, ...}
// that failed schema validation for every field. See suggestConditions' error logging for how this
// was diagnosed if it recurs.
const CONDITIONS_SYSTEM_PROMPT =
  "You suggest typical home-garden growing conditions for a given plant species, for an Australian gardener. " +
  "Use null for any value you are not reasonably confident about rather than guessing wildly. " +
  "'native' means native to Australia; use \"Exotic\" for anything not native to Australia, including plants native elsewhere. " +
  "These are general starting-point defaults a home gardener can adjust, not scientific claims. " +
  "Respond with ONLY a single JSON object, no markdown code fences, no extra text. " +
  "Each field's value must be COPIED EXACTLY as one of the quoted strings listed for it below (never a number or index) — or null:\n" +
  `"sun": one of ${JSON.stringify(SUN_OPTIONS)}, or null\n` +
  `"water": one of ${JSON.stringify(WATER_OPTIONS)}, or null\n` +
  `"soil": one of ${JSON.stringify(SOIL_OPTIONS)}, or null\n` +
  `"soilPh": one of ${JSON.stringify(SOIL_PH_OPTIONS)}, or null\n` +
  `"frost": one of ${JSON.stringify(FROST_OPTIONS)}, or null\n` +
  `"native": one of ${JSON.stringify(NATIVE_OPTIONS)}, or null\n` +
  `"pollinator": one of ${JSON.stringify(POLLINATOR_OPTIONS)}, or null\n` +
  'Example shape: {"sun": "Partial", "water": "Moderate", "soil": "Loamy", "soilPh": "Neutral", "frost": "Hardy", "native": "Exotic", "pollinator": "Yes - bees"}';

export async function suggestConditions(sciName: string): Promise<ConditionsSuggestion | null> {
  const response = await getClient().messages.create({
    model: ANTHROPIC_MODEL,
    max_tokens: 512,
    system: CONDITIONS_SYSTEM_PROMPT,
    messages: [
      {
        role: "user",
        content: `Scientific name: "${sciName}". Suggest the optimal sun, water, soil, soil pH, and frost conditions, whether it's native to Australia or exotic, and whether it's pollinator-friendly, for this plant in a home garden setting.`,
      },
    ],
  });

  const textBlock = response.content.find(
    (block): block is Anthropic.TextBlock => block.type === "text"
  );
  if (!textBlock) return null;

  const raw = textBlock.text.trim().replace(/^```(?:json)?\s*/i, "").replace(/```\s*$/i, "");

  let parsedJson: unknown;
  try {
    parsedJson = JSON.parse(raw);
  } catch (err) {
    console.error("suggestConditions: model output wasn't valid JSON", { sciName, raw, err });
    return null;
  }

  const result = ConditionsSuggestionSchema.safeParse(parsedJson);
  if (!result.success) {
    console.error("suggestConditions: model output failed schema validation", {
      sciName, raw, issues: result.error.issues,
    });
    return null;
  }
  return result.data;
}
