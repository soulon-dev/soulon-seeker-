# Memory AI Backend - Cloudflare Workers

Memory AI 后端服务，基于 Cloudflare Workers 部署，提供 TEEPIN 验证、用户认证、Sovereign Score 和质押状态查询等 API。

## 功能

- **Attestation API**: TEEPIN 硬件身份验证
- **Auth API**: 钱包签名登录 (SIWS)
- **Sovereign API**: Sovereign Score 查询
- **Staking API**: Guardian 质押状态查询

## 快速开始

### 1. 安装依赖

```bash
cd backend
npm install
```

### 2. 本地开发

```bash
npm run dev
```

访问 http://localhost:8787 查看服务状态。

### 3. 配置环境变量

创建 `.dev.vars` 文件（本地开发）:

```env
SOLANA_RPC_URL=https://api.mainnet-beta.solana.com
JWT_SECRET=your-jwt-secret-here
```

生产环境使用 Cloudflare 密钥:

```bash
wrangler secret put SOLANA_RPC_URL
wrangler secret put JWT_SECRET
```

### 4. 部署到 Cloudflare

```bash
# 首次部署
wrangler deploy

# 部署到生产环境
wrangler deploy --env production
```

## API 端点

### 健康检查

```bash
GET /health
```

### Attestation API

```bash
# 获取 Challenge
POST /api/v1/attestation/challenge
Body: { "wallet_address": "xxx" }

# 验证 Attestation
POST /api/v1/attestation/verify
Body: {
  "wallet_address": "xxx",
  "signature": "base58...",
  "public_key": "base58...",
  "genesis_token_id": "xxx"
}
```

### Auth API

```bash
# 获取登录 Challenge
POST /api/v1/auth/challenge
Body: { "wallet_address": "xxx" }

# 登录
POST /api/v1/auth/login
Body: {
  "wallet_address": "xxx",
  "signature": "base58...",
  "public_key": "base58..."
}

# 验证会话
POST /api/v1/auth/verify
Body: { "session_token": "sess_xxx" }
```

### Sovereign API

```bash
# 获取 Sovereign Score
GET /api/v1/sovereign/score?wallet=xxx

# 获取等级列表
GET /api/v1/sovereign/levels
```

### Staking API

```bash
# 获取质押状态
GET /api/v1/staking/status?wallet=xxx

# 获取认证 Guardian 列表
GET /api/v1/staking/guardians

# 计算加成
GET /api/v1/staking/bonus?amount=xxx&certified=true
```

## 配置项

在 `wrangler.toml` 中配置:

| 配置项 | 说明 | 默认值 |
|-------|------|--------|
| SOLANA_CLUSTER | Solana 集群 | mainnet-beta |
| GENESIS_TOKEN_COLLECTION | Genesis Token Collection 地址 | - |
| ATTESTATION_VALIDITY_HOURS | Attestation 有效期（小时） | 24 |

## 生产环境优化

### 启用 KV 存储

1. 创建 KV 命名空间:

```bash
wrangler kv:namespace create "CHALLENGES"
wrangler kv:namespace create "SESSIONS"
```

2. 在 `wrangler.toml` 中取消注释 KV 配置:

```toml
[[kv_namespaces]]
binding = "CHALLENGES"
id = "your-challenges-kv-id"

[[kv_namespaces]]
binding = "SESSIONS"
id = "your-sessions-kv-id"
```

### 监控和日志

```bash
# 查看实时日志
wrangler tail

# 查看生产环境日志
wrangler tail --env production
```

## 许可证

MIT
