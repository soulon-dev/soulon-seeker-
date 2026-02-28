# Deployment Runbook (Test / Staging / Production)

This repository is designed to keep the current production system safe while introducing a clean, isolated branch-and-environment workflow.

## Branches

- `develop`: test environment
- `release/*`: staging environment
- `master`: production environment

## GitHub Repository Settings (Required)

### 1) Default branch

Set default branch to `master`.

### 2) Branch protection

Enable protection rules for `master` and `develop`:

- Require pull request before merging
- Require approvals
- Require status checks:
  - `CI / backend_test`
  - `CI / android_build`
- Require branches to be up to date before merging
- Restrict who can push to matching branches (ops only for `master`)

### 3) CODEOWNERS

CODEOWNERS is included in `.github/CODEOWNERS`. Configure teams and ensure:

- Ops reviews are required for production deployment workflows and `backend/wrangler.toml`.

### 4) Environments & secrets

Create GitHub Environments:

- `test`
- `staging`
- `production` (set required reviewers: ops/release managers)

Add environment secrets:

- `CLOUDFLARE_API_TOKEN_TEST`
- `CLOUDFLARE_API_TOKEN_STAGING`
- `CLOUDFLARE_API_TOKEN_PRODUCTION`

Tokens must be scoped so test/staging tokens cannot deploy production resources.

## Cloudflare Setup (Required)

### 1) Create isolated resources

Create separate resources for each environment:

- D1: `soulon-test`, `soulon-staging`, `soulon-production`
- KV: separate namespaces for each environment
- R2: separate buckets if used

### 2) Configure `backend/wrangler.toml`

Update resource IDs in `env.test`, `env.staging`, `env.production` to point to the corresponding isolated resources.

Safety rule:

- Do not bind production routes to the current live domain until cutover is explicitly approved.

## Domains / Routes (Required)

Recommended routing:

- test: `api-test.<domain>/*`, `privacy-test.<domain>/*`
- staging: `api-staging.<domain>/*`, `privacy-staging.<domain>/*`
- production: bind only after cutover approval

## Deployments

- Test: push to `develop` → auto deploy to `test`
- Staging: push to `release/*` → auto deploy to `staging`
- Production: push to `master` → deploy to `production` (blocked by GitHub Environment reviewers)

Rollback:

- Use the “Rollback (Production)” workflow and provide a git ref (tag or commit SHA).
