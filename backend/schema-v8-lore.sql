-- Game Lore Entries
CREATE TABLE IF NOT EXISTS game_lore_entries (
    id TEXT PRIMARY KEY,
    season_id TEXT,
    title TEXT NOT NULL,
    content TEXT NOT NULL, -- Markdown or plain text
    unlock_threshold INTEGER DEFAULT 0, -- Fragments required to unlock
    created_at INTEGER,
    updated_at INTEGER,
    FOREIGN KEY (season_id) REFERENCES game_seasons(id)
);

-- Player Lore Unlocks
CREATE TABLE IF NOT EXISTS game_player_lore (
    player_id INTEGER,
    lore_id TEXT,
    unlocked_at INTEGER,
    PRIMARY KEY (player_id, lore_id),
    FOREIGN KEY (player_id) REFERENCES game_players(id),
    FOREIGN KEY (lore_id) REFERENCES game_lore_entries(id)
);

-- Initial Lore Data for Season 1
INSERT OR IGNORE INTO game_lore_entries (id, season_id, title, content, unlock_threshold, created_at, updated_at)
VALUES 
('lore_s1_01', 'season_01', 'Genesis Log: The First Glitch', 'Day 0. The network hummed as usual until Sector 7 went dark. It wasn''t a power outage. It was... silence. A void where data should be. The Archivist claims it''s a minor corruption, but I saw the logs. The timestamps were moving backwards.', 1, strftime('%s','now'), strftime('%s','now')),
('lore_s1_02', 'season_01', 'Genesis Log: Echoes', 'Day 3. We sent a drone into the void. It came back... changed. Its hull was intact, but its memory core was filled with static. When we decoded the static, it sounded like a human voice screaming in binary.', 10, strftime('%s','now'), strftime('%s','now')),
('lore_s1_03', 'season_01', 'Genesis Log: The Signal', 'Day 7. The static has a pattern. It''s a countdown. Not to zero, but to... synchronization. Someone, or something, is trying to bridge the gap between the Old World and the Memory Sea.', 50, strftime('%s','now'), strftime('%s','now')),
('lore_s1_04', 'season_01', 'Genesis Log: Contagion', 'Day 14. The corruption is spreading. Ships returning from the outer rim are carrying "artifacts" that shouldn''t exist. Physical objects manifesting from code. The Neon Phantom says it''s an opportunity. I say it''s an invasion.', 100, strftime('%s','now'), strftime('%s','now')),
('lore_s1_05', 'season_01', 'Genesis Log: Awakening', 'Day 30. We are not alone. The signal isn''t a message. It''s a heartbeat.', 500, strftime('%s','now'), strftime('%s','now'));
