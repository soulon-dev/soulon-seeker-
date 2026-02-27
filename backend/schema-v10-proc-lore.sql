-- V10: Procedural Lore & Factions Schema
-- Supports 1000+ hours expanded content

-- 1. TIMELINE EVENTS (History & Future)
CREATE TABLE IF NOT EXISTS game_timeline_events (
    id TEXT PRIMARY KEY,
    year_ae INTEGER NOT NULL, -- After Era (AE)
    title TEXT NOT NULL,
    description TEXT,
    event_type TEXT DEFAULT 'FIXED', -- FIXED, PROCEDURAL, PLAYER_DRIVEN
    is_unlocked BOOLEAN DEFAULT 0,
    required_season_id TEXT,
    created_at INTEGER,
    updated_at INTEGER
);

-- 2. FACTIONS (Extended)
CREATE TABLE IF NOT EXISTS game_factions (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    faction_type TEXT DEFAULT 'NPC', -- NPC, PLAYER_CORPS
    alignment TEXT DEFAULT 'NEUTRAL', -- LAWFUL, CHAOTIC, NEUTRAL
    base_sector_q INTEGER,
    base_sector_r INTEGER,
    is_joinable BOOLEAN DEFAULT 0,
    created_at INTEGER,
    updated_at INTEGER
);

-- 3. PROCEDURAL GENERATION RULES (TAPIR System)
CREATE TABLE IF NOT EXISTS game_proc_gen_rules (
    id TEXT PRIMARY KEY,
    rule_type TEXT NOT NULL, -- LORE, ENTITY, SECTOR
    target_tags TEXT, -- JSON array of tags e.g. ["glitch", "void"]
    template_structure TEXT, -- JSON template for text generation
    probability_weight REAL DEFAULT 1.0,
    created_at INTEGER
);

-- 4. PLAYER FACTION RELATIONS
CREATE TABLE IF NOT EXISTS game_player_factions (
    player_id INTEGER,
    faction_id TEXT,
    reputation INTEGER DEFAULT 0, -- -100 to 100
    rank TEXT DEFAULT 'INITIATE',
    joined_at INTEGER,
    PRIMARY KEY (player_id, faction_id)
);

-- =================================================================================
-- SEED DATA: TIMELINE EVENTS (AE 55 - AE 125)
-- =================================================================================

INSERT OR IGNORE INTO game_timeline_events (id, year_ae, title, description, event_type, is_unlocked, created_at, updated_at)
VALUES 
('evt_ae55', 55, 'Echo Rebellion', 'The first organized uprising of Echo Pilots against the Admin Remnant. Players can choose to suppress or support.', 'PLAYER_DRIVEN', 0, strftime('%s','now'), strftime('%s','now')),
('evt_ae58', 58, 'Infinite Hallway Network', 'The "Infinite Hallway" glitch stabilizes into a traversable fast-travel network, but requires "Stabilized Glitch Matter" to enter.', 'FIXED', 0, strftime('%s','now'), strftime('%s','now')),
('evt_ae62', 62, 'Poetry Code Decryption', 'Architect fragments reunite. "Poetry Code" puzzles begin appearing in dungeons, unlocking custom ship modules.', 'FIXED', 0, strftime('%s','now'), strftime('%s','now')),
('evt_ae65', 65, 'Ghost Fleet 2150', 'A fleet from the pre-collapse era emerges from a wormhole. They believe the simulation is still 2150. Large PvE event.', 'FIXED', 0, strftime('%s','now'), strftime('%s','now')),
('evt_ae70', 70, 'Void Nomad Trade Network', 'The Void Nomads establish permanent trade hubs in deep space. Credit currency becomes universally accepted across servers.', 'FIXED', 0, strftime('%s','now'), strftime('%s','now')),
('evt_ae75', 75, 'Architect Leak', 'Source code leaks reveal the universe is a nested simulation. "Reality" buff/debuff mechanics introduced.', 'FIXED', 0, strftime('%s','now'), strftime('%s','now')),
('evt_ae85', 85, 'First Memory Storm', 'Global weather event. "Memory Storms" rewrite sector biomes temporarily. High risk, high reward.', 'PROCEDURAL', 0, strftime('%s','now'), strftime('%s','now')),
('evt_ae88', 88, 'Second Merge', 'Physical objects begin pixelating on a massive scale. The map expands by 1000+ procedural sectors.', 'FIXED', 0, strftime('%s','now'), strftime('%s','now')),
('evt_ae95', 95, 'Hive Awakening', 'Glitch Mites evolve into a hive mind. Dynamic Boss entities begin patrolling trade routes.', 'PROCEDURAL', 0, strftime('%s','now'), strftime('%s','now')),
('evt_ae100', 100, 'Subject Zero Returns', 'The first uploaded mind wakes up. Multi-ending branch: Is he a savior or a virus?', 'PLAYER_DRIVEN', 0, strftime('%s','now'), strftime('%s','now')),
('evt_ae110', 110, 'Echo Renaissance', 'Players gain the ability to "merge" consciousness, unlocking co-op piloting and base building.', 'FIXED', 0, strftime('%s','now'), strftime('%s','now')),
('evt_ae125', 125, 'The Final Signal', 'The countdown reaches zero. The season ends. The seed for the next universe is chosen.', 'FIXED', 0, strftime('%s','now'), strftime('%s','now'));

