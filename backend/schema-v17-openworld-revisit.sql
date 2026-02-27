-- Open World revisit tracking

ALTER TABLE game_player_exploration ADD COLUMN visit_count INTEGER NOT NULL DEFAULT 1;

