# MCAI — Minecraft AI 玩家

让一个 AI 玩家（glm-5.3-flash）以真实协议进入 Minecraft 1.21.11 服务器：@它聊天就会回复，**言出法随**（自然语言指挥它走路/挖掘/攻击/执行指令）、**死亡自动重生、低饥饿自动吃**（纯本地端，无需服务器插件）、支持离线/微软登录、自定义皮肤、Simple Voice Chat 语音对话。

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

游戏里任何玩家发送 **`@机器人名字 你的问题`** 即触发回复（回复前缀 `[机器人名字]`）。说话带行动意图时它会**言出法随**：

| 你说 | 它做 |
|---|---|
| `@bot 走到 100 -50` | 世界感知行走（读区块贴地，1 格台阶可跨，撞墙/深崖自动停） |
| `@bot 跟我来` / `@bot 跟着那只猪` | 跟随玩家（本地实体跟踪）或生物 |
| `@bot 挖` / `@bot 攻击` | 挖脚下方块 / 攻击最近的敌对生物 |
| `@bot 吃` / `@bot 停` | 吃快捷栏食物 / 停止移动 |
| `@bot /give 我 bread` | 执行任意服务器指令 |

有 API key 时由 GLM 规划动作序列（LLM 意图解析）；无 key 自动降级为关键词规则。**生存基础为纯本地端**：死亡自动回重生包、饥饿低于阈值自动吃快捷栏食物，全程不需要在服务器装任何东西。

## 模块

| 模块 | 产物 | 说明 |
|---|---|---|
| `core/` | 库 | 协议连接(MCBot)、聊天触发(ChatHandler)、AI(AIBrain)、**世界模型(world/)**、意图/动作(IntentParser/ActionExecutor)、语音(voice/)、皮肤(skin/)、配置(config/) |
| `gui/` | `build/libs/mcaibridge-1.0.0-all.jar` | iOS 17 风格 JavaFX（明暗主题跟随系统+手动切换、中英双语、服务器列表、总设置） |
| `paper/` | `paper/build/libs/mcaibridge-paper-1.0.0.jar` | **可选**伴生插件：皮肤服务端注入 + SVC 语音中继 + HTTP API（聊天广播兜底/查坐标） |

## 配置（config.yml）

```yaml
servers:                                        # 独立服务器列表（像 MC 客户端添加服务器）
  - { name: "本地测试", host: "localhost", port: 25565 }
  - { name: "远程生存服", host: "play.example.com", port: 25565 }
ai:      { model: "glm-5.3-flash", api_key: "${ZAI_API_KEY}" }   # 未设置则 mock 模式
survival:
  auto_respawn: true                            # 死亡自动重生（纯本地端）
  auto_eat: true                                # 低饥饿自动吃
  eat_below_food: 10
players:                                        # AI 角色：只填 名字+账户+server 名字引用
  - { name: "BridgeBot", auth: "offline", server: "本地测试", svc: true }
  - { name: "MasterJane", auth: "microsoft", server: "远程生存服", svc: false }
voice:   { enabled: true, server_port: 8787 }   # 角色级 svc 开关控制谁用语音
```

环境变量 `${VAR}` 会被自动替换。`players[]` 缺省时由旧版单档案字段兼容合成；角色也可直接写 `host/port`（不引用 servers）。

## 世界模型（纯本地端言出法随的地基）

机器人不装任何 mod/插件，靠客户端协议数据在本地重建世界：

```
服务器区块包（原始字节） → WorldModel 手写解码（1.21.11 格式实测校准：palette 对齐打包、无长度前缀）
→ 区块/方块缓存 + BlockUpdate 增量 → groundY/standable 查询 → 行走贴地与阻挡判断
EntityTracker：实体增/移/删 + 玩家名单(UUID→名字) → "跟着某人/打某生物"目标解析
SurvivalManager：血量/饥饿/快捷栏跟踪 → 自动重生 + 自动吃
```

## 皮肤（离线服可见）

机器人自己的登录协议无法让服务器采纳皮肤（服务端权威）。本项目方案：`paper/` 插件在玩家登录时把皮肤 textures 属性注入档案 —— **所有原版客户端无需安装任何东西即可看到**。流程：GUI 选择 64x64/64x32 PNG → 连接时自动上传到插件（8788 端口）→ 重连后生效。纯本地端模式（不装插件）下，皮肤仅微软正版账号可用（跟随账号设置）。

## 语音对话（Simple Voice Chat，可选）

```
玩家按住说话 → SVC插件捕获 → Opus解码+静音切句 → HTTP → core(ASR→AI→TTS) → 插件 SVC AudioPlayer 全服广播
```

语音现在是**角色级开关**：`players[].svc` 显式 `true` 才会使用（GUI 角色编辑里有 SVC toggle；全局 TTS/ASR 配置收在"设置"对话框）。只要没有任何 svc 角色在线，8787 服务就不启动。默认关闭——纯本地端玩法完全不需要装插件。

安装（仅语音需要）：把 `plugins/voicechat-bukkit-*.jar`（Modrinth 下载）与 `mcaibridge-paper-1.0.0.jar` 放入服务器 `plugins/`，插件 `config.yml` 里 `voice.bridge_url` 指向 bridge 的 8787 端口。ASR 用 OpenAI 兼容 HTTP 服务（Groq / faster-whisper-server 均可）；TTS 默认 Z.ai GLM-TTS（Edge-TTS 备选）。

## 已知注意事项

1. **机器人名字只能用 ASCII**（字母/数字/下划线，≤16 字符）——MC 1.21 登录协议硬性校验，中文名会被服务器拒绝。
2. Paper 1.21.11-132 对"协议机器人"的外发聊天存在**间歇性静默丢弃**（复现于 40 行独立客户端，与本项目无关，原版服务器无此问题）。项目已内置 `reply_via: plugin` 兜底：AI 回复经伴生插件广播，绕开该问题。
3. 频繁 `kill -9` 服务器可能导致下次启动奇慢（session.lock / 世界恢复），用正常 `stop` 关闭。

## 构建

```bash
./gradlew build        # 产出 build/libs/mcaibridge-1.0.0-all.jar + paper/build/libs/mcaibridge-paper-1.0.0.jar
```

依赖：MCProtocolLib `org.geysermc.mcprotocollib:protocol:1.21.11-SNAPSHOT`（repo.opencollab.dev）、SnakeYAML、SLF4J、JavaFX 21；paper 模块依赖 paper-api + Simple Voice Chat API（`paper/libs/` 内置 jar）。
