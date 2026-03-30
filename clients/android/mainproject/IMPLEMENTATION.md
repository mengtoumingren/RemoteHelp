# mainproject 落地说明

## 本次落地范围

`app` 已从空白模板升级为统一主应用，围绕 `BaseResolution.txt` 落地了以下主流程：

1. 子女端输入长辈手机号并生成一次性协助请求
2. 生成带签名的 deep link / token，模拟短信拉起入口
3. 长辈端识别链接后进入视频验证
4. 双方通过 WebRTC 完成音视频核验
5. 长辈端手动接受后进入远程协助控制台
6. 长辈端授权屏幕采集与无障碍服务，子女端进行远程点击/滑动/输入
7. 本地保存最近联系人与会话留痕

## 代码结构

- 音视频验证由主应用内的 `webrtc` 模块负责
- 远程协助由主应用内的 `remote` 模块负责
- `server/index.js` 现有信令协议可直接继续服务 `mainproject`

## 当前实现策略

- 短信发送与正式后端接口暂由“客户端本地签名 token”模拟
- Manifest 已支持 `https://help.yourdomain.com/r/{token}` 与 `remotehelp://request`
- 后续可把 token 生成迁移到正式服务端
