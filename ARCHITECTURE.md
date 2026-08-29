# MCAI Bridge 架构设计

Minecraft 1.21.11 AI 玩家接入工具。协议库：MCProtocolLib（GeyserMC 分支 `org.geysermc.mcprotocollib:protocol:1.21.11-SNAPSHOT`，仓库 `repo.opencollab.dev/main/`；原 jitpack steveice10 已归档，不支持 1.21.11，经确认改用本坐标）。GUI：JavaFX 21。构建：Gradle 8.14 + Shadow fat JAR。Java 21。

## 模块图

```
┌──────────────────────────── com.mcaibridge ────────────────────────────┐
│                                                                        │
│  Main ──── 启动模式: GUI / --headless / --probe                        │
│   │                                                                    │
│   ├── gui/          MainWindow ◄─ ConfigPanel, LogPanel                │
│   │                     │ 用户点击"连接"                                │
│   ├── core/         MCBot ──── ChatHandler ──── AIBrain                │
│   │                  │             │ @名字触发      │ Z.ai API        │
│   ├── auth/         OfflineAuth / MicrosoftAuth ──► AuthResult         │
│   └── config/       BridgeConfig ◄── config.yml + ${ENV} 插值          │
└────────────────────────────────────────────────────────────────────────┘
```

## 类列表与职责

| 类 | 职责 |
|---|---|
| `Main` | 入口。解析 `--config/--headless/--probe`；加载配置；分发到 GUI 或无头模式。普通类（非 javafx.Application），fat jar `java -jar` 可直接启动 |
| `gui.MainWindow` | JavaFX 主窗口（extends Application，由 Main 以 `Application.launch` 拉起）。布局：上=ConfigPanel，中=LogPanel，下=连接/断开按钮 |
| `gui.ConfigPanel` | 表单：名字/服务器地址/端口/登录方式(offline|microsoft)/API Key/模型。点连接时收集为 BridgeConfig |
| `gui.LogPanel` | 时间戳日志TextArea，供 core 层回调追加；上限行数防内存膨胀 |
| `core.MCBot` | 连接生命周期：`ClientNetworkSessionFactory.factory().setAddress(host,port).setProtocol(protocol).create()`；注册 SessionAdapter；`AUTOMATIC_KEEP_ALIVE_MANAGEMENT`；状态机 DISCONNECTED→CONNECTING→CONNECTED；断线自动重连（指数退避，可配上限）；对外暴露 `sendChat(String)`（`ServerboundChatPacket`，离线无签名：salt=0/signature=null）与 `shutdown()` |
| `core.ChatHandler` | SessionAdapter.packetReceived：`ClientboundPlayerChatPacket`（sender/name/content/unsignedContent）与 `ClientboundSystemChatPacket`（adventure Component 展平为纯文本，含 TranslatableComponent args 递归）。检测 `@botName`（排除自己），提取问题文本，投递到单线程 Executor 异步调 AIBrain，回复经 `[botName] ` 前缀回发聊天。节流：同一时刻仅1个pending回复 |
| `core.AIBrain` | java.net.http.HttpClient 调 Z.ai OpenAI 兼容端点（`ai.base_url`，POST Bearer key，model=glm-5.3-flash，system prompt 可配）。超时(默认30s)/1次重试。`api_key` 为空 → mock 模式（固定回显+WARN 日志），保证无 key 时全链路可测 |
| `auth.OfflineAuth` | `new MinecraftProtocol(username)`（库自动生成离线随机UUID+无令牌）；产出 AuthResult |
| `auth.MicrosoftAuth` | MinecraftAuth 5.x 设备码流：`JavaAuthManager.create(MinecraftAuth.createHttpClient()).login(DeviceCodeMsaAuthService::new, callback)`；callback 弹 Swing 模态窗展示 verificationUri + userCode（复制按钮）；成功后取 profile/token → `new MinecraftProtocol(new GameProfile(id,name), token)`。client_id 可配置（留空用库默认） |
| `auth.AuthResult` | record：GameProfile、accessToken(nullable)、MinecraftProtocol、来源(offline/microsoft) |
| `config.BridgeConfig` | SnakeYAML 加载 + `${ENV_VAR}` 递归插值（缺失置空+WARN）。字段见 config.template.yml。静态 `load(Path)` 与 GUI 场景的构造注入两种来源 |

## 关键流程

