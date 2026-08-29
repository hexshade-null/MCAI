# MCAI Bridge 测试报告

**日期**: 2026-08-29 | **测试人**: 测试工程师（自动化） | **结论**: ✅ 通过（降级路径）

## 环境准备

| 步骤 | 结果 |
|---|---|
| 安装 Java 21（brew openjdk@21） | ✅（原系统仅 Java 17，1.21.11 强制要求 21） |
| Gradle 8.14 + wrapper | ✅ |
| Paper 下载（fill.papermc.io v3，`paper-1.21.11-132.jar`，构建 #132） | ✅（原 api.papermc.io v2 已停用） |
| Paper 配置（`online-mode=false`、`enforce-secure-profile=false`） | ✅ |
| Paper 启动 `java -Xmx1G -jar paper.jar --nogui` | ✅ Done (15.0s)，远低于 60s 超时 |

## 测试执行

| 步骤 | 结果 |
|---|---|
| test-config.yml（bot.name/ai.model=glm-5.3-flash/api_key 引 `${ZAI_API_KEY}`） | ✅ |
| `ZAI_API_KEY` 未设置 → AIBrain 自动 mock 模式（按预案跳过真实 AI 调用） | ✅ |
| Bridge 启动 `java -jar mcaibridge-1.0.0-all.jar --config test-config.yml --headless` | ✅ CONNECTED |
| Prism Launcher 路径 | ⚠️ 降级（见下） |
| 纯协议自检 `--probe`（TestPlayer 加入→发 `@AI_Jane 你好`→等待回复） | ✅ **PROBE_RESULT=PASS** |
| 判定：Paper `logs/latest.log` 出现机器人聊天 | ✅ |

## 关键日志证据

Paper 服务端（`testenv/paper/logs/latest.log`）——完整聊天闭环：

```
[15:55:13] AI_Jane joined the game
[15:55:32] <TestPlayer> @AI_Jane 你好
[15:55:32] <AI_Jane> [AI_Jane] 你好！我是 AI_Jane，收到: 你好（mock 回复：未配置 API Key）
```

Bridge 日志（`/tmp/bridge.log`）：

```
已连接服务器，玩家 AI_Jane 登录成功
收到触发消息 [TestPlayer]: @AI_Jane 你好
AI 回复: [AI_Jane] 你好！我是 AI_Jane，收到: 你好（mock 回复：未配置 API Key）
```

Probe 进程输出：`PROBE_RESULT=PASS`（exit 0）。

## 降级说明（按预案执行）

1. **Prism → headless**：Prism 实例目录为空（无 `MCAI-Test-1.21.11` 实例）。因实例创建+GUI 聊天自动化成本高，且本任务要求控制 token 消耗，按预案改用纯协议验证（`--probe`）。该路径验证了相同的协议链路（加入/聊天收发/AI 触发回复），证据确定性更高。
2. **截图**：无 GUI 游戏窗口，`screencapture` 不适用，按预案仅以日志判定。
3. **AI 真实调用**：`ZAI_API_KEY` 未设置，按预案以 mock 回复验证全链路。配置真实 key 后无需改代码即可启用（`export ZAI_API_KEY=...`）。

## 已发现问题（2 个，均已修复，详见 FIX_REPORT.md）

1. **编译期**：1.21.11-SNAPSHOT 实际 API 与 master 示例的 3 处偏差（缺 import、`disconnect` 签名、`TranslationArgument` 转换）→ javap 校准后修复。
2. **运行期**：MC 1.21 协议要求玩家名仅 ASCII（`[A-Za-z0-9_]`），中文名"测试Jane"被服务端拒绝（`invalid characters`）→ 测试配置改用 `AI_Jane`。**这是 MC 协议硬约束，不是产品缺陷**；生产环境 bot 名需使用 ASCII。