-- =================================================================================
-- SEED DATA: FACTIONS
-- =================================================================================

INSERT OR IGNORE INTO game_factions (id, name, description, faction_type, alignment, is_joinable, created_at, updated_at)
VALUES
-- Core Factions
('fac_vanguard', 'The Vanguard', 'Military order dedicated to protecting the "Status Quo" of the simulation.', 'NPC', 'LAWFUL', 0, 0, 1, strftime('%s','now'), strftime('%s','now')),
('fac_glitch_cult', 'Children of the Glitch', 'Believes bugs are features. Worshippers of entropy.', 'NPC', 'CHAOTIC', 100, 100, 1, strftime('%s','now'), strftime('%s','now')),
('fac_nomads', 'Void Nomads', 'Traders who live in the spaces between sectors. Neutral and profit-driven.', 'NPC', 'NEUTRAL', -50, 50, 1, strftime('%s','now'), strftime('%s','now')),

-- New Extended Factions
('fac_weavers', 'Shard Weavers', 'Artisans who forge equipment from stabilized glitch matter.', 'NPC', 'NEUTRAL', 20, -20, 1, strftime('%s','now'), strftime('%s','now')),
('fac_heretics', 'Reset Heretics', 'Radicals who want to force a system reset to purge corruption.', 'NPC', 'CHAOTIC', -100, -100, 1, strftime('%s','now'), strftime('%s','now')),
('fac_cartel', 'Phantom Cartel 2.0', 'The evolution of the Neon Phantom''s network. Controls the black market.', 'NPC', 'CHAOTIC', 50, 50, 1, strftime('%s','now'), strftime('%s','now')),
('fac_archivists', 'The Library', 'Keepers of the old world data. Led by The Archivist.', 'NPC', 'LAWFUL', 0, 0, 0, strftime('%s','now'), strftime('%s','now'));

-- =================================================================================
-- SEED DATA: PROC GEN RULES (Samples)
-- =================================================================================

INSERT OR IGNORE INTO game_proc_gen_rules (id, rule_type, target_tags, template_structure, probability_weight, created_at)
VALUES
('rule_lore_glitch', 'LORE', '["glitch", "horror"]', '{"title": "Log: {{Adjective}} Error", "content": "The {{Object}} started to {{Verb}} in a way that defied physics. I saw {{Number}} instances of myself."}', 1.5, strftime('%s','now')),
('rule_entity_void', 'ENTITY', '["void", "aggressive"]', '{"name": "Void {{Noun}}", "description": "A creature made of pure absence. It consumes {{Resource}}."}', 1.2, strftime('%s','now'));
