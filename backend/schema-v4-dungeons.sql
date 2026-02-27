-- Game Dungeons Table
CREATE TABLE IF NOT EXISTS game_dungeons (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    difficulty_level INTEGER DEFAULT 1,
    max_depth INTEGER DEFAULT 5,
    entry_cost INTEGER DEFAULT 100,
    created_at INTEGER,
    updated_at INTEGER
);

-- Game Player Dungeon State (Temporary session state)
CREATE TABLE IF NOT EXISTS game_player_dungeon_state (
    player_id INTEGER PRIMARY KEY,
    dungeon_id TEXT NOT NULL,
    current_depth INTEGER DEFAULT 1,
    current_room_description TEXT,
    sanity INTEGER DEFAULT 100,
    health INTEGER DEFAULT 100,
    inventory TEXT DEFAULT '[]', -- JSON string of temporary loot
    status TEXT DEFAULT 'ACTIVE', -- ACTIVE, COMPLETED, FAILED
    last_action TEXT,
    created_at INTEGER,
    updated_at INTEGER,
    FOREIGN KEY (player_id) REFERENCES game_players(id),
    FOREIGN KEY (dungeon_id) REFERENCES game_dungeons(id)
);

-- Initial Dungeons
INSERT OR IGNORE INTO game_dungeons (id, name, description, difficulty_level, max_depth, entry_cost, created_at, updated_at)
VALUES 
('dungeon_memory_breach_01', 'Memory Breach: Alpha', 'A fragmented data sector leaking corrupted memories.', 1, 5, 100, strftime('%s','now'), strftime('%s','now')),
('dungeon_deep_web_ruins', 'Deep Web Ruins', 'Ancient servers from the pre-collapse era.', 2, 8, 250, strftime('%s','now'), strftime('%s','now'));
