# 服务端说明

这是项目的联调信令服务，供主应用音视频验证和远程协助使用。

更完整的项目说明请先看：

- [使用文档](../docs/user-guide.md)
- [技术文档](../docs/technical-guide.md)

## 启动

```bash
npm install
npm run dev
```

默认监听：

- `http://127.0.0.1:3000`
- WebSocket：`ws://127.0.0.1:3000/ws`

## 接口

- `GET /health`
- `GET /config`

## 环境变量

```env
PORT=3000
HOST=127.0.0.1
NODE_ENV=development
TOKEN_SECRET=
TOKEN_SECRET_FILE=.token_secret
MIN_TOKEN_SECRET_LENGTH=32
STUN_URL=stun:stun.timemotion.top:3478
TURN_URL=turn:turn.timemotion.top:3478
ENFORCE_INVITE_API_KEY=true
INVITE_API_KEY=
INVITE_API_KEY_FILE=.invite_api_key
INVITE_RATE_LIMIT_WINDOW_MS=60000
INVITE_RATE_LIMIT_MAX_REQUESTS=30
WS_ALLOWED_ORIGINS=https://help.yourdomain.com
WS_ALLOWED_HOSTS=
WS_REQUIRE_ORIGIN=false
```

说明：

- `TOKEN_SECRET` 不再提供硬编码默认值；必须为强随机密钥。
- 生产环境（`NODE_ENV=production`）若未提供 `TOKEN_SECRET` 且 `TOKEN_SECRET_FILE` 不存在，将拒绝启动。
- 非生产环境首次启动若缺少 `TOKEN_SECRET`，会自动生成并写入 `TOKEN_SECRET_FILE`。
- 当 `ENFORCE_INVITE_API_KEY=true` 时，`POST /invites` 与 `POST /invites/resolve` 必须携带请求头 `x-invite-api-key`。
- 默认即开启校验；仅在你显式配置 `ENFORCE_INVITE_API_KEY=false` 时才会关闭（不建议）。
- 两个邀请接口均启用基于来源 IP 的内存限流，默认 1 分钟最多 30 次请求。
- 若未设置 `INVITE_API_KEY`，服务首次启动会在 `INVITE_API_KEY_FILE` 指定路径自动生成并持久化一个 key。
- WebSocket 握手新增来源校验：若请求带 `Origin`，将按 `WS_ALLOWED_ORIGINS` 白名单校验；可通过 `WS_ALLOWED_HOSTS` 限制 `Host`。
- 默认允许无 `Origin` 的原生客户端连接；若需要强制浏览器来源校验可设置 `WS_REQUIRE_ORIGIN=true`。
- 默认仅监听本机回环地址；若需要局域网或公网访问，请显式配置 `HOST=0.0.0.0` 并配合防火墙/安全组限制来源。

## WebSocket 事件

- `join`
- `leave`
- `signal`
- `rc_join`
- `rc_leave`
- `rc_frame`
- `rc_target_status`
- `rc_command`
- `rc_signal`
