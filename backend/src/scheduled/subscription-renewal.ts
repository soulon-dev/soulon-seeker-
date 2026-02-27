/**
 * 自动续费定时任务
 * 每小时执行一次，检查并处理到期的自动续费订阅
 */

import { getPendingPayments, recordPaymentResult } from '../routes/admin/subscriptions'
import { getSolanaRpcUrl } from '../utils/solana-rpc'

// Solana 相关配置
const SUBSCRIPTION_PROGRAM_ID = 'SUBScripXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX'

interface Env {
  DB?: D1Database
  KV?: KVNamespace
  SOLANA_RPC_URL?: string
  SUBSCRIPTION_EXECUTOR_KEYPAIR?: string  // 执行器私钥（Base58 编码）
}

async function maybeApplyScheduledPlanSwitch(
  env: Env,
  subscription: {
    id: string
    walletAddress: string
    planType: number
    amountUsdc: number
    periodSeconds: number
    nextPaymentAt: number
  },
  now: number
): Promise<{ switched: boolean }> {
  if (!env.DB || !env.KV) return { switched: false }

  const switchKey = `autoRenew:switch:${subscription.walletAddress}`
  const raw = await env.KV.get(switchKey)
  if (!raw) return { switched: false }

  let state: any
  try {
    state = JSON.parse(raw)
  } catch {
    await env.KV.delete(switchKey)
    return { switched: false }
  }

  const effectiveAt = Number(state.effectiveAt || 0)
  if (!effectiveAt || effectiveAt > now) return { switched: false }

  const toPlanType = Number(state.toPlanType || 0)
  const toAmountUsdc = Number(state.toAmountUsdc || 0)
  const toPeriodSeconds = Number(state.toPeriodSeconds || 0)

  if (!toPlanType || !toAmountUsdc || !toPeriodSeconds) {
    await env.KV.delete(switchKey)
    return { switched: false }
  }

  await env.DB.prepare(
    `UPDATE auto_renew_subscriptions
     SET plan_type = ?, amount_usdc = ?, period_seconds = ?, updated_at = ?
     WHERE id = ?`
  ).bind(toPlanType, toAmountUsdc, toPeriodSeconds, now, subscription.id).run()

  subscription.planType = toPlanType
  subscription.amountUsdc = toAmountUsdc
  subscription.periodSeconds = toPeriodSeconds

  await env.KV.delete(switchKey)
  return { switched: true }
}

/**
 * 执行自动续费扣款
 * 调用链上智能合约执行代币转账
 */
async function executePayment(
  env: Env,
  subscription: {
    id: string
    walletAddress: string
    tokenAccountPda: string
    amountUsdc: number
  }
): Promise<{ success: boolean; transactionId?: string; error?: string }> {
  // 注意：实际执行需要配置执行器私钥
  // 这里先记录待执行状态，由外部服务（如 Cron Job 服务器）来实际执行
  
  try {
    const rpcUrl = getSolanaRpcUrl(env)
    
    // 检查订阅者账户是否有足够余额
    // 实际实现需要：
    // 1. 连接 Solana RPC
    // 2. 获取订阅者代币账户余额
    // 3. 构建并发送 executePayment 指令
    // 4. 等待交易确认
    
    // 由于 Cloudflare Workers 无法直接签名交易
    // 这里使用标记模式：将待处理订阅标记，由有签名能力的服务来执行
    
    console.log(`[Subscription Renewal] Marking payment for execution: ${subscription.id}`)
    console.log(`  - Wallet: ${subscription.walletAddress}`)
    console.log(`  - Amount: ${subscription.amountUsdc} USDC`)
    console.log(`  - PDA: ${subscription.tokenAccountPda}`)
    
    // 返回待处理状态
    return {
      success: true,
      transactionId: `pending_${subscription.id}_${Date.now()}`,
    }
  } catch (error: any) {
    console.error(`[Subscription Renewal] Error processing ${subscription.id}:`, error)
    return {
      success: false,
      error: error.message || 'Unknown error',
    }
  }
}

/**
 * 定时任务主入口
 */
export async function handleScheduledRenewal(env: Env): Promise<void> {
  console.log('[Subscription Renewal] Starting scheduled renewal check...')
  
  try {
    // 获取所有待执行的自动续费
    const pendingPayments = await getPendingPayments(env)
    console.log(`[Subscription Renewal] Found ${pendingPayments.length} pending payments`)
    
    if (pendingPayments.length === 0) {
      console.log('[Subscription Renewal] No pending payments, done.')
      return
    }

    // 处理每个待执行的续费
    let successCount = 0
    let failCount = 0
    
    for (const subscription of pendingPayments) {
      console.log(`[Subscription Renewal] Processing: ${subscription.id}`)
      const now = Math.floor(Date.now() / 1000)
      const switchResult = await maybeApplyScheduledPlanSwitch(env, subscription as any, now)
      
      const result = await executePayment(env, subscription)
      
      // 记录结果
      await recordPaymentResult(
        env,
        subscription.id,
        result.success,
        result.transactionId,
        result.error
      )

      if (switchResult.switched && env.KV) {
        await env.KV.delete(`autoRenew:cancelLock:${subscription.walletAddress}`)
      }
      
      if (result.success) {
        successCount++
        console.log(`[Subscription Renewal] ✅ Success: ${subscription.id}`)
      } else {
        failCount++
        console.log(`[Subscription Renewal] ❌ Failed: ${subscription.id} - ${result.error}`)
      }
      
      // 避免请求过快
      await new Promise(resolve => setTimeout(resolve, 100))
    }
    
    console.log(`[Subscription Renewal] Completed: ${successCount} success, ${failCount} failed`)
    
  } catch (error) {
    console.error('[Subscription Renewal] Fatal error:', error)
    throw error
  }
}

