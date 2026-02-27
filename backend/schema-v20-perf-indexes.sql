CREATE INDEX IF NOT EXISTS idx_npc_interactions_player_npc_created
ON game_npc_interactions(player_id, npc_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_lore_entries_drop
ON game_lore_entries(season_id, source_type, source_target);

CREATE INDEX IF NOT EXISTS idx_player_lore_player_lore
ON game_player_lore(player_id, lore_id);

CREATE INDEX IF NOT EXISTS idx_game_market_port_updated
ON game_market(port_id, updated_at);

