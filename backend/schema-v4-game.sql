-- ============================================
-- Schema V4: 大航海跑商系统 (Great Voyage)
-- ============================================

-- 1. 玩家游戏状态表
CREATE TABLE IF NOT EXISTS game_players (
    user_id TEXT PRIMARY KEY,
    wallet_address TEXT NOT NULL,
    gold INTEGER DEFAULT 1000,
    current_port_id TEXT NOT NULL DEFAULT 'port_001',
    ship_level INTEGER DEFAULT 1,
    cargo_capacity INTEGER DEFAULT 50,
    status INTEGER DEFAULT 0, -- 0=Docked, 1=Sailing
    sailing_destination TEXT,
    sailing_arrival_time INTEGER, -- Timestamp
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- 2. 港口基础信息表 (静态配置，但可动态更新)
CREATE TABLE IF NOT EXISTS game_ports (
    id TEXT PRIMARY KEY,
    name_zh TEXT NOT NULL,
    name_en TEXT NOT NULL,
    desc_zh TEXT,
    desc_en TEXT,
    region TEXT, -- Asia, Europe, etc.
    coordinates_x INTEGER,
    coordinates_y INTEGER,
    is_active INTEGER DEFAULT 1
);

-- 3. 商品基础信息表
CREATE TABLE IF NOT EXISTS game_goods (
    id TEXT PRIMARY KEY,
    name_zh TEXT NOT NULL,
    name_en TEXT NOT NULL,
    base_price INTEGER NOT NULL,
    type TEXT -- Luxury, Food, RawMaterial
);

-- 4. 市场行情表 (动态波动)
CREATE TABLE IF NOT EXISTS game_market (
    port_id TEXT NOT NULL,
    good_id TEXT NOT NULL,
    stock INTEGER DEFAULT 1000,
    demand INTEGER DEFAULT 1000, -- 理想库存，用于计算价格
    buy_price INTEGER NOT NULL,
    sell_price INTEGER NOT NULL,
    volatility_factor REAL DEFAULT 0.1, -- 波动因子
    updated_at INTEGER DEFAULT (unixepoch()),
    PRIMARY KEY (port_id, good_id),
    FOREIGN KEY (port_id) REFERENCES game_ports(id),
    FOREIGN KEY (good_id) REFERENCES game_goods(id)
);

-- 5. 玩家持仓表
CREATE TABLE IF NOT EXISTS game_inventory (
    user_id TEXT NOT NULL,
    good_id TEXT NOT NULL,
    amount INTEGER DEFAULT 0,
    avg_cost INTEGER DEFAULT 0, -- 平均持仓成本
    updated_at INTEGER DEFAULT (unixepoch()),
    PRIMARY KEY (user_id, good_id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (good_id) REFERENCES game_goods(id)
);

-- 6. 初始数据填充 (Seed Data)
INSERT OR IGNORE INTO game_ports (id, name_zh, name_en, region, coordinates_x, coordinates_y) VALUES
('port_001', '泉州', 'Quanzhou', 'East Asia', 100, 100),
('port_002', '马六甲', 'Malacca', 'Southeast Asia', 120, 150),
('port_003', '加尔各答', 'Calcutta', 'South Asia', 140, 140),
('port_004', '开普敦', 'Cape Town', 'Africa', 100, 300),
('port_005', '伦敦', 'London', 'Europe', 80, 50);

INSERT OR IGNORE INTO game_goods (id, name_zh, name_en, base_price) VALUES
('good_001', '丝绸', 'Silk', 500),
('good_002', '瓷器', 'Porcelain', 800),
('good_003', '香料', 'Spices', 300),
('good_004', '茶叶', 'Tea', 200),
('good_005', '羊毛', 'Wool', 100);

-- 初始化市场 (示例)
-- 泉州产丝绸和瓷器 (库存高，价格低)
INSERT OR IGNORE INTO game_market (port_id, good_id, stock, demand, buy_price, sell_price) VALUES
('port_001', 'good_001', 2000, 1000, 450, 400), -- Silk in Quanzhou
('port_001', 'good_002', 1500, 1000, 750, 700), -- Porcelain in Quanzhou
('port_001', 'good_003', 500, 1000, 350, 300);  -- Spices in Quanzhou (Imported)

-- 伦敦产羊毛
INSERT OR IGNORE INTO game_market (port_id, good_id, stock, demand, buy_price, sell_price) VALUES
('port_005', 'good_005', 3000, 1000, 80, 70),   -- Wool in London
('port_005', 'good_001', 100, 1000, 1000, 900); -- Silk in London (Rare)
