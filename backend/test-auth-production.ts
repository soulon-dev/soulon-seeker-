
import nacl from 'tweetnacl';
import bs58 from 'bs58';

const API_BASE =
  process.env.API_BASE?.trim() ||
  'https://memory-ai-backend-production.nickseddon2167.workers.dev';

async function main() {
  console.log('Testing Authentication Flow against:', API_BASE);

  // 1. Generate Keypair
  const keyPair = nacl.sign.keyPair();
  const walletAddress = bs58.encode(keyPair.publicKey);
  const publicKey = bs58.encode(keyPair.publicKey);
  console.log('Generated Wallet:', walletAddress);

  const now = Date.now();
  const sessionPubKeyHex = Buffer.from(nacl.sign.keyPair().publicKey).toString('hex');
  const expiresAt = now + 7 * 24 * 60 * 60 * 1000;
  const message = [
    'MemoryAI Session Authorization',
    '',
    'I authorize this session key to sign DataItems on my behalf.',
    '',
    `Session Public Key: ${sessionPubKeyHex}`,
    `Expires: ${expiresAt}`,
    '',
    'This authorization is valid for 7 days.',
  ].join('\n');

  console.log('\n--- Signing Session Authorization Message ---');
  const messageBytes = new TextEncoder().encode(message);
  const signature = nacl.sign.detached(messageBytes, keyPair.secretKey);
  const signatureBase58 = bs58.encode(signature);

  console.log('\n--- Logging In (Session Authorization) ---');
  const loginRes = await fetch(`${API_BASE}/api/v1/auth/login-session-key`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      wallet_address: walletAddress,
      signature: signatureBase58,
      public_key: publicKey,
      message
    })
  });

  if (!loginRes.ok) {
    console.error('Login failed:', await loginRes.text());
    return;
  }

  const loginData = await loginRes.json();
  console.log('Login success!');
  console.log('Access Token:', loginData.access_token);

  console.log('\n--- Sync Subscription (monthly) ---');
  const startDate = Date.now();
  const endDate = startDate + 30 * 24 * 60 * 60 * 1000;
  const syncRes = await fetch(`${API_BASE}/api/v1/subscriptions/sync`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${loginData.access_token}`
    },
    body: JSON.stringify({
      walletAddress,
      planId: 'monthly',
      startDate,
      endDate,
      amount: 9.99,
      transactionId: 'test_tx'
    })
  });
  console.log('sync status', syncRes.status, await syncRes.text());

  console.log('\n--- Check-in Status (GET) ---');
  const statusRes = await fetch(`${API_BASE}/api/v1/user/${walletAddress}/check-in`, {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${loginData.access_token}`
    }
  });
  console.log('check-in status', statusRes.status, await statusRes.text());

  console.log('\n--- Check-in (POST) ---');
  const checkInRes = await fetch(`${API_BASE}/api/v1/user/${walletAddress}/check-in`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${loginData.access_token}`
    }
  });
  console.log('check-in', checkInRes.status, await checkInRes.text());

  // 5. Access Protected Resource (Balance)
  console.log('\n--- Fetching Balance ---');
  const balanceRes = await fetch(`${API_BASE}/api/v1/user/${walletAddress}/balance`, {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${loginData.access_token}`
    }
  });

  if (balanceRes.ok) {
    const balanceData = await balanceRes.json();
    console.log('Balance fetch SUCCESS:', balanceData);
  } else {
    console.error('Balance fetch FAILED:', balanceRes.status, await balanceRes.text());
  }
}

main().catch(console.error);
