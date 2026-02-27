-- ============================================
-- Schema V14: Open World Tables (Safe / Idempotent)
-- Skips ALTER TABLE for q/r to avoid duplicate-column errors
-- ============================================

PRAGMA foreign_keys = ON;

-- Game Map Chunks (Stores dynamic changes to the procedurally generated world)
CREATE TABLE IF NOT EXISTS game_map_chunks (
  id TEXT PRIMARY KEY, -- "q_r" coordinate string
  q INTEGER NOT NULL,
  r INTEGER NOT NULL,
  type TEXT,
  has_beacon BOOLEAN DEFAULT FALSE,
  beacon_message TEXT,
  beacon_owner_id INTEGER,
  created_at INTEGER,
  updated_at INTEGER
);

-- Player Exploration Progress
CREATE TABLE IF NOT EXISTS game_player_exploration (
  player_id INTEGER,
  q INTEGER NOT NULL,
  r INTEGER NOT NULL,
  visited_at INTEGER,
  PRIMARY KEY (player_id, q, r),
  FOREIGN KEY (player_id) REFERENCES game_players(id)
);

-- Player Artifacts (NFTs)
CREATE TABLE IF NOT EXISTS game_player_artifacts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  player_id INTEGER,
  name TEXT NOT NULL,
  description TEXT,
  rarity TEXT DEFAULT 'COMMON',
  image_url TEXT,
  mint_status TEXT DEFAULT 'PENDING',
  mint_address TEXT,
  attributes TEXT,
  created_at INTEGER,
  FOREIGN KEY (player_id) REFERENCES game_players(id)
);

CREATE INDEX IF NOT EXISTS idx_game_map_chunks_qr ON game_map_chunks(q, r);
CREATE INDEX IF NOT EXISTS idx_game_player_exploration_player ON game_player_exploration(player_id);

