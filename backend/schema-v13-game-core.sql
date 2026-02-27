-- ============================================
-- Schema V13: Great Voyage Game Core (New Schema)
-- Aligns D1 tables with backend/src/routes/game.ts
-- ============================================

PRAGMA foreign_keys = ON;

-- Core tables (drop incompatible legacy tables if present)
DROP TABLE IF EXISTS game_market;
DROP TABLE IF EXISTS game_inventory;
DROP TABLE IF EXISTS game_goods;
DROP TABLE IF EXISTS game_ports;
DROP TABLE IF EXISTS game_players;

-- Players (note: q/r are added by later migrations; game.ts has insert fallback)
CREATE TABLE IF NOT EXISTS game_players (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  money INTEGER NOT NULL DEFAULT 1000,
  current_port_id TEXT NOT NULL DEFAULT 'port_amsterdam',
  ship_level INTEGER NOT NULL DEFAULT 1,
  cargo_capacity INTEGER NOT NULL DEFAULT 100,
  created_at INTEGER NOT NULL DEFAULT (unixepoch()),
  updated_at INTEGER NOT NULL DEFAULT (unixepoch()),
  FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Ports
CREATE TABLE IF NOT EXISTS game_ports (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  coordinates TEXT NOT NULL DEFAULT '0,0',
  unlock_level INTEGER NOT NULL DEFAULT 1
);

-- Goods
CREATE TABLE IF NOT EXISTS game_goods (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  base_price INTEGER NOT NULL,
  volatility REAL NOT NULL DEFAULT 0.1
);

-- Market (id used by update statements; unique per port+good)
CREATE TABLE IF NOT EXISTS game_market (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  port_id TEXT NOT NULL,
  good_id TEXT NOT NULL,
  price INTEGER NOT NULL,
  stock INTEGER NOT NULL,
  updated_at INTEGER NOT NULL DEFAULT (unixepoch()),
  UNIQUE (port_id, good_id),
  FOREIGN KEY (port_id) REFERENCES game_ports(id),
  FOREIGN KEY (good_id) REFERENCES game_goods(id)
);

-- Inventory (unique per player+good for ON CONFLICT upserts)
CREATE TABLE IF NOT EXISTS game_inventory (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  player_id INTEGER NOT NULL,
  good_id TEXT NOT NULL,
  quantity INTEGER NOT NULL DEFAULT 0,
  avg_cost REAL NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL DEFAULT (unixepoch()),
  updated_at INTEGER NOT NULL DEFAULT (unixepoch()),
  UNIQUE (player_id, good_id),
  FOREIGN KEY (player_id) REFERENCES game_players(id),
  FOREIGN KEY (good_id) REFERENCES game_goods(id)
);

CREATE INDEX IF NOT EXISTS idx_game_players_user ON game_players(user_id);
CREATE INDEX IF NOT EXISTS idx_game_market_port ON game_market(port_id);
CREATE INDEX IF NOT EXISTS idx_game_inventory_player ON game_inventory(player_id);

-- Seed minimal ports used by current game content
INSERT OR IGNORE INTO game_ports (id, name, description, coordinates, unlock_level) VALUES
('port_amsterdam', 'Neo Amsterdam', 'A major trade hub in the Northern Sector.', '0,0', 1),
('port_shanghai', 'Cyber Shanghai', 'A neon-lit metropolis controlled by the Syndicate.', '10,5', 1);

-- Seed minimal goods (market auto-initializes per port if empty)
INSERT OR IGNORE INTO game_goods (id, name, description, base_price, volatility) VALUES
('fuel_cell', 'Fuel Cell', 'Standard-grade fuel for jump drives.', 10, 0.2),
('data_shard', 'Data Shard', 'Encrypted fragments traded by information brokers.', 50, 0.35),
('signal_fragment', 'Signal Fragment', 'Anomalous shard emitting a faint genesis signal.', 200, 0.5);

