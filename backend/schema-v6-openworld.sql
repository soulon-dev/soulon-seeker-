-- Game Map Chunks (Stores dynamic changes to the procedurally generated world)
CREATE TABLE IF NOT EXISTS game_map_chunks (
    id TEXT PRIMARY KEY, -- "q_r" coordinate string
    q INTEGER NOT NULL,
    r INTEGER NOT NULL,
    type TEXT, -- OVERRIDE type if needed
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
    rarity TEXT DEFAULT 'COMMON', -- COMMON, RARE, LEGENDARY, MYTHIC
    image_url TEXT,
    mint_status TEXT DEFAULT 'PENDING', -- PENDING, MINTED
    mint_address TEXT, -- Solana Address
    attributes TEXT, -- JSON
    created_at INTEGER,
    FOREIGN KEY (player_id) REFERENCES game_players(id)
);

-- Add coordinates to player
ALTER TABLE game_players ADD COLUMN q INTEGER DEFAULT 0;
ALTER TABLE game_players ADD COLUMN r INTEGER DEFAULT 0;
