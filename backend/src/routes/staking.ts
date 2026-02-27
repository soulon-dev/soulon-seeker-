/**
 * Staking API
 * 
 * 获取 Guardian 质押状态
 */

import { Env, jsonResponse } from '../index';
import { getStakingStatus } from '../utils/solana';
import { getSolanaRpcUrl } from '../utils/solana-rpc';

// 官方认证的 Guardian 节点
const CERTIFIED_GUARDIANS: Record<string, { name: string; description: string; apy: number }> = {
  'HeL1usGt7gpjy2uFgwvwGzFxBPn3RhKMSjzHf3SqGKZE': {
    name: 'Helius',
    description: '高性能 RPC 提供商',
    apy: 8.5,
  },
  'JitoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxV1': {
    name: 'Jito',
    description: 'MEV 优化验证者',
    apy: 9.2,
  },
  'SMobxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx': {
    name: 'Solana Mobile',
    description: '官方 Seeker 节点',
    apy: 7.8,
  },
};

export async function handleStaking(
  request: Request,
  env: Env,
  path: string
): Promise<Response> {
  
  if (path === '/api/v1/staking/status' && request.method === 'GET') {
    return handleGetStatus(request, env);
  }
  
  if (path === '/api/v1/staking/guardians' && request.method === 'GET') {
    return handleGetGuardians(request, env);
  }
  
  if (path === '/api/v1/staking/bonus' && request.method === 'GET') {
    return handleGetBonus(request, env);
  }
  
  return jsonResponse({ error: 'Not Found' }, 404);
}

/**
 * 获取质押状态
 * 
 * GET /api/v1/staking/status?wallet=<address>
 */
async function handleGetStatus(
  request: Request,
  env: Env
): Promise<Response> {
  try {
    const url = new URL(request.url);
    const walletAddress = url.searchParams.get('wallet');
    
    if (!walletAddress) {
      return jsonResponse({ error: 'wallet parameter is required' }, 400);
    }
    
    // 从链上获取质押状态
    const rpcUrl = getSolanaRpcUrl(env);
    const stakingData = await getStakingStatus(walletAddress, rpcUrl);
    
    // 检查是否质押给认证 Guardian
    const isCertified = stakingData.validatorAddress 
      ? CERTIFIED_GUARDIANS.hasOwnProperty(stakingData.validatorAddress)
      : false;
    
    const guardianInfo = stakingData.validatorAddress && isCertified
      ? CERTIFIED_GUARDIANS[stakingData.validatorAddress]
      : null;
    
    // 计算加成
    const bonus = calculateStakingBonus(stakingData.amount, isCertified);
    
    return jsonResponse({
      wallet_address: walletAddress,
      is_staking: stakingData.amount > 0,
      staked_amount: stakingData.amount,
      staked_amount_display: formatStakedAmount(stakingData.amount),
      validator_address: stakingData.validatorAddress,
      is_certified_guardian: isCertified,
      guardian: guardianInfo,
      bonus,
      staking_since: stakingData.activationEpoch,
    });
    
  } catch (error) {
    console.error('Get staking status error:', error);
    
    // 返回默认值
    return jsonResponse({
      wallet_address: new URL(request.url).searchParams.get('wallet'),
      is_staking: false,
      staked_amount: 0,
      staked_amount_display: '0 SKR',
      validator_address: null,
      is_certified_guardian: false,
      guardian: null,
      bonus: calculateStakingBonus(0, false),
      note: 'Using default values - chain data unavailable',
    });
  }
}

/**
 * 获取认证 Guardian 列表
 * 
 * GET /api/v1/staking/guardians
 */
async function handleGetGuardians(
  request: Request,
  env: Env
): Promise<Response> {
  const guardians = Object.entries(CERTIFIED_GUARDIANS).map(([address, info]) => ({
    address,
    ...info,
    bonus: {
      extra_memo_percent: 20,
      fee_discount: '10%',
      features: ['premium_skin', 'priority_queue', 'exclusive_airdrops'],
    },
  }));
  
  return jsonResponse({
    certified_guardians: guardians,
    total_count: guardians.length,
    benefits_description: '质押给认证 Guardian 可获得额外 20% 积分加成和 10% 手续费减免',
  });
}

/**
 * 获取质押加成计算
 * 
 * GET /api/v1/staking/bonus?amount=<lamports>&certified=<true|false>
 */
async function handleGetBonus(
  request: Request,
  env: Env
): Promise<Response> {
  const url = new URL(request.url);
  const amount = parseInt(url.searchParams.get('amount') || '0');
  const certified = url.searchParams.get('certified') === 'true';
  
  const bonus = calculateStakingBonus(amount, certified);
  
  return jsonResponse({
    input: {
      amount,
      certified,
    },
    bonus,
  });
}

/**
 * 计算质押加成
 */
function calculateStakingBonus(amount: number, isCertified: boolean): {
  extra_memo_percent: number;
  fee_discount: number;
  memo_multiplier: number;
  features: string[];
  description: string;
} {
  if (amount === 0) {
    return {
      extra_memo_percent: 0,
      fee_discount: 0,
      memo_multiplier: 1.0,
      features: [],
      description: '未质押',
    };
  }
  
  if (isCertified) {
    return {
      extra_memo_percent: 20,
      fee_discount: 0.1,
      memo_multiplier: 1.2,
      features: ['premium_skin', 'priority_queue', 'exclusive_airdrops'],
      description: '质押给认证 Guardian，享受完整加成',
    };
  }
  
  return {
    extra_memo_percent: 5,
    fee_discount: 0.02,
    memo_multiplier: 1.05,
    features: [],
    description: '质押给普通 Guardian',
  };
}

/**
 * 格式化质押金额
 */
function formatStakedAmount(lamports: number): string {
  const skr = lamports / 1_000_000_000;
  if (skr >= 1000000) {
    return (skr / 1000000).toFixed(2) + 'M SKR';
  }
  if (skr >= 1000) {
    return (skr / 1000).toFixed(2) + 'K SKR';
  }
  return skr.toFixed(2) + ' SKR';
}
