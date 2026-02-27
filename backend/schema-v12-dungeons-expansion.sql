INSERT INTO game_dungeons (id, name, description, difficulty_level, max_depth, entry_cost, created_at, updated_at)
VALUES 
('dungeon_cyber_purgatory', 'Cyber Purgatory', 'A quarantined zone where deleted AIs scream in binary.', 3, 10, 500, strftime('%s','now'), strftime('%s','now')),
('dungeon_firewall_fortress', 'Firewall Fortress', 'Heavily guarded Consortium data vault.', 4, 15, 1000, strftime('%s','now'), strftime('%s','now')),
('dungeon_void_nexus', 'The Void Nexus', 'The center of the digital entropy. Only for the insane.', 5, 20, 2500, strftime('%s','now'), strftime('%s','now'))
ON CONFLICT(id) DO NOTHING;
