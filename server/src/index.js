import express from 'express';
import cors from 'cors';
import { WebSocket } from 'ws';
import { randomUUID } from 'crypto';
import { readFileSync, existsSync, createReadStream, writeFileSync } from 'fs';
import { createPrivateKey, sign } from 'crypto';
import multer from 'multer';
import path from 'path';
import { fileURLToPath } from 'url';
import wavespeed from 'wavespeed';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = process.env.PORT || 18881;
const GATEWAY_TOKEN = '4e243a621aed7c5b3abe74b564b66b2f';
const GATEWAY_URL = 'ws://127.0.0.1:18789';
const UPLOADS_DIR = path.join(__dirname, '..', 'uploads');
const WAVESPEED_API_KEY = process.env.WAVESPEED_API_KEY || '';

// ── Multer setup ────────────────────────────────────────────────────────────
const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, UPLOADS_DIR),
  filename: (req, file, cb) => {
    const ext = path.extname(file.originalname);
    const fileId = `${Date.now()}-${randomUUID().slice(0, 8)}${ext}`;
    cb(null, fileId);
  }
});

const upload = multer({
  storage,
  limits: { fileSize: 100 * 1024 * 1024 } // 100 MB
});

// ── File metadata store (fileId → original name / type / size) ──────────────
const fileRegistry = new Map(); // fileId → { originalName, mimeType, size }

// ── Load device identity ─────────────────────────────────────────────────────
const DEVICE_IDENTITY_PATH = `${process.env.HOME}/.openclaw/identity/device.json`;
let deviceIdentity = null;

try {
  const raw = readFileSync(DEVICE_IDENTITY_PATH, 'utf8');
  const parsed = JSON.parse(raw);
  if (parsed?.deviceId && parsed?.publicKeyPem && parsed?.privateKeyPem) {
    deviceIdentity = parsed;
  }
} catch (err) {
  console.warn('[Gateway] No device identity found');
}

// ── Gateway auth helpers ─────────────────────────────────────────────────────
function buildDeviceAuthPayloadV3(params) {
  const scopes = params.scopes.join(',');
  return ['v3', params.deviceId, params.clientId, params.clientMode, params.role, scopes, String(params.signedAt), params.token, params.nonce, 'linux', 'linux'].join('|');
}

function signPayload(payload, privateKeyPem) {
  const key = createPrivateKey(privateKeyPem);
  const sig = sign(null, Buffer.from(payload), key);
  return sig.toString('base64url');
}

// ── Gateway client ───────────────────────────────────────────────────────────
function createGatewayClient() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(GATEWAY_URL);
    const pending = new Map();
    const eventHandlers = new Map();
    let isReady = false;
    let client = null;
    let connectTimeout = null;
    let challengeNonce = null;
    let connId = null;

    const handleMessage = (data) => {
      let msg;
      try { msg = JSON.parse(data.toString()); } catch { return; }

      if (msg.type === 'event' && msg.event === 'connect.challenge') {
        challengeNonce = msg.payload?.nonce;
        if (!challengeNonce) { ws.close(); reject(new Error('Missing nonce')); return; }

        const signedAt = Date.now();
        const scopes = deviceIdentity
          ? ['operator.read', 'operator.admin', 'operator.write', 'operator.approvals', 'operator.pairing']
          : ['operator.admin'];

        let device = undefined;
        if (deviceIdentity) {
          const payload = buildDeviceAuthPayloadV3({ deviceId: deviceIdentity.deviceId, clientId: 'cli', clientMode: 'cli', role: 'operator', scopes, token: GATEWAY_TOKEN, nonce: challengeNonce, signedAt });
          const signature = signPayload(payload, deviceIdentity.privateKeyPem);
          device = { id: deviceIdentity.deviceId, publicKey: deviceIdentity.publicKeyPem, signature, signedAt, nonce: challengeNonce };
        }

        ws.send(JSON.stringify({ type: 'req', id: randomUUID(), method: 'connect', params: { minProtocol: 3, maxProtocol: 3, client: { id: 'cli', mode: 'cli', version: '2026.3.23-2', platform: 'linux', deviceFamily: 'linux' }, caps: [], auth: { token: GATEWAY_TOKEN }, role: 'operator', scopes, ...(device ? { device } : {}) } }));
        return;
      }

      if (msg.type === 'res' && msg.id) {
        if (!isReady && msg.ok && msg.payload?.type === 'hello-ok') {
          isReady = true;
          connId = msg.payload?.server?.connId;
          if (connectTimeout) { clearTimeout(connectTimeout); connectTimeout = null; }

          client = {
            ws, connId,
            request: (method, params) => new Promise((resResolve, resReject) => {
              if (!isReady) { resReject(new Error('Gateway client not ready')); return; }
              const id = randomUUID();
              ws.send(JSON.stringify({ type: 'req', id, method, params }));
              pending.set(id, { resolve: resResolve, reject: resReject });
              setTimeout(() => { if (pending.has(id)) { pending.delete(id); resReject(new Error(`Gateway timeout: ${method}`)); } }, 60000);
            }),
            onEvent: (eventName, handler) => { if (!eventHandlers.has(eventName)) eventHandlers.set(eventName, []); eventHandlers.get(eventName).push(handler); },
            offEvent: (eventName, handler) => { const h = eventHandlers.get(eventName); if (h) { const i = h.indexOf(handler); if (i >= 0) h.splice(i, 1); } },
            close: () => ws.close(),
            isReady: () => isReady
          };
          resolve(client);
        }
        const p = pending.get(msg.id);
        if (p) { pending.delete(msg.id); p.resolve(msg.ok ? msg.payload : msg.error); }
        return;
      }

      if (msg.type === 'event' && msg.event) {
        const h = eventHandlers.get(msg.event);
        if (h) h.forEach(handler => { try { handler(msg.payload || msg); } catch (e) { console.error('[Event handler error]:', e.message); } });
      }
    };

    ws.on('message', handleMessage);
    ws.on('error', err => { console.error('[Gateway] WS error:', err.message); isReady = false; for (const [, p] of pending) p.reject(err); pending.clear(); });
    ws.on('close', (code, reason) => { console.log(`[Gateway] Closed: ${code} ${reason}`); isReady = false; for (const [, p] of pending) p.reject(new Error(`Gateway closed: ${code}`)); pending.clear(); });
    connectTimeout = setTimeout(() => { ws.close(); reject(new Error('Gateway connect timeout')); }, 10000);
  });
}

