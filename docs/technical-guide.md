# 技术文档

本页面面向开发者，描述项目分层、模块职责、信令协议和当前实现边界。

## 总体架构

项目由两部分组成：

- `server/`：轻量联调服务，负责房间管理和消息转发
- `clients/android/mainproject`：把验证、会话和远程协助整合到一个主应用中

主流程是：

1. 创建一次性协助请求
2. 通过 Deep Link 或 token 拉起长辈端
3. 进行音视频核验
4. 进入远程协助控制台
5. 通过信令服务交换控制命令、状态和媒体协商数据

## 服务端设计

### 技术栈

- Node.js
- Express
- `ws`

### HTTP 接口

- `GET /health`：健康检查
- `GET /config`：下发 WebSocket 路径和 ICE 配置

`/config` 当前返回：

- `wsUrlPath: "/ws"`
- `iceServers`：包含 `STUN_URL` 和 `TURN_URL`

### WebSocket 入口

- 路径：`/ws`

### WebRTC 房间模型

服务端使用内存 `Map` 管理 WebRTC 房间，当前约束是：

- 一个房间最多 2 个 peer
- 只负责信令转发，不处理媒体流
- 断开连接时自动清理房间状态

支持的消息类型：

- `join`
- `leave`
- `signal`

`signal` 用于转发 `offer`、`answer` 和 `candidate`。

### 远程控制房间模型

远程控制房间和 WebRTC 房间是独立的内存结构，支持：

- `rc_join`
- `rc_leave`
- `rc_frame`
- `rc_target_status`
- `rc_command`
- `rc_signal`

约束如下：

- 同一房间内 `controller` 只能有一个
- 同一房间内 `target` 只能有一个
- `target` 负责上报帧和设备状态
- `controller` 负责发送控制命令

## Android 主应用

### 入口

主入口是 `clients/android/mainproject/app/src/main/java/com/timemotion/remotehelp/MainActivity.kt`。

它做了几件事：

- 启动并绑定前台服务
- 收集深链
- 驱动主界面路由
- 在恢复前台时刷新本地能力状态

### 路由和页面

主路由在 `clients/android/mainproject/app/src/main/java/com/timemotion/remotehelp/ui/RemoteHelpRoute.kt`。

页面按状态切换为：

- `DASHBOARD`
- `SESSION`
- `VERIFICATION`
- `ASSIST`

### 核心模块

- `core/`：邀请、历史、校验、设置、格式化和协调器
- `remote/`：远程协助前台服务、无障碍服务、屏幕采集和控制器
- `webrtc/`：WebRTC 信令和通话控制
- `ui/`：Compose 页面

### Deep Link

Manifest 当前支持两类入口：

- `https://help.yourdomain.com/r/{token}`
- `remotehelp://request`

其中 `help.yourdomain.com` 目前是示例域名，后续需要替换为真实部署域名。

### 权限

主应用依赖的关键权限包括：

- 相机
- 麦克风
- 网络
- 通知
- 悬浮窗
- 前台服务
- 屏幕采集
- 无障碍服务

## 关键实现边界

当前仓库更接近 MVP 骨架，以下能力还不是完整生产实现：

- 服务端持久化
- 短信供应商接入
- 账户体系和风控
- TURN 服务的完整运维配置
- 生产级审计与证据链存储

如果要继续演进，优先顺序通常是：

1. 将邀请和状态持久化到数据库
2. 把 token 生成和校验迁移到后端
3. 接入正式短信和 TURN
4. 为远程控制增加更完整的审计记录
