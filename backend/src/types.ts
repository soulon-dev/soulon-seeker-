
export interface Env {
  // KV 命名空间
  CHALLENGES?: KVNamespace;
  SESSIONS?: KVNamespace;
  KV?: KVNamespace;
  
  // D1 数据库
  DB?: D1Database;
  
  // R2 存储
  R2?: R2Bucket;
  SHIP_METADATA?: R2Bucket;
  
  // 环境变量
  SOLANA_CLUSTER: string;
  SOLANA_RPC_URL?: string;
  GENESIS_TOKEN_COLLECTION: string;
  ATTESTATION_VALIDITY_HOURS: string;
  JWT_SECRET?: string;
  ENVIRONMENT: string;
  
  // Secrets (新增)
  QWEN_API_KEY?: string; // 从 Secrets 读取
  JUPITER_API_KEY?: string;
  ENCRYPTION_KEY?: string;
  
  // Cloudflare Access 配置
  CF_ACCESS_TEAM_NAME?: string;
  CF_ACCESS_AUD?: string;

  // CORS allowlist（逗号分隔 Origin 列表）
  CORS_ALLOWED_ORIGINS?: string;
  ADMIN_CORS_ALLOWED_ORIGINS?: string;
}
