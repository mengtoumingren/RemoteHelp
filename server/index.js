const express = require("express");
const http = require("http");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const { WebSocket, WebSocketServer } = require("ws");

const PORT = Number(process.env.PORT || 3000);
const HOST = process.env.HOST || "127.0.0.1";
const NODE_ENV = String(process.env.NODE_ENV || "development").toLowerCase();
const STUN_URL = process.env.STUN_URL || "stun:stun.timemotion.top:3478";
const TURN_URL = process.env.TURN_URL || "turn:turn.timemotion.top:3478";
const APP_BASE_URL = (process.env.APP_BASE_URL || "https://help.yourdomain.com").replace(/\/+$/, "");
const TOKEN_SECRET_FILE = process.env.TOKEN_SECRET_FILE || path.join(__dirname, ".token_secret");
const MIN_TOKEN_SECRET_LENGTH = Number(process.env.MIN_TOKEN_SECRET_LENGTH || 32);
const INVITE_TTL_MS = Number(process.env.INVITE_TTL_MS || 5 * 60 * 1000);
const MAX_WS_MESSAGE_BYTES = Number(process.env.MAX_WS_MESSAGE_BYTES || 256 * 1024);
const MAX_MESSAGES_PER_10S = Number(process.env.MAX_MESSAGES_PER_10S || 120);
const MESSAGE_META_TTL_MS = Number(process.env.MESSAGE_META_TTL_MS || 2 * 60 * 1000);
const INVITE_API_KEY_FILE = process.env.INVITE_API_KEY_FILE || path.join(__dirname, ".invite_api_key");
const ENFORCE_INVITE_API_KEY = String(
  process.env.ENFORCE_INVITE_API_KEY || "true"
).toLowerCase() === "true";
const INVITE_API_KEY_HEADER = "x-invite-api-key";
const INVITE_RATE_LIMIT_WINDOW_MS = Number(process.env.INVITE_RATE_LIMIT_WINDOW_MS || 60 * 1000);
const INVITE_RATE_LIMIT_MAX_REQUESTS = Number(process.env.INVITE_RATE_LIMIT_MAX_REQUESTS || 30);

