# Soulon — Hackathon Submission

This repository contains the Android app and the Cloudflare Workers backend used for the hackathon submission.

## Contents

- `app/`: Android application (APK build target)
- `backend/`: Cloudflare Workers backend (API used by the app)

## Android

### Build

Prerequisites:
- Android Studio (or JDK 17 + Android SDK)

Setup:

```bash
cp local.properties.example local.properties
```

Commands:

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

### Notes

- Firebase config is not committed. Copy `app/google-services.json.example` to `app/google-services.json` if you want to enable Firebase features locally.
- Release builds enable R8/minification. Keep rules are included in `app/proguard-rules.pro` to avoid breaking JSON parsing during uploads.

## Backend (Cloudflare Workers)

### Local test

```bash
cd backend
npm ci
npm test
```

### Local dev

```bash
cd backend
npm ci
npm run dev
```

### Deploy

```bash
cd backend
npm run deploy -- --env production
```

## Submission checklist

- Android release APK built successfully
- GitHub repository contains Android + backend source
- Demo video recorded
- Pitch deck prepared
