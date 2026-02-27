# Soulon — Hackathon Submission

This repository contains the Android app and the Cloudflare Workers backend for the Soulon hackathon submission.

## Highlights

- Mobile-first on-chain / permanent storage flow for encrypted memories (Irys/Arweave, with a server-backed fallback).
- Solana Mobile Wallet Adapter integration for wallet-based authorization.
- Background upload + progress tracking, designed for real mobile networks.
- Server-side APIs (Cloudflare Workers) for auth/session, storage fallback, and configuration.

## What To Review

- Android app source: `app/`
- Backend source: `backend/`
- Entry points:
  - Android: `app/src/main/kotlin/com/soulon/app/MainActivity.kt`
  - Backend Worker: `backend/src/index.ts`

## Demo (Judge-Friendly)

Suggested demo flow:

1) Install the release APK (provided in the submission form).
2) Launch the app and connect a Solana wallet.
3) Complete onboarding to generate a batch of memories and start uploads.
4) Verify upload progress and retry behavior (transient failures can be retried).
5) Verify the backend is reachable:
   - `GET https://api.soulon.top/health`

## Mobile-Specific Features

- Wallet-based sign-in / authorization via Solana Mobile Wallet Adapter.
- Client-side encryption before upload (keys backed by Android KeyStore).
- Resumable uploads with persisted progress state and explicit retry UX.
- Release build hardening (R8/minification) with keep rules for JSON parsing.

## Known Limitations

- Backend “paid upload to Irys” proxy is not enabled in this deployment; the app falls back to server storage where applicable.
- Firebase configuration is optional and not committed to this repository.

## Build & Run

### Prerequisites

- Android Studio (or JDK 17 + Android SDK)
- Node.js (for the backend)

### Android

Setup:

```bash
cp local.properties.example local.properties
```

Build:

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Firebase (optional):

```bash
cp app/google-services.json.example app/google-services.json
```

### Backend (Cloudflare Workers)

Local test:

```bash
cd backend
npm ci
npm test
```

Local dev:

```bash
cd backend
npm ci
npm run dev
```

Deploy:

```bash
cd backend
npm run deploy -- --env production
```

## Security Notes

- No production secrets are committed. Cloudflare secrets are configured via `wrangler secret put ...`.
- `app/google-services.json` is intentionally not committed (use the example file for local setup).
