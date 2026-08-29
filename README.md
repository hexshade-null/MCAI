# MCAI Bridge — Minecraft AI 玩家接入工具

让一个 AI 玩家（glm-5.3-flash）以真实协议进入 Minecraft 1.21.11 服务器：@它聊天就会回复，支持离线/微软登录、自定义皮肤（服务端注入）、Simple Voice Chat 语音对话。

## 快速开始

```bash
# 0) 要求：Java 21（brew install openjdk@21）
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

# 1) 启动 GUI（首次运行自动生成 config.yml）
java -jar build/libs/mcaibridge-1.0.0-all.jar

# 2) 无头模式 / 纯协议自检 / 指定档案
java -jar build/libs/mcaibridge-1.0.0-all.jar --config config.yml --headless
java -jar build/libs/mcaibridge-1.0.0-all.jar --config config.yml --probe
java -jar build/libs/mcaibridge-1.0.0-all.jar --config config.yml --profile AI_Jane
```

游戏里任何玩家发送 **`@机器人名字 你的问题`** 即触发回复（回复前缀 `[机器人名字]`）。

## 模块

| 模块 | 产物 | 说明 |
|---|---|---|
| `core/` | 库 | 协议连接(MCBot)、聊天触发(ChatHandler)、AI(AIBrain)、语音(voice/)、皮肤(skin/)、配置(config/) |
| `gui/` | `build/libs/mcaibridge-1.0.0-all.jar` | PCL 风格 JavaFX（暗色卡片、多 AI 玩家档案列表、皮肤预览） |
| `paper/` | `paper/build/libs/mcaibridge-paper-1.0.0.jar` | Paper 伴生插件：皮肤服务端注入 + SVC 语音中继 + HTTP API |

## 配置（config.yml）

```yaml
bot:    { name: "AI_Jane", auth: "offline" }   # auth: offline | microsoft
server: { host: "localhost", port: 25565 }
ai:
  api_key: "${ZAI_API_KEY}"                    # 未设置则 mock 模式
  model: "glm-5.3-flash"
skin:   { file: "skin.png", model: "classic", upload_url: "http://localhost:8788/mcai/skin", token: "changeme" }
voice:
  enabled: true                                # 需要 paper 插件 + Simple Voice Chat
  asr: { provider: "mock" }                    # mock | whisper-http(OpenAI兼容 /audio/transcriptions)
  tts: { provider: "edge", voice: "zh-CN-XiaoxiaoNeural", fallback_text: true }
players:                                        # 多 AI 玩家（GUI 卡片列表）
  - { name: "AI_Jane", host: "localhost", port: 25565 }
```

环境变量 `${VAR}` 会被自动替换。`players[]` 缺省时由旧版单档案字段兼容合成。

## 皮肤（离线服可见）

机器人自己的登录协议无法让服务器采纳皮肤（服务端权威）。本项目方案：`paper/` 插件在玩家登录时把皮肤 textures 属性注入档案 —— **所有原版客户端无需安装任何东西即可看到**。流程：GUI 选择 64x64/64x32 PNG → 连接时自动上传到插件（8788 端口）→ 重连后生效。

- 正版登录：皮肤跟微软账号走（minecraft.net 设置）。
- 单机"对局域网开放"世界：无法装 Paper 插件，只能观看者端方案（CustomSkinLoader / 启动器离线皮肤 / 皮肤站）。

## 语音对话（Simple Voice Chat）

```
玩家按住说话 → SVC插件捕获 → Opus解码+静音切句 → HTTP → core(ASR→AI→TTS) → 插件 SVC AudioPlayer 全服广播
```

安装：把 `plugins/voicechat-bukkit-*.jar`（Modrinth 下载）与 `mcaibridge-paper-1.0.0.jar` 放入服务器 `plugins/`，插件 `config.yml` 里 `voice.bridge_url` 指向 bridge 的 8787 端口。ASR 用 OpenAI 兼容 HTTP 服务（Groq / faster-whisper-server 均可）；TTS 默认 Edge-TTS（免费，（默认，见 config.template.yml；Edge-TTS 备选））。

## 已知注意事项

1. **机器人名字只能用 ASCII**（字母/数字/下划线，≤16 字符）——MC 1.21 登录协议硬性校验，中文名会被服务器拒绝。
2. Paper 1.21.11-132 对"协议机器人"的外发聊天存在**间歇性静默丢弃**（复现于 40 行独立客户端，与本项目无关，原版服务器无此问题）。项目已内置 `reply_via: plugin` 兜底：AI 回复经伴生插件广播，绕开该问题。
3. 频繁 `kill -9` 服务器可能导致下次启动奇慢（session.lock / 世界恢复），用正常 `stop` 关闭。

## 构建

```bash
./gradlew build        # 产出 build/libs/mcaibridge-1.0.0-all.jar + paper/build/libs/mcaibridge-paper-1.0.0.jar
```

依赖：MCProtocolLib `org.geysermc.mcprotocollib:protocol:1.21.11-SNAPSHOT`（repo.opencollab.dev）、SnakeYAML、SLF4J、JavaFX 21；paper 模块依赖 paper-api + Simple Voice Chat API（`paper/libs/` 内置 jar）。
