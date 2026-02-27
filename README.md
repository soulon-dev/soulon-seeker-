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

- `app/google-services.json` is included for Firebase configuration. Treat it as configuration data and apply restrictions in the Firebase console as needed.
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
