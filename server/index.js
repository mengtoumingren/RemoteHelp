const express = require("express");
const http = require("http");
const crypto = require("crypto");
const { WebSocket, WebSocketServer } = require("ws");

const PORT = Number(process.env.PORT || 3000);
const HOST = process.env.HOST || "0.0.0.0";
const STUN_URL = process.env.STUN_URL || "stun:stun.timemotion.top:3478";

const app = express();
app.use(express.json());

app.get("/health", (_req, res) => {
  res.json({ ok: true, timestamp: Date.now() });
});

app.get("/config", (_req, res) => {
  res.json({
    wsUrlPath: "/ws",
    iceServers: [{ urls: [STUN_URL] }],
  });
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: "/ws" });

const webrtcRooms = new Map();
const remoteControlRooms = new Map();

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

wss.on("connection", (socket) => {
  socket.meta = null;
  socket.remoteMeta = null;

  socket.on("message", (raw) => {
    let message;
    try {
      message = JSON.parse(raw.toString());
    } catch (_error) {
      send(socket, { type: "error", message: "Invalid JSON payload" });
      return;
    }

    if (message.type === "join") {
      const roomId = String(message.roomId || "").trim();
      const displayName = String(message.displayName || "Anonymous").trim() || "Anonymous";
      if (!roomId) {
        send(socket, { type: "error", message: "roomId is required" });
        return;
      }

      leaveWebRtcRoom(socket);
      const room = ensureWebRtcRoom(roomId);
      if (room.size >= 2) {
        send(socket, { type: "error", message: "This demo only supports two peers per room" });
        return;
      }

      const clientId = crypto.randomUUID();
      socket.meta = { roomId, clientId, displayName };
      room.set(clientId, { socket, displayName });

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

      const room = webrtcRooms.get(meta.roomId);
      const envelope = {
        type: "signal",
        roomId: meta.roomId,
        fromClientId: meta.clientId,
        fromDisplayName: meta.displayName,
        signalType: message.signalType,
        payload: message.payload || {},
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
      const displayName = String(message.displayName || "Remote Device").trim() || "Remote Device";
      const role = String(message.role || "").trim();
      if (!roomId || !["controller", "target"].includes(role)) {
        send(socket, { type: "error", message: "Invalid rc_join payload" });
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
      const peerRecord = { clientId, roomId, displayName, role, socket };
      room.peers.set(clientId, peerRecord);
      socket.remoteMeta = { clientId, roomId, displayName, role };

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

      const room = remoteControlRooms.get(meta.roomId);
      room.lastFrame = message.frame || null;
      broadcastRemoteRoom(room, { type: "rc_frame", frame: room.lastFrame }, meta.clientId);
      return;
    }

    if (message.type === "rc_target_status") {
      const meta = socket.remoteMeta;
      if (!meta || meta.role !== "target" || !remoteControlRooms.has(meta.roomId)) {
        send(socket, { type: "error", message: "Only target can send rc_target_status" });
        return;
      }

      const room = remoteControlRooms.get(meta.roomId);
      room.lastTargetStatus = message.targetStatus || null;
      broadcastRemoteRoom(room, {
        type: "rc_target_status",
        targetStatus: room.lastTargetStatus,
      }, meta.clientId);
      return;
    }

    if (message.type === "rc_command") {
      const meta = socket.remoteMeta;
      if (!meta || meta.role !== "controller" || !remoteControlRooms.has(meta.roomId)) {
        send(socket, { type: "error", message: "Only controller can send rc_command" });
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
      });
      return;
    }

    if (message.type === "rc_signal") {
      const meta = socket.remoteMeta;
      if (!meta || !remoteControlRooms.has(meta.roomId)) {
        send(socket, { type: "error", message: "Join a remote-control room first" });
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
      });
      return;
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
  console.log(`Demo server listening on http://${HOST}:${PORT}`);
  console.log(`WebRTC STUN server: ${STUN_URL}`);
});

