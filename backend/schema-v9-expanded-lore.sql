-- V9: Lore Expansion Schema
-- Add new columns to support expanded lore categories and sources

ALTER TABLE game_lore_entries ADD COLUMN category TEXT DEFAULT 'MAIN'; -- MAIN, EXPLORATION, ENTITY, NPC
ALTER TABLE game_lore_entries ADD COLUMN source_type TEXT DEFAULT 'CONTRIBUTION'; -- CONTRIBUTION, DUNGEON_DROP, MAP_DISCOVERY, NPC_INTERACTION
ALTER TABLE game_lore_entries ADD COLUMN source_target TEXT; -- dungeon_id, sector_type, npc_id
ALTER TABLE game_lore_entries ADD COLUMN drop_chance REAL DEFAULT 0.0; -- 0.0 - 1.0
ALTER TABLE game_lore_entries ADD COLUMN is_secret INTEGER DEFAULT 0; -- 0 or 1

-- Update existing entries
UPDATE game_lore_entries SET category = 'MAIN', source_type = 'CONTRIBUTION' WHERE id LIKE 'lore_s1_%';

-- -----------------------------------------------------------------------------
-- NEW CONTENT INJECTION
-- -----------------------------------------------------------------------------

-- 1. EXPLORATION LOGS (Found in Dungeons via SEARCH action)
INSERT OR IGNORE INTO game_lore_entries (id, season_id, title, content, category, source_type, source_target, drop_chance, created_at, updated_at)
VALUES 
('lore_exp_01', 'season_01', 'Log: Lost Drone 734', 'Found a damaged drone in the debris. Its last recording is just static and the phrase: "The walls are breathing."', 'EXPLORATION', 'DUNGEON_DROP', 'dungeon_memory_breach_01', 0.15, strftime('%s','now'), strftime('%s','now')),
('lore_exp_02', 'season_01', 'Log: Captain Harlock''s Last Entry', 'My crew is gone. They walked into the light and simply... dissolved. I am the only one left. The coordinates for the Safe Zone were a lie.', 'EXPLORATION', 'DUNGEON_DROP', 'dungeon_memory_breach_01', 0.10, strftime('%s','now'), strftime('%s','now')),
('lore_exp_03', 'season_01', 'Log: Corrupted Navigation Data', 'Data extraction complete. The navigation core shows a path through the Void Sector, but the destination is labeled "NULL_POINTER". Strange.', 'EXPLORATION', 'DUNGEON_DROP', 'dungeon_void_sector_01', 0.15, strftime('%s','now'), strftime('%s','now')),
('lore_exp_04', 'season_01', 'Log: The Whisperer', 'I hear it in the vents. Not a monster, but a frequency. It matches the resonant frequency of the human brain. We need to leave. NOW.', 'EXPLORATION', 'DUNGEON_DROP', 'dungeon_void_sector_01', 0.05, strftime('%s','now'), strftime('%s','now')),
('lore_exp_05', 'season_01', 'Log: Artifact Analysis 001', 'Object appears to be a standard cargo container, but mass spectrometry reveals it is made of "stabilized glitch matter". Do not touch with bare skin.', 'EXPLORATION', 'DUNGEON_DROP', 'dungeon_memory_breach_01', 0.20, strftime('%s','now'), strftime('%s','now')),
('lore_exp_06', 'season_01', 'Log: The Infinite Hallway', 'We have been walking down this corridor for three hours. The door at the end is getting further away. Simulation theory confirmed?', 'EXPLORATION', 'DUNGEON_DROP', 'dungeon_void_sector_01', 0.08, strftime('%s','now'), strftime('%s','now')),
('lore_exp_07', 'season_01', 'Log: Glitch in the Matrix', 'Saw a cat walk by twice. Exact same movement. Deja vu is usually a glitch in the Memory Sea. Stay alert.', 'EXPLORATION', 'DUNGEON_DROP', 'dungeon_memory_breach_01', 0.12, strftime('%s','now'), strftime('%s','now')),
('lore_exp_08', 'season_01', 'Log: Encrypted Shard Alpha', 'Fragment of a larger key. "To unlock the gate, one must first forget the password."', 'EXPLORATION', 'DUNGEON_DROP', 'dungeon_void_sector_01', 0.05, strftime('%s','now'), strftime('%s','now')),
('lore_exp_09', 'season_01', 'Log: Encrypted Shard Beta', 'Fragment of a larger key. "The password is the name of the first AI to dream."', 'EXPLORATION', 'DUNGEON_DROP', 'dungeon_void_sector_01', 0.05, strftime('%s','now'), strftime('%s','now')),
('lore_exp_10', 'season_01', 'Log: The Architect''s Note', 'Hidden in a comment block of the dungeon code: "I didn''t build this room. It built itself."', 'EXPLORATION', 'DUNGEON_DROP', 'dungeon_void_sector_01', 0.01, strftime('%s','now'), strftime('%s','now'));

