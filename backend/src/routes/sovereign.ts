/**
 * Sovereign Score API
 * 
 * 获取用户的 Sovereign 等级和分数
 */

import { Env, jsonResponse } from '../index';
import { getSovereignScore } from '../utils/solana';
import { getSolanaRpcUrl } from '../utils/solana-rpc';

// Sovereign 等级定义
const SOVEREIGN_LEVELS = [
  { name: 'Bronze', minScore: 0, multiplier: 1.0 },
  { name: 'Silver', minScore: 100, multiplier: 1.1 },
  { name: 'Gold', minScore: 500, multiplier: 1.2 },
  { name: 'Platinum', minScore: 2000, multiplier: 1.35 },
  { name: 'Diamond', minScore: 10000, multiplier: 1.5 },
];

export async function handleSovereign(
  request: Request,
  env: Env,
  path: string
): Promise<Response> {
  
  if (path === '/api/v1/sovereign/score' && request.method === 'GET') {
    return handleGetScore(request, env);
  }
  
  if (path === '/api/v1/sovereign/levels' && request.method === 'GET') {
    return handleGetLevels(request, env);
  }
  
  return jsonResponse({ error: 'Not Found' }, 404);
}

/**
 * 获取 Sovereign Score
 * 
 * GET /api/v1/sovereign/score?wallet=<address>
 */
async function handleGetScore(
  request: Request,
  env: Env
): Promise<Response> {
  try {
    const url = new URL(request.url);
    const walletAddress = url.searchParams.get('wallet');
    
    if (!walletAddress) {
      return jsonResponse({ error: 'wallet parameter is required' }, 400);
    }
    
    // 从链上获取 Sovereign Score
    const rpcUrl = getSolanaRpcUrl(env);
    const score = await getSovereignScore(walletAddress, rpcUrl);
    
    // 计算等级
    const level = SOVEREIGN_LEVELS.reduce((prev, curr) => {
      return score >= curr.minScore ? curr : prev;
    }, SOVEREIGN_LEVELS[0]);
    
    // 计算下一等级进度
    const currentIndex = SOVEREIGN_LEVELS.findIndex(l => l.name === level.name);
    const nextLevel = SOVEREIGN_LEVELS[currentIndex + 1];
    const pointsToNext = nextLevel ? nextLevel.minScore - score : 0;
    const progress = nextLevel 
      ? (score - level.minScore) / (nextLevel.minScore - level.minScore) * 100
      : 100;
    
    return jsonResponse({
      wallet_address: walletAddress,
      score,
      level: {
        name: level.name,
        multiplier: level.multiplier,
      },
      next_level: nextLevel ? {
        name: nextLevel.name,
        points_needed: pointsToNext,
        progress: Math.min(progress, 100).toFixed(1) + '%',
      } : null,
      benefits: getLevelBenefits(level.name),
    });
    
  } catch (error) {
    console.error('Get sovereign score error:', error);
    
    // 返回默认值而不是错误
    return jsonResponse({
      wallet_address: new URL(request.url).searchParams.get('wallet'),
      score: 0,
      level: {
        name: 'Bronze',
        multiplier: 1.0,
      },
      next_level: {
        name: 'Silver',
        points_needed: 100,
        progress: '0%',
      },
      benefits: getLevelBenefits('Bronze'),
      note: 'Using default values - chain data unavailable',
    });
  }
}

/**
 * 获取所有等级定义
 * 
 * GET /api/v1/sovereign/levels
 */
async function handleGetLevels(
  request: Request,
  env: Env
): Promise<Response> {
  return jsonResponse({
    levels: SOVEREIGN_LEVELS.map(level => ({
      name: level.name,
      min_score: level.minScore,
      multiplier: level.multiplier,
      benefits: getLevelBenefits(level.name),
    })),
  });
}

/**
 * 获取等级权益
 */
function getLevelBenefits(levelName: string): string[] {
  const benefits: Record<string, string[]> = {
    Bronze: [
      '基础 AI 对话',
      '每日 100 万 Token 限额',
    ],
    Silver: [
      '1.1x 积分加成',
      '优先客服支持',
      '每日 200 万 Token 限额',
    ],
    Gold: [
      '1.2x 积分加成',
      '专属徽章',
      '每日 500 万 Token 限额',
      '早期功能体验',
    ],
    Platinum: [
      '1.35x 积分加成',
      '专属 NFT',
      '每日 1000 万 Token 限额',
      'VIP 客服通道',
      '空投优先',
    ],
    Diamond: [
      '1.5x 积分加成',
      '无限 Token',
      '独家空投',
      '治理投票权',
      '线下活动邀请',
    ],
  };
  
  return benefits[levelName] || benefits.Bronze;
}
