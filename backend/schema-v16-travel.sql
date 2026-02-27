-- Voyage Travel State (航行系统状态表)

CREATE TABLE IF NOT EXISTS game_player_travel_state (
  player_id INTEGER PRIMARY KEY,
  from_port_id TEXT NOT NULL,
  to_port_id TEXT NOT NULL,
  depart_at INTEGER NOT NULL,
  arrive_at INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  travel_cost INTEGER NOT NULL DEFAULT 0,
  encounter_event TEXT,
  encounter_money_delta INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_game_travel_status_arrive
  ON game_player_travel_state (status, arrive_at);