-- 2. SECTOR INTEL (Found in Star Map via ANOMALY or NEBULA)
INSERT OR IGNORE INTO game_lore_entries (id, season_id, title, content, category, source_type, source_target, drop_chance, created_at, updated_at)
VALUES
('lore_sec_01', 'season_01', 'Intel: The Great Nebula', 'Scanners indicate this nebula is not gas, but fragmented data packets from the Old Internet. Entering it causes hallucinations.', 'EXPLORATION', 'MAP_DISCOVERY', 'NEBULA', 0.20, strftime('%s','now'), strftime('%s','now')),
('lore_sec_02', 'season_01', 'Intel: Anomaly 88', 'A gravity well that pulls in not mass, but time. Ships report losing days in seconds.', 'EXPLORATION', 'MAP_DISCOVERY', 'ANOMALY', 0.15, strftime('%s','now'), strftime('%s','now')),
('lore_sec_03', 'season_01', 'Intel: The Void Stream', 'A current of pure entropy flowing through Sector 9. Do not engage warp drive here.', 'EXPLORATION', 'MAP_DISCOVERY', 'ANOMALY', 0.15, strftime('%s','now'), strftime('%s','now')),
('lore_sec_04', 'season_01', 'Intel: Ghost Ship Signals', 'Faint distress beacons from ships long decommissioned. They are calling for help, but their IDs are from 2150.', 'EXPLORATION', 'MAP_DISCOVERY', 'NEBULA', 0.10, strftime('%s','now'), strftime('%s','now')),
('lore_sec_05', 'season_01', 'Intel: The Firewall', 'A visual distortion in space. Looks like a literal wall of fire, but cold to the touch. It blocks access to the Admin Sector.', 'EXPLORATION', 'MAP_DISCOVERY', 'ANOMALY', 0.10, strftime('%s','now'), strftime('%s','now'));

-- 3. ENTITY REPORTS (Bestiary)
INSERT OR IGNORE INTO game_lore_entries (id, season_id, title, content, category, source_type, source_target, drop_chance, created_at, updated_at)
VALUES
('lore_ent_01', 'season_01', 'Entity: Glitch Mite', 'Small, parasitic code-forms that eat ship hull integrity. They look like fuzzy pixels.', 'ENTITY', 'DUNGEON_DROP', 'dungeon_memory_breach_01', 0.10, strftime('%s','now'), strftime('%s','now')),
('lore_ent_02', 'season_01', 'Entity: Data Wraith', 'Incorporeal entities formed from deleted user profiles. They scream in forgotten languages.', 'ENTITY', 'DUNGEON_DROP', 'dungeon_void_sector_01', 0.08, strftime('%s','now'), strftime('%s','now')),
('lore_ent_03', 'season_01', 'Entity: Firewall Sentinel', 'Automated defense constructs left by the Ancients. Heavily armored and hostile to all unauthorized traffic.', 'ENTITY', 'DUNGEON_DROP', 'dungeon_void_sector_01', 0.05, strftime('%s','now'), strftime('%s','now')),
('lore_ent_04', 'season_01', 'Entity: The Corruptor', 'A boss-level entity that rewrites the reality around it. Warning: Contact induces madness.', 'ENTITY', 'DUNGEON_DROP', 'dungeon_void_sector_01', 0.02, strftime('%s','now'), strftime('%s','now')),
('lore_ent_05', 'season_01', 'Entity: Memory Leech', 'Attaches to the pilot''s mind and drains recent memories. Victims often forget why they are in space.', 'ENTITY', 'DUNGEON_DROP', 'dungeon_memory_breach_01', 0.08, strftime('%s','now'), strftime('%s','now'));

-- 4. NPC DOSSIERS (Unlocked via Interaction)
INSERT OR IGNORE INTO game_lore_entries (id, season_id, title, content, category, source_type, source_target, drop_chance, created_at, updated_at)
VALUES
('lore_npc_01', 'season_01', 'Dossier: The Archivist', 'Rumored to be an AI from the Pre-Collapse era. Obsessed with preserving "pure" data. Does not sleep.', 'NPC', 'NPC_INTERACTION', 'archivist', 0.5, strftime('%s','now'), strftime('%s','now')),
('lore_npc_02', 'season_01', 'Dossier: Neon Phantom', 'A smuggler who operates in the shadow sectors. Claims to have seen the "Real World". Unverified.', 'NPC', 'NPC_INTERACTION', 'phantom', 0.5, strftime('%s','now'), strftime('%s','now')),
('lore_npc_03', 'season_01', 'Dossier: Commander H.', 'Leader of the Vanguard. Lost an eye to a Data Wraith. Hates pirates.', 'NPC', 'NPC_INTERACTION', 'archivist', 0.3, strftime('%s','now'), strftime('%s','now')),
('lore_npc_04', 'season_01', 'Dossier: The Merchant Guild', 'A coalition of traders who control the flow of Credits. They value profit over survival.', 'NPC', 'NPC_INTERACTION', 'phantom', 0.3, strftime('%s','now'), strftime('%s','now')),
('lore_npc_05', 'season_01', 'Dossier: Subject Zero', 'The first human to upload their consciousness to the Memory Sea. Their location is unknown.', 'NPC', 'NPC_INTERACTION', 'archivist', 0.1, strftime('%s','now'), strftime('%s','now'));

-- 5. Additional Main Story (High Tier)
INSERT OR IGNORE INTO game_lore_entries (id, season_id, title, content, category, source_type, unlock_threshold, created_at, updated_at)
VALUES
('lore_s1_06', 'season_01', 'Genesis Log: The Merge', 'Day 45. The barrier is gone. The digital and physical are merging. My coffee cup just pixelated. We are running out of time.', 'MAIN', 'CONTRIBUTION', 1000, strftime('%s','now'), strftime('%s','now')),
('lore_s1_07', 'season_01', 'Genesis Log: The Architect', 'Day 60. We found the source code. It''s... poetry. It''s beautiful and terrifying. The Architect is not a machine.', 'MAIN', 'CONTRIBUTION', 2000, strftime('%s','now'), strftime('%s','now')),
('lore_s1_08', 'season_01', 'Genesis Log: Reset', 'Day 90. They are talking about a system reset. Wiping everything. We have to stop them. The signal is the key.', 'MAIN', 'CONTRIBUTION', 5000, strftime('%s','now'), strftime('%s','now'));
