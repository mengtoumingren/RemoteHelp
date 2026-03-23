# 家庭远程协助 App - MVP 开发基线

本仓库基于 `BaseResolution.txt` 初始化，当前交付内容包含：

- 产品方案拆解后的模块实现细则
- 分阶段实施计划
- 可运行的 Node.js MVP 后端骨架

当前实现目标：

- 为移动端提供认证、绑定、协助会话、Deep Link 校验、安全日志上传的基础接口
- 固化状态机与安全约束，便于后续单一 Android App 接入双角色模式

文档入口：

- [模块实现细则](./docs/module-spec.md)
- [实施计划](./docs/implementation-plan.md)
- [API 说明](./docs/api-spec.md)
- [Android 单体 App 架构](./docs/android-app-architecture.md)

服务端目录：

- `server/` Node.js + Express + Socket.IO MVP

## 启动方式

```bash
cd server
npm install
npm run dev
```

默认监听：`http://0.0.0.0:3000`

局域网/真机调试时可通过宿主机 IP 访问，例如：`http://192.168.2.109:3000`

## 当前范围说明

当前版本是 MVP 后端基础层，侧重以下能力：

- 手机号验证码登录流程占位
- 亲友绑定关系管理
- 协助请求创建与一次性 Deep Link 校验
- 视频验证通过前后的会话状态管理
- 安全日志元数据上传
- Socket.IO 信令房间骨架

尚未在本仓库内实现：

- Android 客户端
- WebRTC 媒体流协商落地
- 短信供应商真实发送
- 对象存储/数据库持久化
- 本地加密证据链采集


## WebRTC 配置

当前版本的信令服务器仍然是 `server/` 自带的 Socket.IO 服务。

WebRTC 的 ICE 服务器列表通过环境变量 `WEBRTC_ICE_SERVERS` 下发给 Android 客户端，格式为 JSON 数组，例如：

```env
WEBRTC_ICE_SERVERS=[{"urls":["stun:stun.l.google.com:19302"]},{"urls":["turn:your-turn-host:3478?transport=udp","turn:your-turn-host:3478?transport=tcp"],"username":"demo","credential":"demo-pass"}]
```

说明：

- `stun` 仅适合基础联调
- 真机、跨网络或复杂 NAT 场景，建议补 `turn`
- 修改该环境变量后，需要重启 `server`

## 本次新增的 WebRTC Demo

仓库现已补充一套可直接联调的 WebRTC 音视频 Demo：

- Android 客户端：`clients/android/webrtcdemo`
- Node 信令服务：`server/`

默认 STUN：

```text
stun:stun.timemotion.top:3478
```

启动服务：

```bash
cd server
npm install
npm run dev
```

Android 默认信令地址：

```text
ws://10.0.2.2:3000/ws
```

说明：

- Android 模拟器可直接使用 `10.0.2.2`
- 真机联调请将地址改为宿主机局域网 IP，例如 `ws://192.168.2.109:3000/ws`
- 同一个 `roomId` 下进入两个设备即可自动协商音视频通话
