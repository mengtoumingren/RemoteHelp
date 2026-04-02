# RemoteHelp 安全风险台账

更新时间：2026-04-02  
范围：Android 客户端 + Node.js 信令服务 + 仓库治理

## 使用说明

- 本文档用于跟踪当前已识别的安全风险与整改进度。
- 建议按严重度顺序处理：严重 -> 高 -> 中 -> 中低。
- 每一项都包含：风险描述、代码证据、修复建议、验收标准、处理状态。

状态说明：
- `待处理`：尚未开始
- `处理中`：已开始但未完成
- `已完成`：修复完成并验证
- `已接受`：业务确认暂不修复并接受风险

---

## 风险清单

### R-001（严重）明文传输（HTTP/WS）可被中间人窃听与篡改

- 状态：`已完成`
- 风险等级：严重
- 影响面：邀请接口、信令链路、令牌、手机号
- 风险描述：
  - 客户端允许 `ws://` 与 `http://`，且全局允许明文流量。
  - 在非可信网络下可被抓包或篡改消息。
- 代码证据：
  - `clients/android/mainproject/app/src/main/AndroidManifest.xml:27`
  - `clients/android/mainproject/app/src/main/java/com/timemotion/remotehelp/core/RemoteHelpSettingsManager.kt:50`
  - `clients/android/mainproject/app/src/main/java/com/timemotion/remotehelp/core/ServerApiClient.kt:62`
- 修复建议：
  1. 生产构建强制仅允许 `https://` + `wss://`。
  2. 关闭 `usesCleartextTraffic` 或通过 network security config 仅对白名单调试域名放开。
  3. 在 UI/配置校验中拒绝 `ws://`。
- 验收标准：
  - 生产包中无法保存/连接 `ws://` 地址。
  - 抓包验证令牌与信令仅走 TLS。

### R-002（高危）邀请接口缺少调用方鉴权，存在滥用风险

- 状态：`已完成`
- 风险等级：高危
- 影响面：会话创建、资源消耗、社工攻击面
- 风险描述：
  - `/invites`、`/invites/resolve` 公开可调用，无调用方身份校验。
- 代码证据：
  - `server/index.js:318`
  - `server/index.js:346`
- 修复建议：
  1. 增加服务端鉴权（如 API Key、JWT、签名时间窗）。
  2. 增加 IP/设备维度限流与行为审计。
  3. 对外暴露前加 WAF 或网关策略。
- 验收标准：
  - 未携带合法凭据请求返回 401/403。
  - 压测下超限请求被稳定拒绝。

### R-003（高危）服务端存在弱默认签名密钥

- 状态：`已完成`
- 风险等级：高危
- 影响面：令牌签名、房间鉴权
- 风险描述：
  - `TOKEN_SECRET` 有硬编码默认值，漏配环境变量时可被预测。
- 代码证据：
  - `server/index.js:11`
- 修复建议：
  1. 启动时强制校验：生产环境未配置强随机密钥则拒绝启动。
  2. 密钥改由安全配置中心或环境注入。
  3. 制定密钥轮换策略。
- 验收标准：
  - 生产环境无默认密钥兜底。
  - 密钥长度与复杂度满足策略要求。

### R-004（高危）敏感信息明文落盘且备份开启

- 状态：`已完成`
- 风险等级：高危
- 影响面：inviteToken、channelToken、TURN 密码
- 风险描述：
  - 会话令牌和 TURN 密码存储在 SharedPreferences。
  - 应用启用自动备份，备份规则未明确排除敏感字段。
- 代码证据：
  - `clients/android/mainproject/app/src/main/java/com/timemotion/remotehelp/core/LocalHistoryStore.kt:37`
  - `clients/android/mainproject/app/src/main/java/com/timemotion/remotehelp/core/LocalHistoryStore.kt:38`
  - `clients/android/mainproject/app/src/main/java/com/timemotion/remotehelp/core/ConnectionSettingsStore.kt:46`
  - `clients/android/mainproject/app/src/main/AndroidManifest.xml:20`
  - `clients/android/mainproject/app/src/main/res/xml/backup_rules.xml:1`
  - `clients/android/mainproject/app/src/main/res/xml/data_extraction_rules.xml:1`
