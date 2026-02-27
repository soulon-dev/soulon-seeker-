/**
 * 加密工具函数
 */

import nacl from 'tweetnacl'
import { SignJWT, jwtVerify } from 'jose'

// Base58 字符表
const BASE58_ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';

/**
 * 生成随机 Challenge (32 字节)
 */
export function generateChallenge(): Uint8Array {
  const challenge = new Uint8Array(32);
  crypto.getRandomValues(challenge);
  return challenge;
}

/**
 * Base58 编码
 */
export function base58Encode(bytes: Uint8Array): string {
  if (bytes.length === 0) return '';
  
  // 计算前导零的数量
  let leadingZeros = 0;
  for (const byte of bytes) {
    if (byte === 0) leadingZeros++;
    else break;
  }
  
  // 转换为 BigInt
  let num = BigInt(0);
  for (const byte of bytes) {
    num = num * BigInt(256) + BigInt(byte);
  }
  
  // 转换为 Base58
  let result = '';
  while (num > BigInt(0)) {
    const remainder = Number(num % BigInt(58));
    num = num / BigInt(58);
    result = BASE58_ALPHABET[remainder] + result;
  }
  
  // 添加前导 '1'
  return '1'.repeat(leadingZeros) + result;
}

/**
 * Base58 解码
 */
export function base58Decode(str: string): Uint8Array {
  if (str.length === 0) return new Uint8Array(0);
  
  // 计算前导 '1' 的数量
  let leadingOnes = 0;
  for (const char of str) {
    if (char === '1') leadingOnes++;
    else break;
  }
  
  // 转换为 BigInt
  let num = BigInt(0);
  for (const char of str) {
    const index = BASE58_ALPHABET.indexOf(char);
    if (index === -1) {
      throw new Error(`Invalid Base58 character: ${char}`);
    }
    num = num * BigInt(58) + BigInt(index);
  }
  
  // 转换为字节数组
  const bytes: number[] = [];
  while (num > BigInt(0)) {
    bytes.unshift(Number(num % BigInt(256)));
    num = num / BigInt(256);
  }
  
  // 添加前导零
  const result = new Uint8Array(leadingOnes + bytes.length);
  result.set(bytes, leadingOnes);
  
  return result;
}

/**
 * 验证 Ed25519 签名
 * 
 * 注意：Cloudflare Workers 支持 Web Crypto API
 */
export function verifySignature(
  message: Uint8Array,
  signature: Uint8Array,
  publicKey: Uint8Array
): boolean {
  try {
    if (signature.length !== 64) {
      return false;
    }
    
    if (publicKey.length !== 32) {
      return false;
    }

    return nacl.sign.detached.verify(message, signature, publicKey)
  } catch (error) {
    return false;
  }
}

/**
 * 生成 JWT Token
 */
export async function generateToken(payload: Record<string, unknown>, secret: string, expiresInSeconds: number): Promise<string> {
  const nowSeconds = Math.floor(Date.now() / 1000)
  return await new SignJWT(payload)
    .setProtectedHeader({ alg: 'HS256', typ: 'JWT' })
    .setIssuedAt(nowSeconds)
    .setExpirationTime(nowSeconds + expiresInSeconds)
    .sign(new TextEncoder().encode(secret))
}

/**
 * 验证 JWT Token
 */
export async function verifyToken(token: string, secret: string): Promise<Record<string, unknown> | null> {
  try {
    const { payload } = await jwtVerify(token, new TextEncoder().encode(secret), {
      clockTolerance: 5,
    })
    return payload as unknown as Record<string, unknown>
  } catch {
    return null;
  }
}
