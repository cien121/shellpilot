# ShellPilot Milestone 2 — SSH 连接 + 终端

## 功能
- 添加服务器：别名 / 地址 / 端口（默认 22）/ 用户名 / 密码
- 点服务器连接（10 秒超时），输密码，连上进全屏终端
- 终端：黑色背景、等宽字体，输出区 + 底部输入框，可跑命令看输出
- 长按服务器删除
- arm64-v8a 分包

## 技术说明
- termlib 未启用（要求 compileSdk 37，改动大）。MVP 用自研简单终端：
  `TerminalBridge` 把 SSHJ shell 的 output 流 pump 进 StateFlow，输入行发到 input 流。
  完整终端仿真（termlib + libvterm）列为后续里程碑。
- SSHJ 密码认证，`PromiscuousVerifier`（host key 校验 UI 后续加）。
- 密码不入库，仅内存传递。

## 构建
- 待触发
