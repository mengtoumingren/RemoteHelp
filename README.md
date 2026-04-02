# RemoteHelp

RemoteHelp 是一个面向家庭远程协助场景的 Android + Node.js 项目，当前仓库包含：

- 统一的 Android 主应用 `clients/android/mainproject`
- 用于联调的 Node.js 信令服务 `server/`

## 文档入口

- [使用文档](./docs/user-guide.md)
- [技术文档](./docs/technical-guide.md)
- [主应用落地说明](./clients/android/mainproject/IMPLEMENTATION.md)
- [服务端说明](./server/README.md)

## 项目结构

- `server/`：Express + WebSocket 联调服务，提供健康检查、配置下发和房间信令转发
- `clients/android/mainproject/`：统一主应用，包含邀请、验证、会话和远程协助流程
- `docs/`：项目说明文档

## 快速开始

### 1. 启动服务端

```bash
cd server
npm install
npm run dev
```

默认监听：

- `http://127.0.0.1:3000`
- `ws://127.0.0.1:3000/ws`

### 2. 运行 Android 主应用

使用 Android Studio 打开 `clients/android/mainproject`，直接运行 `mainproject` 模块。

调试时默认信令地址为：

- 模拟器：`ws://10.0.2.2:3000/ws`
- 真机：`ws://<宿主机局域网IP>:3000/ws`

### 3. 查看服务是否可用

```text
GET /health
GET /config
```

## 当前能力

- 生成一次性协助请求
- 支持 Deep Link 拉起流程
- 通过 WebRTC 完成音视频验证
- 进入远程协助控制台
- 支持屏幕采集和无障碍远程操作
- 保留本地会话和日志能力的基础框架

## 说明

- `help.yourdomain.com` 是当前 Deep Link 的示例域名，部署时需要替换成真实域名。
- 当前服务端以内存房间为主，适合本地联调和 MVP 验证，不适合作为生产持久化实现。