let gatewayClient = null;
let clientInitPromise = null;

async function getGatewayClient() {
  if (gatewayClient && gatewayClient.isReady()) return gatewayClient;
  if (!clientInitPromise) {
    clientInitPromise = createGatewayClient().then(c => { gatewayClient = c; console.log('[Gateway] Client ready'); return c; })
      .catch(err => { clientInitPromise = null; gatewayClient = null; throw err; });
  }
  return clientInitPromise;
}

// ── Text extraction helper ──────────────────────────────────────────────────
function extractTextFromContent(content) {
  if (!content) return null;
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    for (const block of content) {
      if (block.type === 'text' && block.text) {
        const finalMatch = block.text.match(/<final>([\s\S]*?)<\/final>/);
        if (finalMatch) return finalMatch[1].trim();
        return block.text.trim();
      }
    }
  }
  return null;
}

// ── TTS via WaveSpeed / MiniMax ──────────────────────────────────────────────
async function synthesizeSpeech(text) {
  if (!WAVESPEED_API_KEY) {
    console.warn('[TTS] WAVESPEED_API_KEY not set, skipping TTS');
    return null;
  }
  try {
    const client = new wavespeed.Client(WAVESPEED_API_KEY, { maxRetries: 2 });
    const result = await client.run('minimax/speech-2.6-turbo', {
      text,
      voice_id: 'English_CalmWoman',
      format: 'mp3',
      sample_rate: 24000,
      bitrate: 128000
    });

    if (!result.outputs || !result.outputs[0]) {
      console.warn('[TTS] No output URL returned');
      return null;
    }

    // Download the audio from WaveSpeed CDN to local uploads/
    const audioUrl = result.outputs[0];
    const response = await fetch(audioUrl);
    if (!response.ok) throw new Error(`Fetch failed: ${response.status}`);

    const arrayBuffer = await response.arrayBuffer();
    const fileId = `tts-${Date.now()}-${randomUUID().slice(0, 8)}.mp3`;
    const filePath = path.join(UPLOADS_DIR, fileId);
    writeFileSync(filePath, Buffer.from(arrayBuffer));

    fileRegistry.set(fileId, { originalName: `tts-${Date.now()}.mp3`, mimeType: 'audio/mpeg', size: Buffer.from(arrayBuffer).length });
    console.log(`[TTS] Audio saved: ${fileId}`);
    return fileId;
  } catch (err) {
    console.error('[TTS] Error:', err.message);
    return null;
  }
}

// ── Express app ─────────────────────────────────────────────────────────────
const app = express();
app.use(cors());
app.use(express.json());

const sessions = new Map();

