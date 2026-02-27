/**
 * 管理后台 - 统一配置中心 API
 * 支持所有 App 配置的 CRUD、历史记录、批量更新
 */

import { AdminContext, logAdminAction } from './middleware'
import { signConfigValue } from '../../utils/config-signature'

interface ConfigItem {
  id: number
  configKey: string
  configValue: string
  valueType: string
  category: string
  subCategory: string | null
  displayName: string
  description: string | null
  defaultValue: string | null
  minValue: string | null
  maxValue: string | null
  options: string | null
  isSensitive: boolean
  requiresRestart: boolean
  isActive: boolean
  updatedBy: string | null
  createdAt: string
  updatedAt: string
}

const BUILTIN_CONFIG_DEFS: Record<string, Omit<ConfigItem, 'id' | 'configValue' | 'createdAt' | 'updatedAt'>> = {
  'subscription.plans': {
    configKey: 'subscription.plans',
    valueType: 'json',
    category: 'subscription',
    subCategory: 'plans',
    displayName: '订阅-档位配置（JSON）',
    description: '订阅页档位卡片的配置：展示顺序、默认选中、角标文案、价格、权益等',
    defaultValue: JSON.stringify({
      version: 1,
      defaultSelectedId: 'yearly',
      uiRules: {
        hidePlans: [
          {
            planIds: ['monthly_continuous', 'quarterly_continuous'],
            when: {}
          }
        ],
        disallowSelect: [
          {
            planIds: ['monthly_continuous'],
            message: '连续包季会员不可直接降级为连续包月。请先在订阅管理取消订阅，待到期后再订阅连续包月会员。',
            when: {
              any: [
                { activeSubscriptionTypeIn: ['quarterly_continuous'] },
                { autoRenewPlanTypeIn: [2] },
                { pendingPlanTypeIn: [2] }
              ]
            }
          }
        ],
        autoRenewUpgrade: [
          {
            fromPlanType: 1,
            toPlanType: 2,
            targetPlanIds: ['quarterly_continuous'],
            action: 'schedule_change',
            title: '确认升级',
            description: '将把当前连续包月升级为连续包季。升级将于当前周期到期后生效，届时开始按季度扣款。升级后的第一笔扣款前不可取消订阅合约。',
            lockCancelUntilEffective: true
          }
        ]
      },
      plans: [
        {
          id: 'monthly_continuous',
          basePlanId: 'monthly',
          name: '连续包月',
          shortName: '连续包月',
          priceUsdc: 9.99,
          renewalPriceUsdc: 9.99,
          pricePerMonth: '≈ $9.99/月',
          duration: '1 个月',
          durationMonths: 1,
          autoRenew: true,
          badgeText: null,
          savings: null,
          tokenMultiplier: 2.0,
          pointsMultiplier: 1.5,
          features: [
            '解锁生态质押功能',
            '每月 Token 限额提升 2 倍',
            '积分累积加速 1.5x',
            '专属客服支持'
          ]
        },
        {
          id: 'monthly',
          basePlanId: 'monthly',
          name: '月费',
          shortName: '月费',
          priceUsdc: 9.99,
          renewalPriceUsdc: null,
          pricePerMonth: '≈ $9.99/月',
          duration: '1 个月',
          durationMonths: 1,
          autoRenew: false,
          badgeText: null,
          savings: null,
          tokenMultiplier: 2.0,
          pointsMultiplier: 1.5,
          features: [
            '解锁生态质押功能',
            '每月 Token 限额提升 2 倍',
            '积分累积加速 1.5x',
            '专属客服支持'
          ]
        },
        {
          id: 'yearly',
          basePlanId: 'yearly',
          name: '12 个月',
          shortName: '12个月',
          priceUsdc: 79.99,
          renewalPriceUsdc: null,
          pricePerMonth: '≈ $6.67/月',
          duration: '12 个月',
          durationMonths: 12,
          autoRenew: false,
          badgeText: '推荐',
          savings: '省33%',
          tokenMultiplier: 5.0,
          pointsMultiplier: 3.0,
          features: [
            '包含季度会员所有权益',
            '每月 Token 限额提升 5 倍',
            '积分累积加速 3.0x',
            '专属空投资格',
            '治理投票权'
          ]
        },
        {
          id: 'quarterly',
          basePlanId: 'quarterly',
          name: '3 个月',
          shortName: '3个月',
          priceUsdc: 24.99,
          renewalPriceUsdc: null,
          pricePerMonth: '≈ $8.33/月',
          duration: '3 个月',
          durationMonths: 3,
          autoRenew: false,
          badgeText: '推荐',
          savings: '省17%',
          tokenMultiplier: 3.0,
          pointsMultiplier: 2.0,
          features: [
            '包含月费所有权益',
            '每月 Token 限额提升 3 倍',
            '积分累积加速 2.0x',
            '优先体验新功能'
          ]
        },
        {
          id: 'quarterly_continuous',
          basePlanId: 'quarterly',
          name: '连续包季',
          shortName: '连续包季',
          priceUsdc: 24.99,
          renewalPriceUsdc: 24.99,
          pricePerMonth: '≈ $8.33/月',
          duration: '3 个月',
          durationMonths: 3,
          autoRenew: true,
          badgeText: null,
          savings: null,
          tokenMultiplier: 3.0,
          pointsMultiplier: 2.0,
          features: [
            '包含月费所有权益',
            '每月 Token 限额提升 3 倍',
            '积分累积加速 2.0x',
            '优先体验新功能'
          ]
        },
        {
          id: 'monthly_one_time',
          basePlanId: 'monthly',
          name: '一个月',
          shortName: '一个月',
          priceUsdc: 9.99,
          renewalPriceUsdc: null,
          pricePerMonth: '≈ $9.99/月',
          duration: '1 个月',
          durationMonths: 1,
          autoRenew: false,
          badgeText: null,
          savings: null,
          tokenMultiplier: 2.0,
          pointsMultiplier: 1.5,
          features: [
            '解锁生态质押功能',
            '每月 Token 限额提升 2 倍',
            '积分累积加速 1.5x',
            '专属客服支持'
          ]
        }
      ]
    }),
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'subscription.monthly_usdc': {
    configKey: 'subscription.monthly_usdc',
    valueType: 'number',
    category: 'subscription',
    subCategory: 'pricing',
    displayName: '订阅-月费价格（USDC）',
    description: '订阅页月费会员的锚定价格（USDC）',
    defaultValue: '9.99',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'subscription.quarterly_usdc': {
    configKey: 'subscription.quarterly_usdc',
    valueType: 'number',
    category: 'subscription',
    subCategory: 'pricing',
    displayName: '订阅-季度价格（USDC）',
    description: '订阅页 3 个月会员的锚定价格（USDC）',
    defaultValue: '24.99',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'subscription.yearly_usdc': {
    configKey: 'subscription.yearly_usdc',
    valueType: 'number',
    category: 'subscription',
    subCategory: 'pricing',
    displayName: '订阅-年度价格（USDC）',
    description: '订阅页 12 个月会员的锚定价格（USDC）',
    defaultValue: '79.99',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'subscription.monthly_token_multiplier': {
    configKey: 'subscription.monthly_token_multiplier',
    valueType: 'number',
    category: 'subscription',
    subCategory: 'benefits',
    displayName: '订阅-月费 Token 倍数',
    description: '订阅页月费会员每月 Token 限额倍数',
    defaultValue: '2.0',
    minValue: '1.0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'subscription.quarterly_token_multiplier': {
    configKey: 'subscription.quarterly_token_multiplier',
    valueType: 'number',
    category: 'subscription',
    subCategory: 'benefits',
    displayName: '订阅-季度 Token 倍数',
    description: '订阅页季度会员每月 Token 限额倍数',
    defaultValue: '3.0',
    minValue: '1.0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'subscription.yearly_token_multiplier': {
    configKey: 'subscription.yearly_token_multiplier',
    valueType: 'number',
    category: 'subscription',
    subCategory: 'benefits',
    displayName: '订阅-年度 Token 倍数',
    description: '订阅页年度会员每月 Token 限额倍数',
    defaultValue: '5.0',
    minValue: '1.0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'subscription.monthly_points_multiplier': {
    configKey: 'subscription.monthly_points_multiplier',
    valueType: 'number',
    category: 'subscription',
    subCategory: 'benefits',
    displayName: '订阅-月费积分倍数',
    description: '订阅页月费会员积分加速倍数',
    defaultValue: '1.5',
    minValue: '1.0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'subscription.quarterly_points_multiplier': {
    configKey: 'subscription.quarterly_points_multiplier',
    valueType: 'number',
    category: 'subscription',
    subCategory: 'benefits',
    displayName: '订阅-季度积分倍数',
    description: '订阅页季度会员积分加速倍数',
    defaultValue: '2.0',
    minValue: '1.0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'subscription.yearly_points_multiplier': {
    configKey: 'subscription.yearly_points_multiplier',
    valueType: 'number',
    category: 'subscription',
    subCategory: 'benefits',
    displayName: '订阅-年度积分倍数',
    description: '订阅页年度会员积分加速倍数',
    defaultValue: '3.0',
    minValue: '1.0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'subscription.badge.yearly': {
    configKey: 'subscription.badge.yearly',
    valueType: 'string',
    category: 'subscription',
    subCategory: 'ui',
    displayName: '订阅-12个月推荐角标文案',
    description: '订阅页 12 个月卡片右上角推荐角标的小字',
    defaultValue: '推荐',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'subscription.badge.quarterly': {
    configKey: 'subscription.badge.quarterly',
    valueType: 'string',
    category: 'subscription',
    subCategory: 'ui',
    displayName: '订阅-3个月推荐角标文案',
    description: '订阅页 3 个月卡片右上角推荐角标的小字',
    defaultValue: '推荐',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.market.refresh_ttl_seconds': {
    configKey: 'game.market.refresh_ttl_seconds',
    valueType: 'number',
    category: 'game',
    subCategory: 'market',
    displayName: '玩法-市场刷新 TTL（秒）',
    description: '同一港口的市场价格/库存刷新间隔（秒）',
    defaultValue: '300',
    minValue: '10',
    maxValue: '86400',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.market.price_variation_ratio': {
    configKey: 'game.market.price_variation_ratio',
    valueType: 'number',
    category: 'game',
    subCategory: 'market',
    displayName: '玩法-市场价格波动比例',
    description: '刷新时价格的随机波动比例（0~0.8），会乘以 goods.volatility',
    defaultValue: '0.2',
    minValue: '0',
    maxValue: '0.8',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.market.stock_min': {
    configKey: 'game.market.stock_min',
    valueType: 'number',
    category: 'game',
    subCategory: 'market',
    displayName: '玩法-市场库存下限',
    description: '刷新时库存随机范围下限（含）',
    defaultValue: '5',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.market.stock_max': {
    configKey: 'game.market.stock_max',
    valueType: 'number',
    category: 'game',
    subCategory: 'market',
    displayName: '玩法-市场库存上限',
    description: '刷新时库存随机范围上限（含）',
    defaultValue: '125',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.market.init_price_variation_ratio': {
    configKey: 'game.market.init_price_variation_ratio',
    valueType: 'number',
    category: 'game',
    subCategory: 'market',
    displayName: '玩法-市场初始化价格波动比例',
    description: '首次初始化市场时价格的随机波动比例（0~0.8），默认跟随刷新波动',
    defaultValue: '0.2',
    minValue: '0',
    maxValue: '0.8',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.market.init_stock_min': {
    configKey: 'game.market.init_stock_min',
    valueType: 'number',
    category: 'game',
    subCategory: 'market',
    displayName: '玩法-市场初始化库存下限',
    description: '首次初始化市场时库存随机范围下限（含）',
    defaultValue: '10',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.market.init_stock_max': {
    configKey: 'game.market.init_stock_max',
    valueType: 'number',
    category: 'game',
    subCategory: 'market',
    displayName: '玩法-市场初始化库存上限',
    description: '首次初始化市场时库存随机范围上限（含）',
    defaultValue: '110',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.market.port_overrides': {
    configKey: 'game.market.port_overrides',
    valueType: 'json',
    category: 'game',
    subCategory: 'market',
    displayName: '玩法-市场港口差异覆盖（JSON）',
    description: '按 port_id 覆盖市场参数：refresh_ttl_seconds/price_variation_ratio/stock_min/stock_max/init_*',
    defaultValue: '{}',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.trade_tax_rate': {
    configKey: 'game.econ.trade_tax_rate',
    valueType: 'number',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-交易关税比例',
    description: '买入/卖出时按金额收取的关税比例（0~0.5）',
    defaultValue: '0.02',
    minValue: '0',
    maxValue: '0.5',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.trade_tax_min': {
    configKey: 'game.econ.trade_tax_min',
    valueType: 'number',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-交易关税最低值',
    description: '每次买入/卖出的关税最低值（G）',
    defaultValue: '1',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.sell_price_ratio': {
    configKey: 'game.econ.sell_price_ratio',
    valueType: 'number',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-卖出价格系数',
    description: '卖出价格 = 市场价 * 系数（0~1），用于基础价差',
    defaultValue: '0.9',
    minValue: '0',
    maxValue: '1',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.jump_cost': {
    configKey: 'game.econ.jump_cost',
    valueType: 'number',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-星图跳跃基础燃料费',
    description: '每次星图相邻移动的基础花费（G）',
    defaultValue: '10',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.jump_cost_per_ship_level': {
    configKey: 'game.econ.jump_cost_per_ship_level',
    valueType: 'number',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-星图跳跃等级附加费',
    description: '每高 1 级船体，星图移动额外花费（G）',
    defaultValue: '0',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.travel_base_cost': {
    configKey: 'game.econ.travel_base_cost',
    valueType: 'number',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-航行基础补给费',
    description: '港口间航行的基础花费（G）',
    defaultValue: '20',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.travel_cost_per_distance': {
    configKey: 'game.econ.travel_cost_per_distance',
    valueType: 'number',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-航行距离燃料费',
    description: '港口坐标距离（曼哈顿距离）每 1 点对应的花费（G）',
    defaultValue: '20',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.travel_cost_per_ship_level': {
    configKey: 'game.econ.travel_cost_per_ship_level',
    valueType: 'number',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-航行等级附加费',
    description: '每高 1 级船体，航行额外花费（G）',
    defaultValue: '0',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.shipyard_cargo_cost_per_capacity': {
    configKey: 'game.econ.shipyard_cargo_cost_per_capacity',
    valueType: 'number',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-货舱升级成本系数',
    description: '货舱升级成本 = cargo_capacity * 系数',
    defaultValue: '50',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.shipyard_cargo_delta_capacity': {
    configKey: 'game.econ.shipyard_cargo_delta_capacity',
    valueType: 'number',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-货舱升级增量',
    description: '每次货舱升级增加的容量',
    defaultValue: '10',
    minValue: '1',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.shipyard_ship_level_cost_multiplier': {
    configKey: 'game.econ.shipyard_ship_level_cost_multiplier',
    valueType: 'number',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-船体升级成本系数',
    description: '船体升级成本 = ship_level * 系数',
    defaultValue: '2000',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.repair_base_cost': {
    configKey: 'game.econ.repair_base_cost',
    valueType: 'number',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-船坞维修基础费',
    description: '船坞维修的基础费用（G）',
    defaultValue: '50',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.repair_cost_per_ship_level': {
    configKey: 'game.econ.repair_cost_per_ship_level',
    valueType: 'number',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-船坞维修等级附加费',
    description: '每高 1 级船体，船坞维修额外费用（G）',
    defaultValue: '25',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.econ.port_overrides': {
    configKey: 'game.econ.port_overrides',
    valueType: 'json',
    category: 'game',
    subCategory: 'economy',
    displayName: '玩法-经济港口差异覆盖（JSON）',
    description: '按 port_id 覆盖经济参数：trade_tax_rate/trade_tax_min/sell_price_ratio/travel_*/shipyard_*/repair_*',
    defaultValue: '{}',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.travel.base_duration_seconds': {
    configKey: 'game.travel.base_duration_seconds',
    valueType: 'number',
    category: 'game',
    subCategory: 'travel',
    displayName: '玩法-航行基础时长（秒）',
    description: '港口航行的基础时长（秒）',
    defaultValue: '30',
    minValue: '5',
    maxValue: '86400',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.travel.seconds_per_distance': {
    configKey: 'game.travel.seconds_per_distance',
    valueType: 'number',
    category: 'game',
    subCategory: 'travel',
    displayName: '玩法-航行距离耗时（秒/距离）',
    description: '港口坐标距离（曼哈顿距离）每 1 点对应增加的航行秒数',
    defaultValue: '20',
    minValue: '0',
    maxValue: '86400',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.travel.seconds_per_ship_level': {
    configKey: 'game.travel.seconds_per_ship_level',
    valueType: 'number',
    category: 'game',
    subCategory: 'travel',
    displayName: '玩法-航行等级附加时长（秒/级）',
    description: '每高 1 级船体，航行额外增加的秒数',
    defaultValue: '0',
    minValue: '0',
    maxValue: '86400',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.travel.encounter_reward_chance': {
    configKey: 'game.travel.encounter_reward_chance',
    valueType: 'number',
    category: 'game',
    subCategory: 'travel',
    displayName: '玩法-航行正向遭遇概率',
    description: '航行中触发正向遭遇的概率（0~1）',
    defaultValue: '0.15',
    minValue: '0',
    maxValue: '1',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.travel.encounter_penalty_chance': {
    configKey: 'game.travel.encounter_penalty_chance',
    valueType: 'number',
    category: 'game',
    subCategory: 'travel',
    displayName: '玩法-航行负向遭遇概率',
    description: '航行中触发负向遭遇的概率（0~1）',
    defaultValue: '0.1',
    minValue: '0',
    maxValue: '1',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.travel.encounter_reward_min': {
    configKey: 'game.travel.encounter_reward_min',
    valueType: 'number',
    category: 'game',
    subCategory: 'travel',
    displayName: '玩法-航行正向遭遇奖励下限（G）',
    description: '航行正向遭遇的金币奖励范围下限（G）',
    defaultValue: '50',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.travel.encounter_reward_max': {
    configKey: 'game.travel.encounter_reward_max',
    valueType: 'number',
    category: 'game',
    subCategory: 'travel',
    displayName: '玩法-航行正向遭遇奖励上限（G）',
    description: '航行正向遭遇的金币奖励范围上限（G）',
    defaultValue: '150',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.travel.encounter_penalty_min': {
    configKey: 'game.travel.encounter_penalty_min',
    valueType: 'number',
    category: 'game',
    subCategory: 'travel',
    displayName: '玩法-航行负向遭遇损失下限（G）',
    description: '航行负向遭遇的金币损失范围下限（G）',
    defaultValue: '20',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.travel.encounter_penalty_max': {
    configKey: 'game.travel.encounter_penalty_max',
    valueType: 'number',
    category: 'game',
    subCategory: 'travel',
    displayName: '玩法-航行负向遭遇损失上限（G）',
    description: '航行负向遭遇的金币损失范围上限（G）',
    defaultValue: '80',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.world.tile_weights': {
    configKey: 'game.world.tile_weights',
    valueType: 'json',
    category: 'game',
    subCategory: 'world',
    displayName: '玩法-星图 Tile 权重（JSON）',
    description: '程序化生成星图 tile 的权重分布（VOID/ASTEROID/NEBULA/ANOMALY），会归一化',
    defaultValue: JSON.stringify({ VOID: 0.6, ASTEROID: 0.2, NEBULA: 0.15, ANOMALY: 0.05 }),
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.world.revisit_event_multiplier': {
    configKey: 'game.world.revisit_event_multiplier',
    valueType: 'number',
    category: 'game',
    subCategory: 'world',
    displayName: '玩法-复访事件概率倍率',
    description: '复访同一坐标时，对正/负/风味事件概率的倍率（0~1）',
    defaultValue: '0.5',
    minValue: '0',
    maxValue: '1',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.world.revisit_anomaly_drop_enabled': {
    configKey: 'game.world.revisit_anomaly_drop_enabled',
    valueType: 'boolean',
    category: 'game',
    subCategory: 'world',
    displayName: '玩法-复访异常掉落开关',
    description: '复访 ANOMALY 是否允许再次掉落信号碎片',
    defaultValue: 'false',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.world.event.positive_chance': {
    configKey: 'game.world.event.positive_chance',
    valueType: 'number',
    category: 'game',
    subCategory: 'world',
    displayName: '玩法-星图正向事件概率',
    description: '星图移动触发正向事件概率（0~1）',
    defaultValue: '0.1',
    minValue: '0',
    maxValue: '1',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.world.event.negative_chance': {
    configKey: 'game.world.event.negative_chance',
    valueType: 'number',
    category: 'game',
    subCategory: 'world',
    displayName: '玩法-星图负向事件概率',
    description: '星图移动触发负向事件概率（0~1）',
    defaultValue: '0.15',
    minValue: '0',
    maxValue: '1',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.world.event.flavor_chance': {
    configKey: 'game.world.event.flavor_chance',
    valueType: 'number',
    category: 'game',
    subCategory: 'world',
    displayName: '玩法-星图风味事件概率',
    description: '星图移动触发风味文本事件概率（0~1）',
    defaultValue: '0.05',
    minValue: '0',
    maxValue: '1',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.world.event.gain_min': {
    configKey: 'game.world.event.gain_min',
    valueType: 'number',
    category: 'game',
    subCategory: 'world',
    displayName: '玩法-星图正向事件收益下限（G）',
    description: '正向事件金币收益随机范围下限（G）',
    defaultValue: '50',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.world.event.gain_max': {
    configKey: 'game.world.event.gain_max',
    valueType: 'number',
    category: 'game',
    subCategory: 'world',
    displayName: '玩法-星图正向事件收益上限（G）',
    description: '正向事件金币收益随机范围上限（G）',
    defaultValue: '150',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.world.event.loss_min': {
    configKey: 'game.world.event.loss_min',
    valueType: 'number',
    category: 'game',
    subCategory: 'world',
    displayName: '玩法-星图负向事件损失下限（G）',
    description: '负向事件金币损失随机范围下限（G）',
    defaultValue: '20',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.world.event.loss_max': {
    configKey: 'game.world.event.loss_max',
    valueType: 'number',
    category: 'game',
    subCategory: 'world',
    displayName: '玩法-星图负向事件损失上限（G）',
    description: '负向事件金币损失随机范围上限（G）',
    defaultValue: '80',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.world.anomaly.fragment_min': {
    configKey: 'game.world.anomaly.fragment_min',
    valueType: 'number',
    category: 'game',
    subCategory: 'world',
    displayName: '玩法-异常掉落碎片下限',
    description: '进入 ANOMALY 时信号碎片掉落数量下限（含）',
    defaultValue: '1',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.world.anomaly.fragment_max': {
    configKey: 'game.world.anomaly.fragment_max',
    valueType: 'number',
    category: 'game',
    subCategory: 'world',
    displayName: '玩法-异常掉落碎片上限',
    description: '进入 ANOMALY 时信号碎片掉落数量上限（含）',
    defaultValue: '2',
    minValue: '0',
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.dungeon.defs': {
    configKey: 'game.dungeon.defs',
    valueType: 'json',
    category: 'game',
    subCategory: 'dungeon',
    displayName: '玩法-地牢定义覆盖（JSON）',
    description: '用于覆盖/新增地牢定义（id/name/description/difficulty_level/max_depth/entry_cost）',
    defaultValue: '[]',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.dungeon.loot_tables': {
    configKey: 'game.dungeon.loot_tables',
    valueType: 'json',
    category: 'game',
    subCategory: 'dungeon',
    displayName: '玩法-地牢掉落表（JSON）',
    description: '按 dungeon_id 或 default 配置 SEARCH/COMPLETE 掉落：good_id/chance/min/max；good_id=money 表示金币',
    defaultValue: JSON.stringify({
      default: {
        SEARCH: [{ good_id: 'signal_fragment', chance: 0.2, min: 1, max: 3 }],
        COMPLETE: [{ good_id: 'signal_fragment', chance: 1, min: 2, max: 5 }]
      }
    }),
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.dungeon.action_costs': {
    configKey: 'game.dungeon.action_costs',
    valueType: 'json',
    category: 'game',
    subCategory: 'dungeon',
    displayName: '玩法-地牢行动成本曲线（JSON）',
    description: '在 AI 返回的基础上叠加的额外消耗：base_*、move/search/attack_*、difficulty_*、depth_*',
    defaultValue: JSON.stringify({
      base_sanity_cost: 5,
      base_health_cost: 0,
      move_sanity_cost: 2,
      move_health_cost: 0,
      search_sanity_cost: 1,
      search_health_cost: 0,
      attack_sanity_cost: 2,
      attack_health_cost: 1,
      difficulty_sanity_per_level: 1,
      difficulty_health_per_level: 0,
      depth_sanity_per_depth: 0,
      depth_health_per_depth: 0
    }),
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.dungeon.completion_rewards': {
    configKey: 'game.dungeon.completion_rewards',
    valueType: 'json',
    category: 'game',
    subCategory: 'dungeon',
    displayName: '玩法-地牢通关成长奖励（JSON）',
    description: '通关奖励：money_per_difficulty 与 contribution_per_difficulty（会计入赛季进度与个人贡献）',
    defaultValue: JSON.stringify({
      money_per_difficulty: 0,
      contribution_per_difficulty: 0
    }),
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ai.timeout_ms': {
    configKey: 'game.ai.timeout_ms',
    valueType: 'number',
    category: 'game',
    subCategory: 'ai',
    displayName: '玩法-AI 超时（毫秒）',
    description: '调用大模型的超时时间（毫秒），超时将降级为本地文案',
    defaultValue: '8000',
    minValue: '1000',
    maxValue: '60000',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ai.model': {
    configKey: 'game.ai.model',
    valueType: 'string',
    category: 'game',
    subCategory: 'ai',
    displayName: '玩法-AI 模型名',
    description: 'Qwen compatible-mode chat/completions 的 model 字段',
    defaultValue: 'qwen-turbo',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ai.npc.rate_limit_window_seconds': {
    configKey: 'game.ai.npc.rate_limit_window_seconds',
    valueType: 'number',
    category: 'game',
    subCategory: 'ai',
    displayName: '玩法-NPC 交互限频窗口（秒）',
    description: 'NPC 对话按玩家限频的时间窗口（秒）',
    defaultValue: '30',
    minValue: '1',
    maxValue: '3600',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ai.npc.rate_limit_max': {
    configKey: 'game.ai.npc.rate_limit_max',
    valueType: 'number',
    category: 'game',
    subCategory: 'ai',
    displayName: '玩法-NPC 交互限频次数',
    description: '每个限频窗口允许的最大 NPC 交互次数',
    defaultValue: '6',
    minValue: '1',
    maxValue: '1000',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ai.npc.max_memory_messages': {
    configKey: 'game.ai.npc.max_memory_messages',
    valueType: 'number',
    category: 'game',
    subCategory: 'ai',
    displayName: '玩法-NPC 记忆回放条数',
    description: '构建提示词时使用的最近对话条数（0 表示不带历史）',
    defaultValue: '5',
    minValue: '0',
    maxValue: '50',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ai.npc.max_prompt_chars': {
    configKey: 'game.ai.npc.max_prompt_chars',
    valueType: 'number',
    category: 'game',
    subCategory: 'ai',
    displayName: '玩法-NPC 提示词最大字符数',
    description: '提示词长度上限，用于成本控制与稳定性',
    defaultValue: '3500',
    minValue: '500',
    maxValue: '20000',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ai.npc.max_response_chars': {
    configKey: 'game.ai.npc.max_response_chars',
    valueType: 'number',
    category: 'game',
    subCategory: 'ai',
    displayName: '玩法-NPC 回复最大字符数',
    description: 'NPC 回复文本最大字符数',
    defaultValue: '800',
    minValue: '50',
    maxValue: '5000',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ai.npc.max_store_interactions': {
    configKey: 'game.ai.npc.max_store_interactions',
    valueType: 'number',
    category: 'game',
    subCategory: 'ai',
    displayName: '玩法-NPC 记忆保留条数',
    description: '每个玩家- NPC 组合保留的对话条数上限（0 表示不清理）',
    defaultValue: '200',
    minValue: '0',
    maxValue: '10000',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ship.require_nft': {
    configKey: 'game.ship.require_nft',
    valueType: 'number',
    category: 'game',
    subCategory: 'core',
    displayName: '玩法-进入游戏需要基础货船 NFT',
    description: '1=需要；0=不需要（应急开关）',
    defaultValue: '1',
    minValue: '0',
    maxValue: '1',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ship.collection': {
    configKey: 'game.ship.collection',
    valueType: 'string',
    category: 'game',
    subCategory: 'core',
    displayName: '玩法-基础货船 Collection 地址',
    description: '用于 DAS 持有校验（Core 标准 Collection 地址）',
    defaultValue: '',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ship.core.candy_machine': {
    configKey: 'game.ship.core.candy_machine',
    valueType: 'string',
    category: 'game',
    subCategory: 'core',
    displayName: '玩法-基础货船 Core Candy Machine 地址',
    description: '用于链上铸造（MPL Core Candy Machine 地址）',
    defaultValue: '',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ship.core.candy_guard_address': {
    configKey: 'game.ship.core.candy_guard_address',
    valueType: 'string',
    category: 'game',
    subCategory: 'core',
    displayName: '玩法-基础货船 Candy Guard 地址',
    description: 'wrap-guards 成功后写入（CandyGuard PDA，Base58）',
    defaultValue: '',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ship.core.wrap_signature': {
    configKey: 'game.ship.core.wrap_signature',
    valueType: 'string',
    category: 'game',
    subCategory: 'core',
    displayName: '玩法-基础货船 Wrap 交易签名',
    description: 'wrap-guards 成功交易签名（Base58）',
    defaultValue: '',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ship.mint.recipient': {
    configKey: 'game.ship.mint.recipient',
    valueType: 'string',
    category: 'game',
    subCategory: 'mint',
    displayName: '玩法-基础货船 Mint 收款地址',
    description: 'SolPayment 收款地址（Base58）',
    defaultValue: '',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ship.mint.price_lamports': {
    configKey: 'game.ship.mint.price_lamports',
    valueType: 'number',
    category: 'game',
    subCategory: 'mint',
    displayName: '玩法-基础货船 Mint 价格（lamports）',
    description: '默认 0.05 SOL = 50,000,000 lamports',
    defaultValue: '50000000',
    minValue: '0',
    maxValue: '1000000000000',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ship.mint.bot_tax_lamports': {
    configKey: 'game.ship.mint.bot_tax_lamports',
    valueType: 'number',
    category: 'game',
    subCategory: 'mint',
    displayName: '玩法-基础货船 Bot Tax（lamports）',
    description: '默认 0.01 SOL = 10,000,000 lamports',
    defaultValue: '10000000',
    minValue: '0',
    maxValue: '1000000000000',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ship.mint.start_at': {
    configKey: 'game.ship.mint.start_at',
    valueType: 'number',
    category: 'game',
    subCategory: 'mint',
    displayName: '玩法-基础货船 Mint 开始时间（epoch 秒）',
    description: 'StartDate（UTC，精确到秒）。0 表示不限制。',
    defaultValue: '0',
    minValue: '0',
    maxValue: '4102444800',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ship.mint.enabled': {
    configKey: 'game.ship.mint.enabled',
    valueType: 'number',
    category: 'game',
    subCategory: 'mint',
    displayName: '玩法-基础货船 Mint 开关',
    description: '1=开放用户领取；0=关闭（发售前准备阶段建议关闭）',
    defaultValue: '0',
    minValue: '0',
    maxValue: '1',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ship.soulbound.mode': {
    configKey: 'game.ship.soulbound.mode',
    valueType: 'string',
    category: 'game',
    subCategory: 'core',
    displayName: '玩法-基础货船 不可转赠模式',
    description: '例如 core_oracle / core_permanent_freeze / server_record（用于前端提示与运营标识）',
    defaultValue: 'server_record',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ship.metadata.teaser_uri': {
    configKey: 'game.ship.metadata.teaser_uri',
    valueType: 'string',
    category: 'game',
    subCategory: 'metadata',
    displayName: '玩法-基础货船 盲盒 teaser URI',
    description: 'Reveal 关闭或无可用真实 URI 时使用',
    defaultValue: '',
    minValue: null,
    maxValue: null,
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ship.metadata.reveal_mode': {
    configKey: 'game.ship.metadata.reveal_mode',
    valueType: 'number',
    category: 'game',
    subCategory: 'metadata',
    displayName: '玩法-基础货船 Reveal 模式',
    description: '1=Mint 时分配真实 URI；0=始终使用 teaser',
    defaultValue: '0',
    minValue: '0',
    maxValue: '1',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ai.npc.summary_every': {
    configKey: 'game.ai.npc.summary_every',
    valueType: 'number',
    category: 'game',
    subCategory: 'ai',
    displayName: '玩法-NPC 记忆摘要频率',
    description: '每 N 次交互刷新一次摘要',
    defaultValue: '10',
    minValue: '1',
    maxValue: '1000000',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
  'game.ai.npc.summary_max_chars': {
    configKey: 'game.ai.npc.summary_max_chars',
    valueType: 'number',
    category: 'game',
    subCategory: 'ai',
    displayName: '玩法-NPC 记忆摘要长度',
    description: '摘要最大字符数',
    defaultValue: '800',
    minValue: '100',
    maxValue: '5000',
    options: null,
    isSensitive: false,
    requiresRestart: false,
    isActive: true,
    updatedBy: null,
  },
}

/**
 * 获取所有配置（支持分类筛选）
 */
export async function getAllConfigs(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const category = url.searchParams.get('category')

  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    let query = 'SELECT * FROM app_config WHERE is_active = 1'
    const params: any[] = []

    if (category) {
      query += ' AND category = ?'
      params.push(category)
    }

    query += ' ORDER BY category, sub_category, config_key'

    const result = await env.DB.prepare(query).bind(...params).all()

    const existingKeys = new Set<string>()
    // 按分类分组
    const grouped: Record<string, ConfigItem[]> = {}
    
    for (const row of (result.results || [])) {
      const config: ConfigItem = {
        id: row.id,
        configKey: row.config_key,
        configValue: row.is_sensitive ? '********' : row.config_value,
        valueType: row.value_type,
        category: row.category,
        subCategory: row.sub_category,
        displayName: row.display_name,
        description: row.description,
        defaultValue: row.default_value,
        minValue: row.min_value,
        maxValue: row.max_value,
        options: row.options,
        isSensitive: !!row.is_sensitive,
        requiresRestart: !!row.requires_restart,
        isActive: !!row.is_active,
        updatedBy: row.updated_by,
        createdAt: new Date(row.created_at * 1000).toISOString(),
        updatedAt: new Date(row.updated_at * 1000).toISOString(),
      }
      existingKeys.add(config.configKey)

      if (!grouped[config.category]) {
        grouped[config.category] = []
      }
      grouped[config.category].push(config)
    }

    for (const [key, def] of Object.entries(BUILTIN_CONFIG_DEFS)) {
      if (existingKeys.has(key)) continue
      const nowIso = new Date().toISOString()
      const item: ConfigItem = {
        id: 0,
        configKey: def.configKey,
        configValue: def.defaultValue || '',
        valueType: def.valueType,
        category: def.category,
        subCategory: def.subCategory,
        displayName: def.displayName,
        description: def.description,
        defaultValue: def.defaultValue,
        minValue: def.minValue,
        maxValue: def.maxValue,
        options: def.options,
        isSensitive: def.isSensitive,
        requiresRestart: def.requiresRestart,
        isActive: def.isActive,
        updatedBy: def.updatedBy,
        createdAt: nowIso,
        updatedAt: nowIso,
      }
      if (!grouped[item.category]) grouped[item.category] = []
      grouped[item.category].push(item)
    }

    return jsonResponse({ configs: grouped, total: result.results?.length || 0 })
  } catch (error) {
    console.error('Error getting configs:', error)
    return jsonResponse({ error: 'Failed to get configs' }, 500)
  }
}

/**
 * 获取单个配置
 */
export async function getConfig(
  request: Request,
  env: any,
  adminContext: AdminContext,
  key: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const result = await env.DB.prepare(
      'SELECT * FROM app_config WHERE config_key = ?'
    ).bind(key).first()

    if (!result) {
      const def = BUILTIN_CONFIG_DEFS[key]
      if (!def) {
        return jsonResponse({ error: 'Config not found' }, 404)
      }
      return jsonResponse({
        id: 0,
        configKey: def.configKey,
        configValue: def.defaultValue || '',
        valueType: def.valueType,
        category: def.category,
        subCategory: def.subCategory,
        displayName: def.displayName,
        description: def.description,
        defaultValue: def.defaultValue,
        isSensitive: def.isSensitive,
        requiresRestart: def.requiresRestart,
        updatedAt: new Date().toISOString(),
      })
    }

    return jsonResponse({
      id: result.id,
      configKey: result.config_key,
      configValue: result.is_sensitive ? '********' : result.config_value,
      valueType: result.value_type,
      category: result.category,
      subCategory: result.sub_category,
      displayName: result.display_name,
      description: result.description,
      defaultValue: result.default_value,
      isSensitive: !!result.is_sensitive,
      requiresRestart: !!result.requires_restart,
      updatedAt: new Date(result.updated_at * 1000).toISOString(),
    })
  } catch (error) {
    console.error('Error getting config:', error)
    return jsonResponse({ error: 'Failed to get config' }, 500)
  }
}

/**
 * 更新单个配置
 */
export async function updateConfig(
  request: Request,
  env: any,
  adminContext: AdminContext,
  key: string
): Promise<Response> {
  try {
    const body = await request.json() as { value: string; reason?: string }
    const now = Math.floor(Date.now() / 1000)

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    // 获取旧值
    const existing = await env.DB.prepare(
      'SELECT config_value, value_type, min_value, max_value FROM app_config WHERE config_key = ?'
    ).bind(key).first()

    if (!existing) {
      const def = BUILTIN_CONFIG_DEFS[key]
      if (!def) {
        return jsonResponse({ error: 'Config not found' }, 404)
      }

      if (def.valueType === 'number') {
        const numValue = parseFloat(body.value)
        if (isNaN(numValue)) {
          return jsonResponse({ error: 'Invalid number value' }, 400)
        }
      }
      if (def.valueType === 'json') {
        try {
          JSON.parse(body.value)
        } catch {
          return jsonResponse({ error: 'Invalid JSON value' }, 400)
        }
      }

      await env.DB.prepare(
        `INSERT INTO app_config (config_key, config_value, value_type, category, sub_category, display_name, description, default_value,
          min_value, max_value, options, is_sensitive, requires_restart, is_active, updated_by, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      ).bind(
        def.configKey,
        body.value,
        def.valueType,
        def.category,
        def.subCategory,
        def.displayName,
        def.description,
        def.defaultValue,
        def.minValue,
        def.maxValue,
        def.options,
        def.isSensitive ? 1 : 0,
        def.requiresRestart ? 1 : 0,
        def.isActive ? 1 : 0,
        adminContext.email,
        now,
        now
      ).run()

      await env.DB.prepare(
        `INSERT INTO config_change_history (config_key, old_value, new_value, changed_by, change_reason, ip_address)
         VALUES (?, ?, ?, ?, ?, ?)`
      ).bind(
        key,
        null,
        body.value,
        adminContext.email,
        body.reason || '创建并更新配置',
        adminContext.ip
      ).run()

      if (env.KV) {
        await env.KV.put(`config:${key}`, body.value)
      }

      await logAdminAction(env, adminContext, 'CREATE_CONFIG', 'config', key, `创建并更新值为: ${body.value}`)

      return jsonResponse({
        success: true,
        message: 'Config created and updated successfully',
        requiresRestart: def.requiresRestart
      })
    }

    // 验证数值范围
    if (existing.value_type === 'number') {
      const numValue = parseFloat(body.value)
      if (isNaN(numValue)) {
        return jsonResponse({ error: 'Invalid number value' }, 400)
      }
      if (existing.min_value && numValue < parseFloat(existing.min_value)) {
        return jsonResponse({ error: `Value must be >= ${existing.min_value}` }, 400)
      }
      if (existing.max_value && numValue > parseFloat(existing.max_value)) {
        return jsonResponse({ error: `Value must be <= ${existing.max_value}` }, 400)
      }
    }

    // 验证 JSON 格式
    if (existing.value_type === 'json') {
      try {
        JSON.parse(body.value)
      } catch {
        return jsonResponse({ error: 'Invalid JSON value' }, 400)
      }
    }

    // 记录历史
    await env.DB.prepare(
      `INSERT INTO config_change_history (config_key, old_value, new_value, changed_by, change_reason, ip_address)
       VALUES (?, ?, ?, ?, ?, ?)`
    ).bind(
      key,
      existing.config_value,
      body.value,
      adminContext.email,
      body.reason || null,
      adminContext.ip
    ).run()

    // 更新配置
    await env.DB.prepare(
      'UPDATE app_config SET config_value = ?, updated_by = ?, updated_at = ? WHERE config_key = ?'
    ).bind(body.value, adminContext.email, now, key).run()

    // 同步到 KV（用于快速读取）
    if (env.KV) {
      // 如果是关键配置，生成签名
      let storedValue = body.value;
      if (['blockchain.recipient_wallet', 'blockchain.subscription_program_id'].includes(key)) {
          const signature = await signConfigValue(env, key, body.value);
          if (signature) {
              storedValue = JSON.stringify({
                  value: body.value,
                  signature: signature,
                  timestamp: Date.now()
              });
          }
      }
      await env.KV.put(`config:${key}`, storedValue)
    }

    await logAdminAction(env, adminContext, 'UPDATE_CONFIG', 'config', key, 
      `更新值: ${existing.config_value} -> ${body.value}${body.reason ? `, 原因: ${body.reason}` : ''}`)

    return jsonResponse({ 
      success: true, 
      message: 'Config updated successfully',
      requiresRestart: !!existing.requires_restart
    })
  } catch (error) {
    console.error('Error updating config:', error)
    return jsonResponse({ error: 'Failed to update config' }, 500)
  }
}

/**
 * 批量更新配置
 */
export async function batchUpdateConfigs(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as { 
      updates: Array<{ key: string; value: string }>
      reason?: string 
    }
    const now = Math.floor(Date.now() / 1000)

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const results: Array<{ key: string; success: boolean; error?: string }> = []

    for (const update of body.updates) {
      try {
        // 获取旧值
        const existing = await env.DB.prepare(
          'SELECT config_value FROM app_config WHERE config_key = ?'
        ).bind(update.key).first()

        if (!existing) {
          results.push({ key: update.key, success: false, error: 'Not found' })
          continue
        }

        // 记录历史
        await env.DB.prepare(
          `INSERT INTO config_change_history (config_key, old_value, new_value, changed_by, change_reason, ip_address)
           VALUES (?, ?, ?, ?, ?, ?)`
        ).bind(update.key, existing.config_value, update.value, adminContext.email, body.reason || null, adminContext.ip).run()

        // 更新配置
        await env.DB.prepare(
          'UPDATE app_config SET config_value = ?, updated_by = ?, updated_at = ? WHERE config_key = ?'
        ).bind(update.value, adminContext.email, now, update.key).run()

        // 同步到 KV
        if (env.KV) {
          await env.KV.put(`config:${update.key}`, update.value)
        }

        results.push({ key: update.key, success: true })
      } catch (e) {
        results.push({ key: update.key, success: false, error: (e as Error).message })
      }
    }

    await logAdminAction(env, adminContext, 'BATCH_UPDATE_CONFIG', 'config', 'batch', 
      `批量更新 ${body.updates.length} 个配置`)

    return jsonResponse({ success: true, results })
  } catch (error) {
    console.error('Error batch updating configs:', error)
    return jsonResponse({ error: 'Failed to batch update configs' }, 500)
  }
}

/**
 * 获取配置变更历史
 */
export async function getConfigHistory(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const configKey = url.searchParams.get('key')
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '20')

  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    let whereClause = '1=1'
    const params: any[] = []

    if (configKey) {
      whereClause += ' AND config_key = ?'
      params.push(configKey)
    }

    const countResult = await env.DB.prepare(
      `SELECT COUNT(*) as count FROM config_change_history WHERE ${whereClause}`
    ).bind(...params).first()
    const total = countResult?.count || 0

    const offset = (page - 1) * pageSize
    const result = await env.DB.prepare(
      `SELECT * FROM config_change_history WHERE ${whereClause} ORDER BY created_at DESC LIMIT ? OFFSET ?`
    ).bind(...params, pageSize, offset).all()

    const history = (result.results || []).map((row: any) => ({
      id: row.id,
      configKey: row.config_key,
      oldValue: row.old_value,
      newValue: row.new_value,
      changedBy: row.changed_by,
      changeReason: row.change_reason,
      ipAddress: row.ip_address,
      createdAt: new Date(row.created_at * 1000).toISOString(),
    }))

    return jsonResponse({ history, total, page, pageSize })
  } catch (error) {
    console.error('Error getting config history:', error)
    return jsonResponse({ error: 'Failed to get config history' }, 500)
  }
}

/**
 * 导出配置
 */
export async function exportConfigs(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const result = await env.DB.prepare(
      'SELECT config_key, config_value, value_type, category, sub_category, display_name, description FROM app_config WHERE is_active = 1 ORDER BY category, config_key'
    ).all()

    await logAdminAction(env, adminContext, 'EXPORT_CONFIG', 'config', 'all', '导出所有配置')

    return jsonResponse({
      success: true,
      exportedAt: new Date().toISOString(),
      count: result.results?.length || 0,
      configs: result.results || []
    })
  } catch (error) {
    console.error('Error exporting configs:', error)
    return jsonResponse({ error: 'Failed to export configs' }, 500)
  }
}

/**
 * 导入配置
 */
export async function importConfigs(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as { 
      configs: Array<{ config_key: string; config_value: string }>
      overwrite?: boolean 
    }
    const now = Math.floor(Date.now() / 1000)

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    let imported = 0
    let skipped = 0

    for (const config of body.configs) {
      const existing = await env.DB.prepare(
        'SELECT id FROM app_config WHERE config_key = ?'
      ).bind(config.config_key).first()

      if (existing && !body.overwrite) {
        skipped++
        continue
      }

      if (existing) {
        await env.DB.prepare(
          'UPDATE app_config SET config_value = ?, updated_by = ?, updated_at = ? WHERE config_key = ?'
        ).bind(config.config_value, adminContext.email, now, config.config_key).run()
      }
      // Note: 不创建新配置，只更新已存在的

      imported++
    }

    await logAdminAction(env, adminContext, 'IMPORT_CONFIG', 'config', 'batch', 
      `导入配置: ${imported} 成功, ${skipped} 跳过`)

    return jsonResponse({ success: true, imported, skipped })
  } catch (error) {
    console.error('Error importing configs:', error)
    return jsonResponse({ error: 'Failed to import configs' }, 500)
  }
}

/**
 * 重置配置为默认值
 */
export async function resetConfig(
  request: Request,
  env: any,
  adminContext: AdminContext,
  key: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const existing = await env.DB.prepare(
      'SELECT config_value, default_value FROM app_config WHERE config_key = ?'
    ).bind(key).first()

    if (!existing) {
      return jsonResponse({ error: 'Config not found' }, 404)
    }

    if (!existing.default_value) {
      return jsonResponse({ error: 'No default value defined' }, 400)
    }

    const now = Math.floor(Date.now() / 1000)

    // 记录历史
    await env.DB.prepare(
      `INSERT INTO config_change_history (config_key, old_value, new_value, changed_by, change_reason, ip_address)
       VALUES (?, ?, ?, ?, ?, ?)`
    ).bind(key, existing.config_value, existing.default_value, adminContext.email, '重置为默认值', adminContext.ip).run()

    // 更新配置
    await env.DB.prepare(
      'UPDATE app_config SET config_value = default_value, updated_by = ?, updated_at = ? WHERE config_key = ?'
    ).bind(adminContext.email, now, key).run()

    // 同步到 KV
    if (env.KV) {
      await env.KV.put(`config:${key}`, existing.default_value)
    }

    await logAdminAction(env, adminContext, 'RESET_CONFIG', 'config', key, '重置为默认值')

    return jsonResponse({ success: true, defaultValue: existing.default_value })
  } catch (error) {
    console.error('Error resetting config:', error)
    return jsonResponse({ error: 'Failed to reset config' }, 500)
  }
}

/**
 * 处理配置路由
 */
export async function handleAppConfigRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string
): Promise<Response | null> {
  // GET /admin/app-config - 获取所有配置
  if (request.method === 'GET' && path === '/admin/app-config') {
    return getAllConfigs(request, env, adminContext)
  }

  // GET /admin/app-config/rollout/preview - 灰度预览
  if (request.method === 'GET' && path === '/admin/app-config/rollout/preview') {
    return previewRollouts(request, env, adminContext)
  }

  // GET /admin/app-config/key/:key - 获取单个配置
  const keyMatch = path.match(/^\/admin\/app-config\/key\/([^/]+)$/)
  if (request.method === 'GET' && keyMatch) {
    return getConfig(request, env, adminContext, keyMatch[1])
  }

  // PUT /admin/app-config/key/:key - 更新单个配置
  if (request.method === 'PUT' && keyMatch) {
    return updateConfig(request, env, adminContext, keyMatch[1])
  }

  // POST /admin/app-config/key/:key/reset - 重置配置
  const resetMatch = path.match(/^\/admin\/app-config\/key\/([^/]+)\/reset$/)
  if (request.method === 'POST' && resetMatch) {
    return resetConfig(request, env, adminContext, resetMatch[1])
  }

  // PUT /admin/app-config/batch - 批量更新
  if (request.method === 'PUT' && path === '/admin/app-config/batch') {
    return batchUpdateConfigs(request, env, adminContext)
  }

  // GET /admin/app-config/history - 配置历史
  if (request.method === 'GET' && path === '/admin/app-config/history') {
    return getConfigHistory(request, env, adminContext)
  }

  // POST /admin/app-config/export - 导出配置
  if (request.method === 'POST' && path === '/admin/app-config/export') {
    return exportConfigs(request, env, adminContext)
  }

  // POST /admin/app-config/import - 导入配置
  if (request.method === 'POST' && path === '/admin/app-config/import') {
    return importConfigs(request, env, adminContext)
  }

  return null
}

// 辅助函数
function jsonResponse(data: any, status: number = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}

function stableBucket(seed: string, salt: string): number {
  const s = `${seed}|${salt}`
  let h = 5381
  for (let i = 0; i < s.length; i++) {
    h = ((h << 5) + h) ^ s.charCodeAt(i)
  }
  return Math.abs(h) % 100
}

function parseRolloutValue(raw: string | null): any {
  if (!raw) return null
  try {
    const v = JSON.parse(raw)
    return (v && typeof v === 'object') ? v : null
  } catch {
    return null
  }
}

function pickVariant(bucket: number, variants: Record<string, any>): string | null {
  const entries = Object.entries(variants || {})
    .map(([k, w]) => [k, Number(w)] as [string, number])
    .filter(([, w]) => Number.isFinite(w) && w > 0)
  const total = entries.reduce((acc, [, w]) => acc + w, 0)
  if (total <= 0) return null
  const pct = (bucket / 100) * total
  let acc = 0
  for (const [k, w] of entries) {
    acc += w
    if (pct < acc) return k
  }
  return entries[entries.length - 1]?.[0] || null
}

async function previewRollouts(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) return jsonResponse({ records: [] })
    const url = new URL(request.url)
    const seed = (url.searchParams.get('seed') || '').trim()
    if (!seed) return jsonResponse({ error: 'missing_seed' }, 400)

    const keysParam = (url.searchParams.get('keys') || '').trim()
    const prefix = (url.searchParams.get('prefix') || '').trim()
    let keys: string[] = []
    if (keysParam) {
      keys = keysParam.split(',').map(s => s.trim()).filter(Boolean)
    }

    let rows: any[] = []
    if (keys.length > 0) {
      const placeholders = keys.map(() => '?').join(',')
      const q = `SELECT config_key as configKey, config_value as configValue FROM app_config WHERE config_key IN (${placeholders})`
      const res = await env.DB.prepare(q).bind(...keys).all()
      rows = res.results || []
    } else {
      const like = prefix ? `${prefix}%` : 'rollout.%'
      const res = await env.DB.prepare(
        `SELECT config_key as configKey, config_value as configValue
         FROM app_config
         WHERE config_key LIKE ? AND is_active = 1
         ORDER BY config_key ASC`
      ).bind(like).all()
      rows = res.results || []
    }

    const records = rows.map((r: any) => {
      const key = r.configKey
      const value = parseRolloutValue(r.configValue)
      const type = (value?.type || 'percent').toString()
      const salt = (value?.salt || key).toString()
      const bucket = stableBucket(seed, salt)

      if (type === 'ab') {
        const variant = pickVariant(bucket, value?.variants || {})
        return { key, type, bucket, variant, raw: value }
      }

      const percent = Number(value?.percent ?? (value?.enabled ? 100 : 0))
      const p = Number.isFinite(percent) ? Math.max(0, Math.min(100, percent)) : 0
      const enabled = bucket < p
      return { key, type: 'percent', bucket, enabled, percent: p, raw: value }
    })

    return jsonResponse({ seed, records })
  } catch (error) {
    console.error('Error previewing rollouts:', error)
    return jsonResponse({ error: 'Failed to preview rollouts' }, 500)
  }
}
