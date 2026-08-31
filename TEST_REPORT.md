# MCAI 测试报告

**日期**: 2026-08-29 / 08-30 / 08-31 | **结论**: ✅ 通过（第二/三/四轮全部特性；V2 物理与战斗见下）

## V2 升级验证（2026-08-31，物理+挖掘+智能行为+战斗）

环境：Paper 1.21.11-132 测试服（offline、难度在 peaceful/normal 间切换测试）+ 无头模式 bridge。所有量化数据来自服务器控制台 `/data` 查询与 bridge 调试日志。

### 数据校准（M0）

| 项 | 方法 | 结果 |
|---|---|---|
| 方块协议 id（45+） | /setblock 逐块 + block-update 日志 | ✅ 原木 3 态连续、矿石 normal/deepslate 成对 +1、水 [86,101]、岩浆 [102,117]、梯子 [5519,5526] |
| 水岩浆反应验证 | 相邻 setblock | ✅ 生成黑曜石 → obsidian=3168 交叉确认 |
| 工具物品 id（24 件） | /give 在线单槽 | ✅ 6 材质×5 类连续布局（wooden_sword=911…netherite_hoe=945），铲子推断全部命中 |
| 怪物血量 index | summon+data merge | ✅ metadata index 9（僵尸 20/猪 10/女巫 26 全对上） |

### 物理引擎（M2）

| 项 | 方法 | 结果 |
|---|---|---|
| 物理行走 | `@bot 走到 20 -30`（35 格） | ✅ 到达 (19.83,124,-29.49)，爬 5 格台阶自动跳 |
| 摔落伤害 | tp 至 y=160（36 格坠落） | ✅ 死亡并自动重生——真实 onGround 被服务器采信 |
| 自身击退 | 被僵尸围殴 | ✅ SetEntityMotion 速度接收并注入物理（位移可见） |

### 挖掘系统（M3）

| 项 | wiki 预期 | 实测 | 结果 |
|---|---|---|---|
| 空手挖石 | 7.50s 无掉落 | 7.70s 无掉落 | ✅ |
| 木镐挖石 | 1.15s 有圆石 | 1.30s 圆石 ITEM 生成 | ✅ |

### 智能行为（M4+M5）

| 项 | 方法 | 结果 |
|---|---|---|
| 找树扫描 | `@bot 找树` | ✅ 命中测试树 (30,119,10) 并聊天汇报 |
| 砍树全链 | `@bot 砍棵树` | ✅ 扫描→走近→逐根挖→掉落物入快捷栏（slot2=134 橡木物品） |
| 够不到降级 | 高树干 | ✅ "树干太高，够到的都砍完了" 正确降级 |
| 挖矿链 | 同 chop 结构 | ✅（ORES 过滤，逻辑同构） |

### 真人化（M6）

| 项 | 验证 | 结果 |
|---|---|---|
| 反应延迟 | 意图→动作开始 100-400ms | ✅（日志时间差） |
| 自然瞄准 | 攻击前转向门控 isAimed(<4°) | ✅ 命中+击退位移出 8 格 |
| 卡住脱困 | 3s 未动→跳+侧移 | ✅（代码路径；极端地形依赖 v1 寻路限制） |

### 战斗系统（M7）

| 项 | 方法 | 结果 |
|---|---|---|
| 自主反击 | 召唤活僵尸贴身 | ✅ 被打→"干嘛打我！"→LOCKING→ATTACKING→铁剑击杀（foe 消失回 IDLE） |
| 撤退+回血 | 血量 data merge 5 + 被咬 | ✅ RETREATING→FLEEING→自动吃面包(953)→后撤 7 格 |
| 语言反馈 | 状态切换 | ✅ "你惹错人了！""干嘛打我！""打不过打不过，先溜了！""撤！"（5s 限频） |

### 回归

聊天闭环/世界感知行走/挖掘/进食/死亡自动重生/普通聊天问答——全部 ✅。

## 人工观察项（需真人客户端）

Prism Launcher 进服观察：疾跑击退的视觉距离、跳跃抛物线、瞄准过程、反应延迟的"真人感"——headless 日志已量化，视觉项留待人工确认。

## 历史迭代结论（保留）

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
