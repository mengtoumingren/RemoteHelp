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

- `http://0.0.0.0:3000`
- WebSocket：`ws://<your-ip>:3000/ws`

## 接口

- `GET /health`
- `GET /config`

## 环境变量

```env
PORT=3000
HOST=0.0.0.0
STUN_URL=stun:stun.timemotion.top:3478
TURN_URL=turn:turn.timemotion.top:3478
```

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
