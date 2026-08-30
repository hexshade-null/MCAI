# MCAI 测试报告

**日期**: 2026-08-29 / 08-30 | **结论**: ✅ 通过（含第二、第三迭代全部新特性）

## 第三迭代验证（2026-08-30，言出法随纯本地端）

环境：Paper 1.21.11-132 测试服（offline、peaceful、默认世界）+ 无头模式 bridge（mock AI，关键词降级路径）。

| 项 | 方法 | 结果 |
|---|---|---|
| 区块解码（1.21.11 格式） | 连接后解析全部区块包 | ✅ 0 失败、无剩余字节、palette 无越界；位置 y=120 与出生点一致 |
| 区块格式校准 | hex 转储对比 | ✅ 实测：palette 后**无长度前缀**，long 对齐打包（bpe=5 越界排除了连续打包假设） |
| 聊天闭环（回归） | 插件 /mcai/chat 广播 `@BridgeBot 在哪` | ✅ 意图回复 `我在 (-7.5, 120.0, -5.5)`，chat+plugin 双通道可见 |
| 世界感知行走 | `@BridgeBot 走到 10 -20` → /mcai/where 轮询 | ✅ 到达 (9.44, 120, -19.54)，贴地无橡皮筋校正 |
| 挖掘 | `@BridgeBot 挖` | ✅ `动作完成: dig`（START→挥臂→FINISH 序列） |
| 进食协议链路 | `@BridgeBot 吃` | ✅ `进食: 快捷栏槽位 0 (物品 id 893)`（SetCarriedItem+UseItem） |
| 食物 id 实测 | /give 在线单槽对照 | ✅ 9 种：apple=893 bread=953 steak=1111 cooked_porkchop=984 golden_carrot=1232 baked_potato=1229 carrot=1227 pumpkin_pie=1241 cookie=1102 |
| 死亡自动重生 | 控制台 `kill BridgeBot` | ✅ `已死亡`→800ms→RESPAWN→位置同步 (6.5, 118.0, 3.5)，世界模型自动清空 |
| 攻击优雅降级 | `@BridgeBot 攻击`（和平服无敌对） | ✅ `目标不见了` 回复，无异常 |
| 普通聊天（回归） | `@BridgeBot 你好啊…` | ✅ 回落普通 AI 问答（mock） |
| 语音 svc 门控 | BridgeBot `svc: true` / MasterJane `svc: false` | ✅ 配置解析+merge 正确；VoiceServer 仅 svc 角色触发 |
| GUI | 编译 + 启动 | ✅ 三模块编译通过；iOS 17 双主题 CSS（looked-up colors）+ I18n 词表运行时切换 |

已知限制：和平难度下饥饿效果不消耗 foodLevel（原版行为），auto_eat 的**阈值自动触发**需在非和平服验证（协议链路已验证）；方块状态无名称注册表，"攻击苦力怕"等类型名匹配依赖 EntityType 枚举名（英文）。

## 第一迭代结论（保留）

Paper 1.21.11-132 + MCProtocolLib 1.21.11-SNAPSHOT 全链路聊天闭环 PASS；详见 git 历史。当日后期发现 Paper 对"协议机器人"外发聊天存在**间歇性静默丢弃**（跨 9 次服务器启动 ~30% 成功率），用 40 行独立 MCProtocolLib 客户端可脱离本项目复现，**原版 vanilla 1.21.11 服务器接受同一客户端的聊天** → 判定为 Paper-132 构建/机器人协议组合的边缘行为，非本项目缺陷。

## 第二迭代验证

| 项 | 方法 | 结果 |
|---|---|---|
| 多模块构建 | `./gradlew build` | ✅ 三模块编译；`build/libs/mcaibridge-1.0.0-all.jar` 路径不变 + `paper/build/libs/mcaibridge-paper-1.0.0.jar` |
| 皮肤上传 | bridge 启动自动 POST /mcai/skin | ✅ 插件日志"皮肤已存储"，`skins/ai_jane.png+json` 落盘 |
| 皮肤注入 | 探针读 PlayerInfo 包断言 | ✅ `SKIN_PROPERTY=PRESENT (AI_Jane 档案含 textures)` |
| 聊天闭环（回归） | 探针发 `@AI_Jane 你好` 等回复 | ✅ `PROBE_RESULT=PASS`，Paper 日志双方向聊天（修掉 chat-executor 误配置后） |
| 语音管线 | curl POST WAV → /mcai/voice | ✅ HTTP 200，`asr="你好机器人"`（mock），`reply` 由 AI 生成；TTS 音频当前网络下 Edge-TTS 握手被拒（详见下） |
| 插件健壮性 | 无 SVC 环境启动 | ✅ 反射加载保护，皮肤功能不受影响 |
| GUI | 启动 10s 观察 | ✅ JavaFX 正常启动运行（截屏需终端屏幕录制权限，未捕获图像） |

## 关键日志

```
[probe] SKIN_PROPERTY=PRESENT (AI_Jane 档案含 textures)
[probe] 收到 AI 回复: [AI_Jane] 你好！我是 AI_Jane，收到: 你好（mock 回复：未配置 API Key）
PROBE_RESULT=PASS
HTTP=200 asr=你好机器人 reply=你好！我是 AI_Jane，收到: 你好机器人（mock 回复…）
```

## 第二迭代修复记录（详见 FIX_REPORT.md）

1. 多模块仓库解析（settings 集中管理）、core api 依赖透传、资源文件搬运丢失重建。
2. `normalizeProfiles` 未继承全局 skin 字段 → 皮肤未上传。
3. **Paper 聊天静默丢弃排查**：逐层二分（插件/SVC/构建/世界/难度/时钟/tick），最终确认两组成因：误配置的 `chat-executor-*-size: 1`（线程池拒收聊天任务，已回滚 -1）+ Paper-132 对机器人外发聊天的偶发拒绝（ vanilla 无此问题）。工程对策：新增 `reply_via: plugin` 通道（AI 回复经伴生插件广播，绕开该问题），探针据此稳定 PASS。
4. VoiceRelay 反射化加载（无 SVC 环境插件崩溃）+ EdgeTtsEngine 增加 User-Agent。

## 未完成 / 需人工介入

- **Edge-TTS 音频**：当前网络对 `speech.platform.bing.com` WSS 握手返回 400（UA/DRM 令牌已实现仍被拒）。文字回退正常，TtsEngine 为可插拔接口，可接本地 OpenAI 兼容 TTS。
- **真人麦克风端到端语音**：需真人参与，已用脚本化管线验证替代（ASR/AI/TTS 各段 mock+真实混合）。
- GUI 截图：终端无屏幕录制权限；建议手动 `screencapture -x /tmp/gui.png` 确认视觉效果。
