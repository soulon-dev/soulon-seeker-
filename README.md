# Soulon — Hackathon Submission

This repository contains the Android app and the Cloudflare Workers backend for the Soulon hackathon submission.

This repo is for the hackathon submission only. The production codebase and deployment workflow live in https://github.com/soulon-dev/Official.

## Contents

- `app/`: Android application
- `backend/`: Cloudflare Workers backend

## Quick Demo

1) Install the release APK (provided in the submission form).
2) Open the app and connect a Solana wallet.
3) Complete onboarding and trigger an upload.

## Build

### Android

```bash
cp local.properties.example local.properties
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

### Backend

```bash
cd backend
npm ci
npm test
```

## Notes

- Firebase config is optional and not committed. Use `app/google-services.json.example` for local setup if needed.
