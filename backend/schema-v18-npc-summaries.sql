CREATE TABLE IF NOT EXISTS game_npc_memory_summaries (
  player_id INTEGER NOT NULL,
  npc_id TEXT NOT NULL,
  summary TEXT NOT NULL,
  interaction_count INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (player_id, npc_id)
);

CREATE INDEX IF NOT EXISTS idx_npc_memory_summaries_player ON game_npc_memory_summaries(player_id);

