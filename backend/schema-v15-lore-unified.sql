-- ============================================
-- Schema V15: Unified Lore Schema (Compatible with routes/game.ts)
-- Recreates lore tables to include required columns:
-- category, source_type, source_target, drop_chance
-- ============================================

PRAGMA foreign_keys = ON;

DROP TABLE IF EXISTS game_player_lore;
DROP TABLE IF EXISTS game_lore_entries;

CREATE TABLE IF NOT EXISTS game_lore_entries (
  id TEXT PRIMARY KEY,
  season_id TEXT NOT NULL,
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  unlock_threshold INTEGER DEFAULT 0,
  category TEXT NOT NULL DEFAULT 'MAIN',
  source_type TEXT NOT NULL DEFAULT 'CONTRIBUTION',
  source_target TEXT,
  drop_chance REAL DEFAULT 0.1,
  created_at INTEGER,
  updated_at INTEGER
);

CREATE TABLE IF NOT EXISTS game_player_lore (
  player_id INTEGER,
  lore_id TEXT,
  unlocked_at INTEGER,
  PRIMARY KEY (player_id, lore_id),
  FOREIGN KEY (player_id) REFERENCES game_players(id),
  FOREIGN KEY (lore_id) REFERENCES game_lore_entries(id)
);

CREATE INDEX IF NOT EXISTS idx_game_lore_season ON game_lore_entries(season_id);
CREATE INDEX IF NOT EXISTS idx_game_lore_category ON game_lore_entries(category);

-- Seed minimal lore for current season (uses placeholders; game.ts can add more procedurally)
INSERT OR IGNORE INTO game_lore_entries (id, season_id, title, content, unlock_threshold, category, source_type, created_at, updated_at)
VALUES
('lore_genesis_01', 'season_genesis', 'Genesis Signal', 'Fragments of an unknown signal pulse through the trade lanes.', 0, 'MAIN', 'CONTRIBUTION', strftime('%s','now'), strftime('%s','now')),
('lore_port_ams_01', 'season_genesis', 'Neo Amsterdam Manifest', 'A ledger of forbidden cargo codes, stamped with old-world insignia.', 10, 'PORT', 'PORT', strftime('%s','now'), strftime('%s','now'));