- 修复建议：
  1. 敏感字段改为加密存储（如 EncryptedSharedPreferences）。
  2. 令牌最小化持久化，不必要不落盘。
  3. 调整备份规则排除敏感配置，必要时关闭备份。
- 验收标准：
  - 本地存储中不再出现明文令牌/密码。
  - 备份数据不包含敏感字段。

### R-005（中危）WebSocket 缺少来源校验

- 状态：`已完成`
- 风险等级：中危
- 影响面：连接滥用、跨站发起连接
- 风险描述：
  - WebSocket 连接处理未见 Origin/来源白名单校验。
- 代码证据：
  - `server/index.js:376`
- 修复建议：
  1. 在握手阶段校验 Origin/Host/协议头。
  2. 非法来源直接拒绝连接并记录日志。
- 验收标准：
  - 白名单外来源无法建立连接。

### R-006（中危）默认监听 0.0.0.0，暴露面偏大

- 状态：`已完成`
- 风险等级：中危
- 影响面：服务暴露、扫描攻击面
- 风险描述：
  - 默认监听所有网卡，且文档默认示例为局域网开放。
- 代码证据：
  - `server/index.js:7`
  - `README.md:33`
  - `README.md:34`
- 修复建议：
  1. 默认改为 `127.0.0.1`，部署时显式配置外网监听。
  2. 增加防火墙/安全组限制来源。
- 验收标准：
  - 未配置时仅本机可访问。

### R-007（中危）仓库敏感资产治理不足

- 状态：`已完成`
- 风险等级：中危
- 影响面：误提交密钥、环境信息泄露
- 风险描述：
  - 根目录存在二进制 `key` 文件，用途不明。
  - Android `local.properties` 已提交。
  - 根 `.gitignore` 覆盖范围较窄。
- 代码证据：
  - `key`
  - `clients/android/mainproject/local.properties:1`
  - `.gitignore:1`
- 修复建议：
  1. 明确 `key` 文件用途，若为密钥/证书应移出仓库并轮换。
  2. 删除并忽略 `local.properties`。
  3. 完善 `.gitignore` 与密钥扫描流程（pre-commit/CI）。
- 验收标准：
  - 仓库中无敏感密钥材料。
  - CI 增加 secret scan 并通过。

### R-008（中低）Release 未开启混淆压缩

- 状态：`待处理`
- 风险等级：中低
- 影响面：逆向门槛较低
- 风险描述：
  - Release 构建未启用混淆压缩，暴露更多实现细节。
- 代码证据：
  - `clients/android/mainproject/app/build.gradle.kts:25`
- 修复建议：
  1. 启用 R8/Proguard。
  2. 配置保留规则并进行回归测试。
- 验收标准：
  - release APK 已混淆且功能回归通过。

---

## 建议处理顺序（可执行）

1. R-001 明文传输
2. R-003 默认弱密钥
3. R-002 接口鉴权与限流
4. R-004 敏感存储与备份
5. R-007 仓库治理
6. R-005 / R-006 服务暴露面加固
7. R-008 混淆压缩

---

## 变更记录

- 2026-04-02：初版台账创建。
- 2026-04-02：完成 R-001 第一轮修复（Release 禁止明文传输，Debug 保留联调能力）。
- 2026-04-02：完成 R-002 第一轮修复（邀请接口增加 API Key 鉴权与限流，客户端支持鉴权头）。
- 2026-04-02：R-002 增强：服务端支持首次启动自动生成并持久化 API Key，安卓设置页可配置 API Key。
- 2026-04-02：完成 R-003 修复（移除 TOKEN_SECRET 弱默认值，增加强度校验与生产环境启动强制策略）。
- 2026-04-02：完成 R-004 修复（敏感偏好迁移至加密存储，并从备份/迁移规则中排除）。
- 2026-04-02：完成 R-005 修复（WebSocket 握手增加 Origin/Host 校验与可配置来源策略）。
- 2026-04-02：完成 R-006 修复（服务端默认监听改为 127.0.0.1，文档同步最小暴露面策略）。
- 2026-04-02：完成 R-007 修复（清理本地配置与密钥文件、完善 .gitignore、增加 CI secret scan）。