1. **启动**：Main → BridgeConfig.load → （GUI：显示窗口等待用户点连接；headless：直接连；probe：自检模式）
2. **认证**：bot.auth=offline → OfflineAuth；microsoft → MicrosoftAuth 弹设备码窗 → AuthResult(MinecraftProtocol)
3. **连接**：MCBot.connect() → factory.create + SessionAdapter（connected→状态CONNECTED；disconnected→记录原因+计划重连）
4. **聊天触发**：服务器 → packetReceived(PlayerChat/SystemChat) → ChatHandler 展平文本 → 含 `@测试Jane` 且非本人 → 异步 AIBrain.chat(question) → 回复加前缀 → MCBot.sendChat → `ServerboundChatPacket`
5. **probe 自检**（降级测试路径）：以 `probe.name`(TestPlayer) 第二客户端加入，发 `@<bot> 你好`，≤25s 内监听到含回复前缀的聊天 → 打印 `PROBE_RESULT=PASS`（超时 FAIL，exit code 1）

## 异常处理

| 场景 | 处理 |
|---|---|
| 连接被拒/超时/认证失败 | disconnected(cause) 记录原因 Component；按指数退避重连(2s→4s→…上限60s，次数可配)；GUI/日志提示 |
| AI 调用超时/非200 | WARN 日志，聊天内回复 `[bot] (AI 暂时无法回复)`；1次重试后放弃该条 |
| API key 缺失 | 启动时 WARN 进入 mock 模式，不中断 |
| 网络包解析异常 | packetReceived 外层 try/catch，单包异常不影响会话 |
| 优雅退出 | shutdown hook + GUI 关闭：session.disconnect()，Executor 优雅关闭(5s) |
| 配置缺字段 | 回退默认值并 WARN；无法回退的（如 host）报错退出 code 2 |

## 已知设计约束

- 1.21.11 签名聊天：离线客户端无签名密钥 → 发送未签名聊天（salt=0, signature=null）。服务端需 `online-mode=false` 且 `enforce-secure-profile=false`（测试服务端已按此配置）。
- `ServerboundChatPacket` 构造参数以 1.21.11-SNAPSHOT 实际 jar 为准（master 已加 checksum 参数）；编译期用 javap 校准。
- fat jar 含 JavaFX mac-aarch64 natives，仅在 macOS arm64 上 `java -jar` 可用（项目目标平台即此）。

---

# 第二迭代增量（2026-08-29）：多模块 + 皮肤 + 语音 + PCL GUI

## 模块重构

```
settings.gradle: include 'core','gui','paper'（依赖仓库集中在 settings 管理）
core/  java-library: config、auth、core、voice、skin（无 GUI 依赖）
gui/   application+shadow+javafx: Main、gui/*、pcl.css；depends(core)
paper/ java: BridgePlugin、SkinService、ApiServer、SkinLoginListener、VoiceRelay、AudioUtil（仅 paper-api + SVC API，编译期隔离）
```
根 `build/libs/mcaibridge-1.0.0-all.jar` 路径由 gui 的 build 任务 copy 保持不变。

## 皮肤链路

GUI 选择 PNG（64x64/64x32 校验，SkinPreview 平面预览）→ OfflineAuth 注入 textures 属性（客户端侧，展示用途）→ 连接时 SkinManager.upload → paper 插件 ApiServer(8788) `POST /mcai/skin` 存储 → SkinLoginListener 在 AsyncPlayerPreLoginEvent 注入 textures（服务端权威，全部原版客户端可见）→ `GET /mcai/skinimg/<name>.png` 供客户端拉取皮肤图。

## 语音链路（SVC 中继模式）

```
玩家麦克风 → SVC MicrophonePacketEvent → OpusDecoder → 能量VAD(静音900ms切句,上限12s)
→ HTTP POST /mcai/voice(48k mono WAV) → core VoiceServer: AsrEngine → AIBrain → TtsEngine
→ {asr, reply, audio(base64 WAV 48k)} → 插件 SVC AudioPlayer 广播(all|nearby) + 文字播报
```
AsrEngine: `MockAsrEngine`（默认）/ `WhisperHttpAsrEngine`（OpenAI 兼容 /audio/transcriptions）。
TtsEngine: `EdgeTtsEngine`（WSS+Sec-MS-GEC，失败回退文字）/ `OffTtsEngine`。
Opus 编解码使用 SVC API 自带 `createEncoder/createDecoder`（插件侧），core 定义 OpusCodec 扩展点。

## GUI（PCL 风格）

pcl.css：#1E1E2E 暗底 / #2A2B3A 圆角 12 卡片 / rgba 半透明毛玻璃近似 / 状态灯（绿黄红灰）/ hover 缩放动画。
MainWindow：Logo 区 + 快速开始 + AI 玩家卡片列表（ProfileCard/ProfileDialog，多档案=多 MCBot 实例）+ 可折叠日志。
多档案：`players[]` ↔ PlayerProfile，`BridgeConfig.merge(profile)` 产出兼容旧类的生效配置。

## 兼容性

- `players[]` 缺省时由旧字段合成单档案；旧 config.yml 无需修改。
- 根目录 fat JAR 路径与启动方式不变；新增 `--profile <名>`。
