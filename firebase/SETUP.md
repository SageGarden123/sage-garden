# Sage backend — setup

All the Cloud Functions code is already written under `functions/src/`. You don't need to run `firebase init` — that would just scaffold what's already here. What's left is linking this folder to your real Firebase project and deploying.

## 1. Install dependencies and build (verifies the code compiles)

```bash
cd firebase/functions
npm install
npm run build
```

Fix any TypeScript errors `npm run build` reports before continuing — I wrote this against the current `@anthropic-ai/sdk` docs but couldn't run a live build in my environment (no Node on PATH here), so this is the first real compile check.

## 2. Link to your Firebase project

You've already: created the Firebase project, enabled Blaze billing, enabled Firestore (Native mode), and run `npm install -g firebase-tools` + `firebase login`.

From the `firebase/` directory (one level up from `functions/`):

```bash
firebase use --add
```

Pick your project when prompted, give it an alias (e.g. `default`).

## 3. Set your Anthropic API key as a Functions secret

```bash
firebase functions:secrets:set ANTHROPIC_API_KEY
```

Paste your key when prompted. This is stored by Google Secret Manager, injected only into the functions that declare it (`sageChat`, `sageAutoFill`) — it's never written to any file in this repo.

## 4. Deploy

```bash
firebase deploy --only functions,firestore:rules,firestore:indexes
```

The deploy output will print each function's URL, e.g.:
```
https://us-central1-your-project-id.cloudfunctions.net/sageChat
https://us-central1-your-project-id.cloudfunctions.net/sageAutoFill
https://us-central1-your-project-id.cloudfunctions.net/redeemPromoCode
https://us-central1-your-project-id.cloudfunctions.net/syncEntitlement
```

Note the base (everything before the function name) — that's `SAGE_API_BASE_URL` for the Android app's `local.properties` (added when Stage 3 of the plan wires up `SageClient.kt`).

## 5. Smoke-test before touching the Android app

```bash
curl -X POST https://us-central1-your-project-id.cloudfunctions.net/syncEntitlement \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"test-device-1"}'
```

Should return a JSON entitlement snapshot with `"isPro": true, "source": "trial"` (a fresh device starts its 14-day trial on first sync). Then:

```bash
curl -X POST https://us-central1-your-project-id.cloudfunctions.net/sageChat \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"test-device-1","message":"How often should I water a tomato plant?"}'
```

Should return `{"reply": "...", "promptsRemaining": null}` (null because the trial makes this device Pro). Run the exact same request again — check the Cloud Functions logs (`firebase functions:log`) to confirm the second call was a cache hit (no new Anthropic API call logged).

## 6. Create your own permanent promo code

In the Firestore console, under the `promoCodes` collection, create a document with the ID being your code (e.g. `DANFOREVER`) and fields:

```
active: true (boolean)
activeFrom: (leave unset)
activeUntil: (leave unset)
maxRedemptions: (leave unset / null)
redemptionCount: 0 (number)
permanent: true (boolean)
```

Redeeming it (once the app UI exists) sets `promoCode` on your device doc permanently — deactivating this code later only blocks *new* redemptions, never revokes yours.

For a time-windowed code like `SPRINGSALE`, set `activeFrom`/`activeUntil` as Firestore Timestamps and/or `maxRedemptions` as a number.
