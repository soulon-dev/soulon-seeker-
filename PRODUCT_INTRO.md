# Soulon — Product Introduction (English)

Soulon is a mobile-first “memory layer” that lets people capture personal moments, encrypt them on-device, and store them with durable, verifiable persistence—while still making those memories useful through AI-powered organization and retrieval.

It is built for real-world mobile constraints: intermittent networks, background operation, and a user experience that stays smooth even when storage and verification happen asynchronously.

---

## Why Soulon

People write fragments of their lives across notes apps, chat threads, and screenshots. These memories are:

- Easy to lose (device migration, account lockouts, platform churn)
- Hard to trust (edits, deletion, opaque storage)
- Impossible to reuse (no structure, no semantic retrieval)

Soulon turns personal memory into a secure, portable, and intelligence-ready asset:

- **You own the identity** (wallet-based)
- **You control the data** (client-side encryption)
- **You can prove integrity** (content hashing + verifiable storage references)
- **You can actually use it** (search, summarization, persona understanding)

---

## What It Does

### 1) Capture

Create memories from:

- Onboarding reflections (structured prompts)
- Ongoing journaling and conversation-style entries
- Contextual events and notes

### 2) Protect

Before anything leaves the device, Soulon:

- Encrypts content on-device
- Derives encryption context from the user’s wallet identity
- Stores keys using Android’s secure key infrastructure

### 3) Persist

Soulon stores encrypted memories with durable persistence using decentralized storage primitives (Irys/Arweave). Each stored memory is associated with:

- A stable **memory ID**
- A **content hash** for integrity verification
- Metadata tags that make later retrieval and organization possible

### 4) Understand

Soulon can generate higher-level insights from your memory stream:

- Persona signals and long-term preferences
- Thematic clustering across time
- Fast semantic retrieval for “find what I meant” moments

### 5) Retrieve

Search and revisit your memories with mobile-first UX:

- Background synchronization
- Indexed retrieval
- Clear provenance (where the memory lives, and how it can be verified)

---

## Key Differentiators

### Mobile-First Security and Ownership

- **Wallet-based identity** using Solana Mobile Wallet Adapter
- **Client-side encryption** before upload
- **Integrity by design** using content hashing and consistent identifiers

### Durable-by-Default Storage

- Uses decentralized storage for long-term persistence
- Designed so stored data references are stable and verifiable

### “Works in the Background”

- Upload progress tracking across multiple items
- Persisted states for retry/resume
- UX built around asynchronous completion instead of blocking flows

### AI That Builds on Real Data

- AI features are grounded in your encrypted memory pipeline
- Organized, searchable, and extensible memory metadata

---

## Typical User Flow (30 Seconds)

1) **Connect wallet** → establishes identity and authorization
2) **Write a memory** → content is encrypted locally
3) **Upload runs in background** → progress is visible, retries are supported
4) **Search later** → retrieve relevant moments semantically, not just by keywords

---

## Architecture (High Level)

### Android App

- User experience, wallet connection, and on-device encryption
- Upload orchestration with progress + retry
- Local indexing to keep interactions fast

### Cloudflare Workers Backend

- Lightweight API surface for mobile clients
- Authentication/session support
- Storage-related routing and supporting services

### Decentralized Storage Layer

- Persistent storage references for encrypted memory objects
- Metadata tagging for discovery and indexing

---

## Demo Script (Judge-Friendly)

1) Install the release APK (from the submission).
2) Connect a Solana wallet.
3) Complete onboarding to generate several memories.
4) Observe background upload progress and completion.
5) Trigger a search/query and show retrieval of a relevant memory.

Optional health check:

- `GET https://api.soulon.top/health`

---

## Build & Run (Developer Quick Start)

### Android

```bash
cp local.properties.example local.properties
./gradlew :app:assembleRelease
```

### Backend

```bash
cd backend
npm ci
npm test
```

---

## Product Vision

Soulon’s long-term direction is to become a personal, secure “memory OS”:

- A durable private archive that survives platforms and devices
- A trustworthy foundation for personalized AI
- A mobile-native experience where ownership and privacy are not optional add-ons, but core primitives

