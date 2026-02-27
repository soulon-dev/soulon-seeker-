CREATE TABLE IF NOT EXISTS game_npc_interactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id INTEGER NOT NULL,
    npc_id TEXT NOT NULL,
    message TEXT NOT NULL, -- User's message
    response TEXT NOT NULL, -- NPC's response
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_npc_interactions_player_npc ON game_npc_interactions(player_id, npc_id);
