
import { Env, jsonResponse } from '../index';
import { getUserAuth } from '../utils/user-auth';

/**
 * 后端代付上传到 Irys
 * 
 * 只有付费用户（current_tier > 1 或 subscription_type != 'FREE'）才允许使用此接口
 * 
 * POST /api/v1/memories/upload
 * Body: {
 *   memoryId: string,
 *   contentBase64: string,
 *   tags?: { name: string, value: string }[]
 * }
 */
export async function handleIrysUpload(request: Request, env: Env): Promise<Response> {
  if (request.method !== 'POST') {
    return jsonResponse({ error: 'Method not allowed' }, 405);
  }

  // 1. 鉴权
  const auth = await getUserAuth(request, env);
  if (!auth.ok) {
    return jsonResponse({ error: 'unauthorized', detail: auth.error }, 401);
  }
  const walletAddress = auth.walletAddress;

  // 2. 检查数据库连接
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    // 3. 检查用户付费状态 (Tier > 1 或 订阅类型不是 FREE)
    const user = await env.DB.prepare(
      `SELECT current_tier, subscription_type FROM users WHERE wallet_address = ?`
    ).bind(walletAddress).first();

    if (!user) {
      return jsonResponse({ error: 'User not found' }, 404);
    }

    const currentTier = (user.current_tier as number) || 1;
    const subscriptionType = (user.subscription_type as string) || 'FREE';
    
    // 付费用户判断逻辑
    const isPaidUser = currentTier > 1 || subscriptionType !== 'FREE';

    if (!isPaidUser) {
      // 非付费用户：拒绝上传到 Irys，建议仅存储在后端 Blob (R2/KV)
      // 客户端应在收到此错误后，降级为调用 /api/v1/memories/blob
      return jsonResponse({ 
        success: false, 
        error: 'payment_required', 
        message: 'Only paid users can upload to permanent storage (Irys). Free users should use server storage.',
        isPaidUser: false
      }, 403);
    }

    // 4. 解析请求体
    const body = await request.json() as {
      memoryId: string;
      contentBase64: string;
      tags?: { name: string; value: string }[];
    };

    if (!body.memoryId || !body.contentBase64) {
      return jsonResponse({ error: 'Missing memoryId or contentBase64' }, 400);
    }

    return jsonResponse(
      {
        success: false,
        error: 'payment_required',
        message:
          'Permanent storage (Irys) is not available in this deployment. Please use server storage (/api/v1/memories/blob).',
        isPaidUser: false,
      },
      403
    );

  } catch (error) {
    console.error('Irys upload error:', error);
    return jsonResponse({ 
      success: false, 
      error: 'Upload failed', 
      message: (error as Error).message 
    }, 500);
  }
}
