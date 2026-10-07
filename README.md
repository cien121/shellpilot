# ShellPilot

Android SSH 客户端：终端 + 文件传输（SFTP，可打开/修改远程文件）+ 脚本命令保存。

参考：LobiShell。黑色主题，手机优先。

## 技术栈（全 Apache 2.0）

| 模块 | 库 |
|---|---|
| SSH / SFTP | [SSHJ](https://github.com/hierynomus/sshj) 0.41.1 |
| 终端仿真 | [connectbot/termlib](https://github.com/connectbot/termlib) 0.3.11（libvterm + Compose） |
| UI | Jetpack Compose + Material3 |
| 本地存储 | Room（服务器配置、Snippets） |
| 加密 | BouncyCastle 完整版（Android 自带阉割版无 X25519，App 启动时优先注册） |

## 包结构

```
com.chan.shellpilot/
├── ui/        # Compose 界面（服务器列表、终端页、文件页）
├── ssh/       # SSHJ 封装（连接管理、Shell 会话）
├── data/      # Room（Server、Snippet 实体/DAO）
└── terminal/  # termlib 接线（SSH 输出 <-> 终端显示）
```

## 构建

```bash
./gradlew assembleDebug    # 调试包
./gradlew assembleRelease  # 发布包
```

或推到 `main` 分支，GitHub Actions 自动编译并上传 APK 到 Artifacts。

## 路线图

- [x] 项目骨架（可编译）
- [ ] SSH 连接（密码/密钥、known_hosts）
- [ ] 终端页（输入、缩放、滚动）
- [ ] SFTP 浏览 + 内置文本编辑器
- [ ] Snippets（保存/一键执行/连接后自启）
- [ ] 端口转发
