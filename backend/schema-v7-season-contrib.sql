-- Player Season Contribution
CREATE TABLE IF NOT EXISTS game_player_season_contrib (
    player_id INTEGER,
    season_id TEXT,
    total_contribution INTEGER DEFAULT 0,
    created_at INTEGER,
    updated_at INTEGER,
    PRIMARY KEY (player_id, season_id),
    FOREIGN KEY (player_id) REFERENCES game_players(id),
    FOREIGN KEY (season_id) REFERENCES game_seasons(id)
);

-- Insert Signal Fragment Item
INSERT OR IGNORE INTO game_goods (id, name, description, base_price, volatility)
VALUES ('signal_fragment', 'Signal Fragment', 'A corrupted data shard emitting a faint pulse.', 0, 0);