/**
 * 发送续费提醒（到期前 3 天）
 */
export async function sendRenewalReminders(env: Env): Promise<void> {
  console.log('[Subscription Reminder] Checking for upcoming renewals...')
  
  if (!env.DB) return
  
  const threeDaysLater = Math.floor(Date.now() / 1000) + (3 * 24 * 60 * 60)
  const oneDayLater = Math.floor(Date.now() / 1000) + (24 * 60 * 60)
  const now = Math.floor(Date.now() / 1000)
  
  // 获取即将到期的订阅（3 天内）
  const result = await env.DB.prepare(
    `SELECT ars.*, ft.fcm_token, ft.device_id
     FROM auto_renew_subscriptions ars
     LEFT JOIN fcm_tokens ft ON ars.wallet_address = ft.wallet_address
     WHERE ars.is_active = 1 
     AND ars.next_payment_at > ? 
     AND ars.next_payment_at <= ?`
  ).bind(now, threeDaysLater).all()
  
  const upcomingRenewals = result.results || []
  console.log(`[Subscription Reminder] Found ${upcomingRenewals.length} upcoming renewals`)
  
  for (const sub of upcomingRenewals) {
    const subscription = sub as any
    const daysUntilRenewal = Math.ceil((subscription.next_payment_at - now) / (24 * 60 * 60))
    
    // 检查是否已发送过提醒
    const reminderKey = `reminder_${subscription.id}_${daysUntilRenewal}`
    const alreadySent = await env.DB.prepare(
      `SELECT 1 FROM subscription_reminders WHERE reminder_key = ?`
    ).bind(reminderKey).first()
    
    if (alreadySent) {
      continue
    }
    
    // 确定提醒类型
    let reminderType: 'three_days' | 'one_day' | 'today'
    let title: string
    let body: string
    
    if (subscription.next_payment_at <= oneDayLater) {
      reminderType = 'today'
      title = '📢 订阅即将续费'
      body = `您的会员订阅将在今天自动续费 $${subscription.amount_usdc} USDC，请确保钱包余额充足`
    } else if (daysUntilRenewal <= 1) {
      reminderType = 'one_day'
      title = '⏰ 订阅续费提醒'
      body = `您的会员订阅将在 1 天内自动续费 $${subscription.amount_usdc} USDC`
    } else {
      reminderType = 'three_days'
      title = '💳 订阅续费提醒'
      body = `您的会员订阅将在 ${daysUntilRenewal} 天内自动续费 $${subscription.amount_usdc} USDC`
    }
    
    console.log(`[Subscription Reminder] Sending ${reminderType} reminder to: ${subscription.wallet_address}`)
    
    // 发送推送通知
    if (subscription.fcm_token) {
      try {
        await sendFcmNotification(env, subscription.fcm_token, {
          title,
          body,
          data: {
            type: 'subscription_reminder',
            subscriptionId: subscription.id,
            walletAddress: subscription.wallet_address,
            amountUsdc: String(subscription.amount_usdc),
            nextPaymentAt: String(subscription.next_payment_at),
          },
        })
        
        // 记录已发送
        await env.DB.prepare(
          `INSERT INTO subscription_reminders (reminder_key, subscription_id, reminder_type, sent_at)
           VALUES (?, ?, ?, ?)`
        ).bind(reminderKey, subscription.id, reminderType, now).run()
        
        console.log(`[Subscription Reminder] ✅ Sent to ${subscription.wallet_address}`)
      } catch (error) {
        console.error(`[Subscription Reminder] ❌ Failed to send to ${subscription.wallet_address}:`, error)
      }
    } else {
      console.log(`[Subscription Reminder] No FCM token for ${subscription.wallet_address}`)
    }
  }
}

/**
 * 发送 FCM 推送通知
 */
async function sendFcmNotification(
  env: Env,
  fcmToken: string,
  notification: {
    title: string
    body: string
    data?: Record<string, string>
  }
): Promise<void> {
  // 获取 FCM 服务器密钥
  const fcmServerKey = (env as any).FCM_SERVER_KEY
  
  if (!fcmServerKey) {
    console.warn('[FCM] FCM_SERVER_KEY not configured')
    return
  }
  
  const response = await fetch('https://fcm.googleapis.com/fcm/send', {
    method: 'POST',
    headers: {
      'Authorization': `key=${fcmServerKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      to: fcmToken,
      notification: {
        title: notification.title,
        body: notification.body,
        icon: 'ic_notification',
        sound: 'default',
      },
      data: notification.data || {},
      priority: 'high',
    }),
  })
  
  if (!response.ok) {
    const errorText = await response.text()
    throw new Error(`FCM error: ${response.status} - ${errorText}`)
  }
  
  const result = await response.json() as { success: number; failure: number }
  if (result.failure > 0) {
    console.warn('[FCM] Some messages failed to send')
  }
}