function parseCsv(value) {
  return String(value || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

const DEFAULT_WS_ALLOWED_ORIGIN = (() => {
  try {
    return new URL(APP_BASE_URL).origin;
  } catch {
    return "";
  }
})();
const WS_ALLOWED_ORIGINS = new Set(
  parseCsv(process.env.WS_ALLOWED_ORIGINS || DEFAULT_WS_ALLOWED_ORIGIN).map((origin) => origin.toLowerCase())
);
const WS_ALLOWED_HOSTS = new Set(
  parseCsv(process.env.WS_ALLOWED_HOSTS).map((host) => host.toLowerCase())
);
const WS_REQUIRE_ORIGIN = String(process.env.WS_REQUIRE_ORIGIN || "false").toLowerCase() === "true";

function validateTokenSecret(secret, source) {
  if (!secret || secret.length < MIN_TOKEN_SECRET_LENGTH) {
    throw new Error(`TOKEN_SECRET from ${source} is too short, min length is ${MIN_TOKEN_SECRET_LENGTH}`);
  }
  if (secret === "remotehelp-server-secret") {
    throw new Error(`TOKEN_SECRET from ${source} uses forbidden weak default value`);
  }
  const uniqueChars = new Set(secret).size;
  if (uniqueChars < 8) {
    throw new Error(`TOKEN_SECRET from ${source} appears weak (not enough character diversity)`);
  }
}

function loadTokenSecret() {
  const envSecret = String(process.env.TOKEN_SECRET || "").trim();
  if (envSecret) {
    validateTokenSecret(envSecret, "env");
    return envSecret;
  }
  const filePath = path.resolve(TOKEN_SECRET_FILE);
  if (fs.existsSync(filePath)) {
    const fileSecret = fs.readFileSync(filePath, "utf8").trim();
    validateTokenSecret(fileSecret, "file");
    return fileSecret;
  }
  if (NODE_ENV === "production") {
    throw new Error("Missing TOKEN_SECRET in production. Set TOKEN_SECRET or provide TOKEN_SECRET_FILE with a strong secret.");
  }
  const generatedSecret = crypto.randomBytes(48).toString("hex");
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${generatedSecret}\n`, { encoding: "utf8", mode: 0o600 });
  console.warn(`[security] TOKEN_SECRET was missing, generated strong secret at ${filePath} (non-production only)`);
  return generatedSecret;
}

const TOKEN_SECRET = loadTokenSecret();

function loadOrCreateInviteApiKey() {
  const envKey = String(process.env.INVITE_API_KEY || "").trim();
  if (envKey) {
    return envKey;
  }
  try {
    const filePath = path.resolve(INVITE_API_KEY_FILE);
    if (fs.existsSync(filePath)) {
      const fileKey = fs.readFileSync(filePath, "utf8").trim();
      if (fileKey) {
        return fileKey;
      }
    }
    const generatedKey = crypto.randomBytes(32).toString("hex");
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, `${generatedKey}\n`, { encoding: "utf8", mode: 0o600 });
    console.warn(`[security] INVITE_API_KEY file not found, generated new key at ${filePath}`);
    return generatedKey;
  } catch (error) {
    throw new Error(`Failed to load or create invite API key: ${error.message || "unknown"}`);
  }
}

const INVITE_API_KEY = loadOrCreateInviteApiKey();

if (ENFORCE_INVITE_API_KEY && !INVITE_API_KEY) {
  throw new Error("INVITE_API_KEY is required when ENFORCE_INVITE_API_KEY=true");
}

const app = express();
app.use(express.json());

const inviteSessions = new Map();
const webrtcRooms = new Map();
const remoteControlRooms = new Map();
const inviteRequestCounters = new Map();

function base64UrlEncode(value) {
  return Buffer.from(value)
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function base64UrlDecode(value) {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4 || 4)) % 4);
  return Buffer.from(padded, "base64").toString("utf8");
}

function signTokenPayload(payload) {
  return crypto.createHmac("sha256", TOKEN_SECRET).update(payload).digest("hex");
}

function createSignedToken(payload) {
  const encodedPayload = base64UrlEncode(JSON.stringify(payload));
  return `${encodedPayload}.${signTokenPayload(encodedPayload)}`;
}

function verifySignedToken(token) {
  if (!token || typeof token !== "string") {
    throw new Error("Missing token");
  }
  const parts = token.split(".");
  if (parts.length !== 2) {
    throw new Error("Invalid token format");
  }
  const [payloadPart, signaturePart] = parts;
  const expectedSignature = signTokenPayload(payloadPart);
  const expectedBuffer = Buffer.from(expectedSignature, "utf8");
  const signatureBuffer = Buffer.from(signaturePart, "utf8");
  if (signatureBuffer.length !== expectedBuffer.length || !crypto.timingSafeEqual(signatureBuffer, expectedBuffer)) {
    throw new Error("Invalid token signature");
  }
  return JSON.parse(base64UrlDecode(payloadPart));
}

function sanitizeName(value, fallback) {
  return String(value || "").trim() || fallback;
}

function sanitizePhone(value) {
  return String(value || "").trim();
}

function buildDeepLink(token) {
  return `${APP_BASE_URL}/r/${token}`;
}

function buildInvitePayload(helperName, elderName, elderPhone, now = Date.now()) {
  const sessionId = `sess-${crypto.randomUUID().slice(0, 8)}`;
  const expiresAt = now + INVITE_TTL_MS;
  return {
    inviteId: crypto.randomUUID(),
    sessionId,
    requestId: sessionId,
    helperName: sanitizeName(helperName, "协助方"),
    elderName: sanitizeName(elderName, "协助对象"),
    elderPhone: sanitizePhone(elderPhone),
    createdAt: now,
    expiresAt,
  };
}

function issueInvite(helperName, elderName, elderPhone, now = Date.now()) {
  const payload = buildInvitePayload(helperName, elderName, elderPhone, now);
  const helperToken = createSignedToken({
    type: "channel",
    audience: "helper",
    roles: ["helper", "controller"],
    requestId: payload.requestId,
    sessionId: payload.sessionId,
    createdAt: now,
    expiresAt: payload.expiresAt,
  });
  const elderToken = createSignedToken({
    type: "channel",
    audience: "elder",
    roles: ["elder", "target"],
    requestId: payload.requestId,
    sessionId: payload.sessionId,
    createdAt: now,
    expiresAt: payload.expiresAt,
  });
  const session = {
    payload,
    helperToken,
    elderToken,
    consumedAt: null,
  };
  inviteSessions.set(payload.requestId, session);
  return session;
}

function getInviteSessionByToken(token, expectedAudience) {
  const decoded = verifySignedToken(token);
  if (decoded.type !== "channel") {
    throw new Error("Invalid token type");
  }
  if (decoded.expiresAt <= Date.now()) {
    throw new Error("Invite expired");
  }
  if (expectedAudience && decoded.audience !== expectedAudience) {
    throw new Error("Unexpected invite audience");
  }
  const session = inviteSessions.get(decoded.requestId);
  if (!session) {
    throw new Error("Invite session not found");
  }
  const expectedToken = decoded.audience === "helper" ? session.helperToken : session.elderToken;
  if (expectedToken !== token) {
    throw new Error("Invite token revoked");
  }
  return { session, decoded };
}

function pruneExpiredInvites() {
  const now = Date.now();
  inviteSessions.forEach((session, requestId) => {
    if (session.payload.expiresAt <= now) {
      inviteSessions.delete(requestId);
    }
  });
}

function allowedRolesFromToken(decoded) {
  return Array.isArray(decoded.roles) ? decoded.roles.map((role) => String(role)) : [];
}

function authenticateRoomAccess({ roomId, authToken, requiredRole }) {
  const decoded = verifySignedToken(authToken);
  if (decoded.type !== "channel") {
    throw new Error("Invalid auth token type");
  }
  if (decoded.expiresAt <= Date.now()) {
    throw new Error("Auth token expired");
  }
  if (decoded.requestId !== roomId || decoded.sessionId !== roomId) {
    throw new Error("Auth token does not match room");
  }
  if (!allowedRolesFromToken(decoded).includes(requiredRole)) {
    throw new Error("Auth token does not allow this role");
  }
  return decoded;
}

function send(socket, payload) {
  if (socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(payload));
  }
}

function ensureWebRtcRoom(roomId) {
  if (!webrtcRooms.has(roomId)) {
    webrtcRooms.set(roomId, new Map());
  }
  return webrtcRooms.get(roomId);
}

function broadcastWebRtc(room, message, exceptClientId) {
  const data = JSON.stringify(message);
  room.forEach((peer, clientId) => {
    if (clientId === exceptClientId) {
      return;
    }
    if (peer.socket.readyState === WebSocket.OPEN) {
      peer.socket.send(data);
    }
  });
}

function leaveWebRtcRoom(socket) {
  const { roomId, clientId } = socket.meta || {};
  if (!roomId || !clientId || !webrtcRooms.has(roomId)) {
    return;
  }
  const room = webrtcRooms.get(roomId);
  const departedPeer = room.get(clientId);
  room.delete(clientId);
  broadcastWebRtc(room, {
    type: "peer-left",
    clientId,
    displayName: departedPeer?.displayName || "",
    role: departedPeer?.role || "",
  });
  if (room.size === 0) {
    webrtcRooms.delete(roomId);
  }
  socket.meta = null;
}

function ensureRemoteControlRoom(roomId) {
  if (!remoteControlRooms.has(roomId)) {
    remoteControlRooms.set(roomId, {
      peers: new Map(),
      lastFrame: null,
      lastTargetStatus: null,
    });
  }
  return remoteControlRooms.get(roomId);
}

function remotePeersPayload(room) {
  return Array.from(room.peers.values()).map((peer) => ({
    clientId: peer.clientId,
    displayName: peer.displayName,
    role: peer.role,
  }));
}

function broadcastRemoteRoom(room, message, exceptClientId) {
  const data = JSON.stringify(message);
  room.peers.forEach((peer) => {
    if (peer.clientId === exceptClientId) {
      return;
    }
    if (peer.socket.readyState === WebSocket.OPEN) {
      peer.socket.send(data);
    }
  });
}

function leaveRemoteControlRoom(socket) {
  const { roomId, clientId } = socket.remoteMeta || {};
  if (!roomId || !clientId || !remoteControlRooms.has(roomId)) {
    return;
  }
  const room = remoteControlRooms.get(roomId);
  room.peers.delete(clientId);
  if (room.peers.size === 0) {
    remoteControlRooms.delete(roomId);
  } else {
    broadcastRemoteRoom(room, {
      type: "rc_peer_update",
      peers: remotePeersPayload(room),
    });
  }
  socket.remoteMeta = null;
}

function getMessageMeta(message) {
  if (!message || typeof message !== "object" || typeof message.meta !== "object" || message.meta === null) {
    throw new Error("Missing message meta");
  }
  const issuedAt = Number(message.meta.issuedAt || 0);
  const nonce = String(message.meta.nonce || "").trim();
  const traceId = String(message.meta.traceId || "").trim();
  if (!issuedAt || !nonce || !traceId) {
    throw new Error("Invalid message meta");
  }
  if (Math.abs(Date.now() - issuedAt) > MESSAGE_META_TTL_MS) {
    throw new Error("Expired message meta");
  }
  return { nonce };
}

function trackSocketMessageRate(socket) {
  const now = Date.now();
  socket.messageRateWindow = (socket.messageRateWindow || []).filter((timestamp) => now - timestamp < 10_000);
  socket.messageRateWindow.push(now);
  if (socket.messageRateWindow.length > MAX_MESSAGES_PER_10S) {
    throw new Error("Too many messages");
  }
}

function ensureUniqueNonce(socket, nonce) {
  socket.seenNonces = socket.seenNonces || new Set();
  if (socket.seenNonces.has(nonce)) {
    throw new Error("Duplicate message nonce");
  }
  socket.seenNonces.add(nonce);
  const timer = setTimeout(() => socket.seenNonces?.delete(nonce), MESSAGE_META_TTL_MS);
  timer.unref?.();
}

function getRequestIp(req) {
  const forwarded = String(req.headers["x-forwarded-for"] || "")
    .split(",")
    .map((value) => value.trim())
    .find(Boolean);
  return forwarded || req.socket?.remoteAddress || "unknown";
}

function pruneInviteRateLimitCounters(now = Date.now()) {
  inviteRequestCounters.forEach((record, key) => {
    if (now - record.windowStart >= INVITE_RATE_LIMIT_WINDOW_MS) {
      inviteRequestCounters.delete(key);
    }
  });
}

function enforceInviteRateLimit(req, res, next) {
  pruneInviteRateLimitCounters();
  const key = `${req.path}:${getRequestIp(req)}`;
  const now = Date.now();
  const existing = inviteRequestCounters.get(key);
  if (!existing || now - existing.windowStart >= INVITE_RATE_LIMIT_WINDOW_MS) {
    inviteRequestCounters.set(key, { windowStart: now, count: 1 });
    next();
    return;
  }
  existing.count += 1;
  if (existing.count > INVITE_RATE_LIMIT_MAX_REQUESTS) {
    res.status(429).json({ ok: false, message: "Too many invite requests" });
    return;
  }
  next();
}

function requireInviteApiKey(req, res, next) {
  if (!ENFORCE_INVITE_API_KEY) {
    next();
    return;
  }
  const provided = String(req.header(INVITE_API_KEY_HEADER) || "").trim();
  if (!provided) {
    res.status(401).json({ ok: false, message: "Missing invite API key" });
    return;
  }
  const expectedBuffer = Buffer.from(INVITE_API_KEY, "utf8");
  const providedBuffer = Buffer.from(provided, "utf8");
  if (providedBuffer.length !== expectedBuffer.length || !crypto.timingSafeEqual(providedBuffer, expectedBuffer)) {
    res.status(403).json({ ok: false, message: "Invalid invite API key" });
    return;
  }
  next();
}

function validateWsHandshake(request) {
  const host = String(request.headers.host || "").trim().toLowerCase();
  if (WS_ALLOWED_HOSTS.size > 0 && (!host || !WS_ALLOWED_HOSTS.has(host))) {
    return { ok: false, reason: "Host is not allowed" };
  }

  const originHeader = String(request.headers.origin || "").trim();
  if (!originHeader) {
    if (WS_REQUIRE_ORIGIN) {
      return { ok: false, reason: "Origin is required" };
    }
    return { ok: true };
  }

  let origin;
  try {
    origin = new URL(originHeader).origin.toLowerCase();
  } catch {
    return { ok: false, reason: "Invalid Origin header" };
  }

  if (WS_ALLOWED_ORIGINS.size > 0 && !WS_ALLOWED_ORIGINS.has(origin)) {
    return { ok: false, reason: "Origin is not allowed" };
  }
  return { ok: true };
}

app.get("/health", (_req, res) => {
  res.json({ ok: true, timestamp: Date.now() });
});

app.get("/config", (_req, res) => {
  res.json({
    wsUrlPath: "/ws",
    iceServers: [{ urls: [STUN_URL, TURN_URL] }],
    transportSecurity: {
      enforceSecureTransport: true,
      appBaseUrl: APP_BASE_URL,
    },
  });
});

app.post("/invites", enforceInviteRateLimit, requireInviteApiKey, (req, res) => {
  try {
    const elderPhone = sanitizePhone(req.body?.elderPhone);
    if (!elderPhone) {
      res.status(400).json({ ok: false, message: "elderPhone is required" });
      return;
    }
    const invite = issueInvite(req.body?.helperName, req.body?.elderName, elderPhone);
    res.json({
      ok: true,
      invite: {
        requestId: invite.payload.requestId,
        sessionId: invite.payload.sessionId,
        helperName: invite.payload.helperName,
        elderName: invite.payload.elderName,
        elderPhone: invite.payload.elderPhone,
        createdAt: invite.payload.createdAt,
        expiresAt: invite.payload.expiresAt,
        inviteToken: invite.elderToken,
        channelToken: invite.helperToken,
        deepLink: buildDeepLink(invite.elderToken),
      },
    });
  } catch (error) {
    res.status(400).json({ ok: false, message: error.message || "Failed to create invite" });
  }
});

app.post("/invites/resolve", enforceInviteRateLimit, requireInviteApiKey, (req, res) => {
  try {
    const token = String(req.body?.token || "").trim();
    const { session } = getInviteSessionByToken(token, "elder");
    session.consumedAt = Date.now();
    res.json({
      ok: true,
      invite: {
        requestId: session.payload.requestId,
        sessionId: session.payload.sessionId,
        helperName: session.payload.helperName,
        elderName: session.payload.elderName,
        elderPhone: session.payload.elderPhone,
        createdAt: session.payload.createdAt,
        expiresAt: session.payload.expiresAt,
        inviteToken: session.elderToken,
        channelToken: session.elderToken,
        deepLink: buildDeepLink(session.elderToken),
      },
    });
  } catch (error) {
    res.status(400).json({ ok: false, message: error.message || "Failed to resolve invite" });
  }
});

setInterval(pruneExpiredInvites, 30_000).unref();

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: "/ws", maxPayload: MAX_WS_MESSAGE_BYTES });

wss.on("connection", (socket, request) => {
  const handshake = validateWsHandshake(request);
  if (!handshake.ok) {
    socket.close(1008, handshake.reason || "Handshake rejected");
    return;
  }

  socket.meta = null;
  socket.remoteMeta = null;
  socket.messageRateWindow = [];
  socket.seenNonces = new Set();

  socket.on("message", (raw) => {
    if (raw.length > MAX_WS_MESSAGE_BYTES) {
      send(socket, { type: "error", message: "Payload too large" });
      return;
    }

    let message;
    try {
      trackSocketMessageRate(socket);
      message = JSON.parse(raw.toString());
    } catch (error) {
      send(socket, { type: "error", message: error.message === "Too many messages" ? error.message : "Invalid JSON payload" });
      return;
    }

    if (message.type === "join") {
      const roomId = String(message.roomId || "").trim();
      const displayName = sanitizeName(message.displayName, "Anonymous");
      const role = String(message.role || "").trim();
      const authToken = String(message.authToken || "").trim();
      if (!roomId || !["helper", "elder"].includes(role) || !authToken) {
        send(socket, { type: "error", message: "Invalid join payload" });
        return;
      }
      let auth;
      try {
        auth = authenticateRoomAccess({ roomId, authToken, requiredRole: role });
      } catch (error) {
        send(socket, { type: "error", message: error.message || "Join denied" });
        return;
      }
      leaveWebRtcRoom(socket);
      const room = ensureWebRtcRoom(roomId);
      if (room.size >= 2) {
        send(socket, { type: "error", message: "This room only supports two peers" });
        return;
      }
      const clientId = crypto.randomUUID();
      socket.meta = { roomId, clientId, displayName, role, sessionId: auth.sessionId };
      room.set(clientId, { socket, displayName, role, sessionId: auth.sessionId });
      send(socket, {
        type: "joined",
        roomId,
        clientId,
        participants: Array.from(room.keys()),
      });
      broadcastWebRtc(room, {
        type: "peer-joined",
        roomId,
        clientId,
        displayName,
        role,
      }, clientId);
      return;
    }

    if (message.type === "leave") {
      leaveWebRtcRoom(socket);
      return;
    }

    if (message.type === "signal") {
      const meta = socket.meta;
      if (!meta || !webrtcRooms.has(meta.roomId)) {
        send(socket, { type: "error", message: "Join a room before sending signals" });
        return;
      }
      if (message.roomId && String(message.roomId).trim() !== meta.roomId) {
        send(socket, { type: "error", message: "roomId does not match active room" });
        return;
      }
      try {
        const { nonce } = getMessageMeta(message);
        ensureUniqueNonce(socket, nonce);
      } catch (error) {
        send(socket, { type: "error", message: error.message || "Invalid signal meta" });
        return;
      }
      const room = webrtcRooms.get(meta.roomId);
      const envelope = {
        type: "signal",
        roomId: meta.roomId,
        fromClientId: meta.clientId,
        fromDisplayName: meta.displayName,
        fromRole: meta.role,
        signalType: message.signalType,
        payload: message.payload || {},
        meta: message.meta,
      };
      if (message.targetClientId) {
        const peer = room.get(message.targetClientId);
        if (peer && peer.socket.readyState === WebSocket.OPEN) {
          peer.socket.send(JSON.stringify(envelope));
        }
      } else {
        broadcastWebRtc(room, envelope, meta.clientId);
      }
      return;
    }

    if (message.type === "rc_join") {
      const roomId = String(message.roomId || "").trim();
      const displayName = sanitizeName(message.displayName, "Remote Device");
      const role = String(message.role || "").trim();
      const authToken = String(message.authToken || "").trim();
      if (!roomId || !["controller", "target"].includes(role) || !authToken) {
        send(socket, { type: "error", message: "Invalid rc_join payload" });
        return;
      }
      let auth;
      try {
        auth = authenticateRoomAccess({ roomId, authToken, requiredRole: role });
      } catch (error) {
        send(socket, { type: "error", message: error.message || "rc_join denied" });
        return;
      }
      leaveRemoteControlRoom(socket);
      const room = ensureRemoteControlRoom(roomId);
      const occupiedRole = Array.from(room.peers.values()).some((peer) => peer.role === role);
      if (occupiedRole) {
        send(socket, { type: "error", message: `Room already has a ${role}` });
        return;
      }
      const clientId = crypto.randomUUID();
      const peerRecord = { clientId, roomId, displayName, role, socket, sessionId: auth.sessionId };
      room.peers.set(clientId, peerRecord);
      socket.remoteMeta = { clientId, roomId, displayName, role, sessionId: auth.sessionId };
      send(socket, {
        type: "rc_joined",
        clientId,
        peers: remotePeersPayload(room),
        targetStatus: room.lastTargetStatus,
      });
      broadcastRemoteRoom(room, {
        type: "rc_peer_update",
        peers: remotePeersPayload(room),
      }, clientId);
      return;
    }

    if (message.type === "rc_leave") {
      leaveRemoteControlRoom(socket);
      return;
    }

    if (message.type === "rc_frame") {
      const meta = socket.remoteMeta;
      if (!meta || meta.role !== "target" || !remoteControlRooms.has(meta.roomId)) {
        send(socket, { type: "error", message: "Only target can send rc_frame" });
        return;
      }
      try {
        const { nonce } = getMessageMeta(message);
        ensureUniqueNonce(socket, nonce);
      } catch (error) {
        send(socket, { type: "error", message: error.message || "Invalid rc_frame meta" });
        return;
      }
      const room = remoteControlRooms.get(meta.roomId);
      room.lastFrame = message.frame || null;
      broadcastRemoteRoom(room, { type: "rc_frame", frame: room.lastFrame, meta: message.meta }, meta.clientId);
      return;
    }

    if (message.type === "rc_target_status") {
      const meta = socket.remoteMeta;
      if (!meta || meta.role !== "target" || !remoteControlRooms.has(meta.roomId)) {
        send(socket, { type: "error", message: "Only target can send rc_target_status" });
        return;
      }
      try {
        const { nonce } = getMessageMeta(message);
        ensureUniqueNonce(socket, nonce);
      } catch (error) {
        send(socket, { type: "error", message: error.message || "Invalid rc_target_status meta" });
        return;
      }
      const room = remoteControlRooms.get(meta.roomId);
      room.lastTargetStatus = message.targetStatus || null;
      broadcastRemoteRoom(room, {
        type: "rc_target_status",
        targetStatus: room.lastTargetStatus,
        meta: message.meta,
      }, meta.clientId);
      return;
    }

    if (message.type === "rc_command") {
      const meta = socket.remoteMeta;
      if (!meta || meta.role !== "controller" || !remoteControlRooms.has(meta.roomId)) {
        send(socket, { type: "error", message: "Only controller can send rc_command" });
        return;
      }
      try {
        const { nonce } = getMessageMeta(message);
        ensureUniqueNonce(socket, nonce);
      } catch (error) {
        send(socket, { type: "error", message: error.message || "Invalid rc_command meta" });
        return;
      }
      const room = remoteControlRooms.get(meta.roomId);
      const targetPeer = Array.from(room.peers.values()).find((peer) => peer.role === "target");
      if (!targetPeer) {
        send(socket, { type: "error", message: "No target connected in this room" });
        return;
      }
      send(targetPeer.socket, {
        type: "rc_command",
        fromDisplayName: meta.displayName,
        command: message.command || {},
        meta: message.meta,
      });
      return;
    }

    if (message.type === "rc_signal") {
      const meta = socket.remoteMeta;
      if (!meta || !remoteControlRooms.has(meta.roomId)) {
        send(socket, { type: "error", message: "Join a remote-control room first" });
        return;
      }
      try {
        const { nonce } = getMessageMeta(message);
        ensureUniqueNonce(socket, nonce);
      } catch (error) {
        send(socket, { type: "error", message: error.message || "Invalid rc_signal meta" });
        return;
      }
      const room = remoteControlRooms.get(meta.roomId);
      const targetRole = meta.role === "controller" ? "target" : "controller";
      const oppositePeer = Array.from(room.peers.values()).find((peer) => peer.role === targetRole);
      if (!oppositePeer) {
        send(socket, { type: "error", message: `No ${targetRole} connected in this room` });
        return;
      }
      send(oppositePeer.socket, {
        type: "rc_signal",
        fromDisplayName: meta.displayName,
        signalType: message.signalType,
        payload: message.payload || {},
        meta: message.meta,
      });
    }
  });

  socket.on("close", () => {
    leaveWebRtcRoom(socket);
    leaveRemoteControlRoom(socket);
  });

  socket.on("error", () => {
    leaveWebRtcRoom(socket);
    leaveRemoteControlRoom(socket);
  });
});

server.listen(PORT, HOST, () => {
  console.log(`RemoteHelp server listening on http://${HOST}:${PORT}`);
  console.log(`WebRTC STUN server: ${STUN_URL}`);
  console.log(`Invite base URL: ${APP_BASE_URL}`);
});
