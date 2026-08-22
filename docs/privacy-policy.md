---
title: Sage Garden — Privacy Policy
---

# Privacy Policy for Sage Garden

**Last updated: 22 August 2026**

Sage Garden ("the app") is a personal garden-tracking app developed by Daniel Luton. This page explains what data the app collects, why, and who it's shared with.

## Data the app collects

**Garden and plant data.** Plant names, photos, locations on your map, care schedules and history (watering, fertilising, pruning, feeding), and notes you enter. This is stored locally on your device in the app's own database. It leaves your device only if you choose to back it up to Dropbox, or when a photo is sent for AI plant identification or AI care-suggestion features (see below).

**Photos.** Camera or gallery photos you attach to a plant. Stored locally by default; optionally stored in a Dropbox folder you choose, if you connect Dropbox.

**Location.** If you grant location permission, it's used to help place plants on the real-world map and to determine your garden's coordinates for weather-aware watering reminders and frost warnings. If you enter a garden address instead, that address is sent to Google's Places/Geocoding APIs to convert it into coordinates.

**A random device identifier.** The app generates a random ID (not tied to your name, email, or Google account) to track your free trial and Pro entitlement status. This ID, and nothing else personally identifying, is sent to the app's backend (hosted on Google Firebase) each time entitlement status is checked.

**AI assistant conversations and photos.** If you use "Sage" (the in-app AI assistant) or the AI auto-fill/photo-identification features, your typed questions and/or plant photos are sent to third-party AI providers to generate a response (see below).

**Smart-irrigation credentials.** If you connect a Tuya or Rachio irrigation account, the credentials you enter (API token, or client ID/secret) are stored locally on your device only, and used to talk directly to that vendor's own cloud API from your device — they are never sent to Sage Garden's own backend.

## Who your data is shared with

- **Anthropic** (Claude AI) — receives your Sage chat messages and, for auto-fill, plant species names — to generate responses. See [Anthropic's privacy policy](https://www.anthropic.com/legal/privacy).
- **Pl@ntNet** — receives a plant photo when you use AI photo identification, to return a species match. See [Pl@ntNet's privacy policy](https://identify.plantnet.org/data-privacy).
- **Google** (Maps/Places APIs) — receives address search text and/or coordinates for map display and address autocomplete.
- **Dropbox** — only if you connect a Dropbox account, receives the photos/backup files you choose to store there, under your own Dropbox account's terms.
- **Tuya / Rachio** — only if you connect one, your device talks directly to that vendor's cloud using the credentials you provide, to read watering activity.
- **Google Firebase / Google Cloud** — hosts the app's backend (entitlement checks, AI request relaying, promo code redemption). Firebase does not receive your plant data, photos, or location.

The app does not use advertising or analytics/tracking SDKs, and does not sell your data to anyone.

## Data retention and deletion

Your plant data, photos, and settings live on your device and are deleted when you uninstall the app (unless separately backed up to Dropbox, which you control). To request deletion of the anonymous device record held on the app's backend, contact the email below with your device's install ID (found in Help → About).

## Children's privacy

Sage Garden is not directed at children under 13, and does not knowingly collect data from them.

## Changes to this policy

If this policy changes, the "Last updated" date above will change accordingly.

## Contact

Questions about this policy or your data: **lutond@gmail.com**
