# WebRTC Demo Server

## Start

```bash
npm install
npm run dev
```

Default address: `http://0.0.0.0:3000`

WebSocket endpoint: `ws://<your-ip>:3000/ws`

Health check: `GET /health`

ICE config: `GET /config`

## Environment variables

```env
PORT=3000
HOST=0.0.0.0
STUN_URL=stun:stun.timemotion.top:3478
```

## Signaling events

- `join`: enter a room, max 2 peers
- `signal`: forward `offer` / `answer` / `candidate`
- `leave`: exit a room
