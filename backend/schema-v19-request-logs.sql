CREATE TABLE IF NOT EXISTS admin_request_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  request_id TEXT NOT NULL,
  path TEXT NOT NULL,
  method TEXT NOT NULL,
  status INTEGER NOT NULL,
  duration_ms INTEGER NOT NULL,
  error_type TEXT,
  user_identity TEXT,
  ip TEXT,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_admin_request_logs_created ON admin_request_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_admin_request_logs_status ON admin_request_logs(status);