// POST /api/chat
app.post('/api/chat', async (req, res) => {
  try {
    const { text, sessionId, enableTTS, fileId, fileType } = req.body;
    if (!text || typeof text !== 'string') return res.status(400).json({ code: 1, error: 'text is required' });
    let trimmedText = text.trim();
    if (!trimmedText) return res.status(400).json({ code: 1, error: 'text cannot be empty' });

    // 如果有图片附件，读取并附加到消息中
    if (fileId && fileType === 'image') {
      const filePath = path.join(UPLOADS_DIR, fileId);
      if (existsSync(filePath)) {
        try {
          const imageBuffer = readFileSync(filePath);
          const base64Image = imageBuffer.toString('base64');
          const mimeType = fileRegistry.get(fileId)?.mimeType || 'image/jpeg';
          // 在消息末尾附加图片的base64数据
          trimmedText = trimmedText + '\n[图片数据](data:' + mimeType + ';base64,' + base64Image + ')';
        } catch (err) {
          console.warn('[Chat] Failed to attach image:', err.message);
        }
      }
    }

    const client = await getGatewayClient();
    let sessionKey, actualSessionId;

    if (sessionId && sessions.has(sessionId)) {
      const session = sessions.get(sessionId);
      sessionKey = session.key;
      actualSessionId = sessionId;
    } else {
      const createResult = await client.request('sessions.create', {});
      sessionKey = createResult.key;
      actualSessionId = sessionId || randomUUID();
      sessions.set(actualSessionId, { key: sessionKey, createdAt: Date.now() });
      await client.request('sessions.messages.subscribe', { key: sessionKey });
    }

    let replyResolve = null, replyReject = null;
    let timeoutId = null;
    const replyPromise = new Promise((resolve, reject) => { replyResolve = resolve; replyReject = reject; });

    const messageHandler = (payload) => {
      if (!payload || !payload.sessionKey || payload.sessionKey !== sessionKey) return;
      const message = payload.message;
      if (!message || typeof message !== 'object' || message.role !== 'assistant') return;
      const reply = extractTextFromContent(message.content);
      if (reply) {
        clearTimeout(timeoutId);
        client.offEvent('session.message', messageHandler);
        replyResolve(reply);
      }
    };

    client.onEvent('session.message', messageHandler);
    timeoutId = setTimeout(() => { client.offEvent('session.message', messageHandler); replyReject(new Error('Timeout')); }, 60000);

    await client.request('sessions.send', { key: sessionKey, message: trimmedText });
    const reply = await replyPromise;

    // Optional TTS
    let audioUrl = null;
    if (enableTTS) {
      const fileId = await synthesizeSpeech(reply);
      if (fileId) audioUrl = `/api/audio/${fileId}`;
    }

    const response = { code: 0, reply: reply.trim(), sessionId: actualSessionId };
    if (audioUrl) response.audioUrl = audioUrl;
    res.json(response);

  } catch (err) {
    console.error('[Chat] Error:', err.message);
    if (err.message.includes('Gateway closed') || err.message.includes('WebSocket')) { gatewayClient = null; clientInitPromise = null; }
    res.status(500).json({ code: 2, error: err.message });
  }
});

// POST /api/upload
app.post('/api/upload', upload.single('file'), (req, res) => {
  try {
    if (!req.file) return res.status(400).json({ code: 1, error: 'No file provided' });

    const type = req.body.type || 'file';
    const sessionId = req.body.sessionId || null;
    const fileId = req.file.filename;

    fileRegistry.set(fileId, {
      originalName: req.file.originalname,
      mimeType: req.file.mimetype,
      size: req.file.size,
      type,
      sessionId
    });

    const url = `/api/download/${fileId}`;
    res.json({ code: 0, url, fileId });
  } catch (err) {
    console.error('[Upload] Error:', err.message);
    res.status(500).json({ code: 2, error: err.message });
  }
});

// GET /api/download/:fileId
app.get('/api/download/:fileId', (req, res) => {
  const { fileId } = req.params;
  const meta = fileRegistry.get(fileId);
  if (!meta) return res.status(404).json({ code: 1, error: 'File not found' });

  const filePath = path.join(UPLOADS_DIR, fileId);
  if (!existsSync(filePath)) return res.status(404).json({ code: 1, error: 'File not found on disk' });

  res.setHeader('Content-Type', meta.mimeType);
  res.setHeader('Content-Disposition', `attachment; filename="${meta.originalName}"`);
  createReadStream(filePath).pipe(res);
});

// GET /api/audio/:fileId  (serves audio files, used by chat audioUrl)
app.get('/api/audio/:fileId', (req, res) => {
  const { fileId } = req.params;
  const filePath = path.join(UPLOADS_DIR, fileId);
  if (!existsSync(filePath)) return res.status(404).json({ code: 1, error: 'Audio not found' });

  res.setHeader('Content-Type', 'audio/mpeg');
  createReadStream(filePath).pipe(res);
});

// Health check
app.get('/health', (req, res) => {
  const clientReady = gatewayClient && gatewayClient.isReady();
  res.json({ status: 'ok', sessions: sessions.size, gatewayConnected: clientReady, uploads: fileRegistry.size });
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`VoiceChat server listening on port ${PORT}`);
  console.log(`Uploads dir: ${UPLOADS_DIR}`);
});
