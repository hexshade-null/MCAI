# MCAI 架构 V2 — 真实客户端模拟 + 智能行为

第三迭代（言出法随纯本地端，见 ARCHITECTURE.md 末节）之后的第四轮升级：把机器人从"半真实客户端"升级为**完整物理模拟 + 感知/决策/真人化执行 + 战斗系统**。本档记录 V2 增量；基础架构见 ARCHITECTURE.md。

## 新增模块总览（core/ 下）

```
physics/  PhysicsEngine(50ms积分+AABB分轴碰撞+子步防穿透) · VanillaPhysics(原版常量) · FluidHandler(水/岩浆/梯子采样)
mining/   BlockHardnessRegistry(76+方块硬度+挖掘公式) · ToolSpeedRegistry(工具id/速度/武器冷却) · HarvestChecker(采集等级) · MiningProgressTracker(START→计时挥臂→FINISH)
world/    +BlockIds(协议数字id实测表) · +WorldScanner(圈扫LOGS/ORES/WATER) · +Raycaster(DDA视线) · EntityTracker扩展(血量index9/玩家名/掉落物)
ai/       TaskPlanner(序列提交+失败重试) · ContextManager(短期记忆/LLM状态摘要)
action/   HumanizedExecutor(反应延迟/分神/抖动) · AimController(自然转向/呼吸微动/isAimed) · MoveController(3秒卡住→跳+侧移)
combat/   DamageListener(DamageEvent攻击者) · ThreatAssessor(威胁分级) · AttackCooldownTracker · CombatStateMachine(六态)
protocol/ ClientInfoSender(皮肤部件/聊天FULL) · ActionStateSender(疾跑/潜行/输入位)
```

## 数据地基（M0 实测与来源）

- **协议数字 id**（库无名称注册表）：测试服 /setblock、/give 在线单槽实测——
  原木9种(axis 3态连续, oak=136-138)、矿石normal/deepslate成对+1(红石含发光态2×2)、
  水[86,101](86+level)、岩浆[102,117]、梯子[5519,5526](facing×waterlogged)、
  工具6材质×5类连续布局(wooden sword=911 … netherite hoe=945)、面包=953 等；怪物血量=metadata index 9。
  交叉验证：水+岩浆相邻生成黑曜石 → obsidian=3168 ✓。
- **wiki 数值**（minecraft.wiki/w/Breaking、/Sprinting、/Knocking、Module:Hardness values，代码注释附出处）：
  挖掘公式 damage=speed/hardness/(可采集?30:100)、工具速度 2/4/5/6/8/9/金12、
  武器冷却剑0.625s/斧1s、击退基础1.552格+KB附魔2.586/级、
  物理 gravity0.08/drag0.98·0.91·0.546/jump0.42/walk4.317·sprint5.612 m/s、AABB 0.6×1.8。

## 真实客户端状态补齐（对照"服务器权威计算"）

| 服务器算什么 | 需要的上报 | 本项目实现 |
|---|---|---|
| 疾跑击退/饥饿消耗 | START/STOP_SPRINTING | ActionStateSender（攻击瞬间疾跑一击） |
| 摔落伤害 | 真实 onGround + 连续位置 | PhysicsEngine（36 格摔落实测死亡✓） |
| 击退方向/距离 | 攻击瞬间的位置+朝向+疾跑 | faceTo+aimAt（isAimed 门控） |
| 挖掘进度/掉落 | START/计时/FINISH | MiningProgressTracker（公式实测 7.5s/1.3s✓） |
| 皮肤层/聊天可见 | ClientInformation | ClientInfoSender（join 后一次） |
| 被击退的物理反应 | 速度积分后位置 | PhysicsEngine.setVelocity（SetEntityMotion 注入） |

## 智能行为链路

```
@bot 说话 → IntentParser（LLM 严格 JSON / 关键词降级）
  动作词表: walk_to/follow/stop/dig[x,y,z]/attack/eat/command/scan/chop/mine/collect
→ TaskPlanner（失败重试×1）
→ ActionExecutor（反应延迟 100-400ms → 顺序执行）
   chop/mine = 内嵌状态机: 扫描(WorldScanner)→走近(PhysicsEngine 贴地+自动跳)→
   逐块挖(MiningProgressTracker)→走进掉落物(EntityTracker.nearestItem)拾取
→ 语言反馈经 ChatHandler 统一回复通道
```

## 战斗状态机

```
IDLE →(被打 DamageListener)→ LOCKING →(hp>50%)→ ATTACKING（submit attack foeId）
ATTACKING →(hp≤10)→ RETREATING（后撤6格+eatNow，hp>14 重新交战）
任意 →(hp≤6)→ FLEEING（远离6格+吃，hp>15 或超时回 IDLE）
玩家指令攻击 = HUNTING（意图层直接提交）
语言反馈限频 5s："干嘛打我！"/"退后！先吃口东西。"/"先溜了！"
开关: survival.combat_auto（默认 true）
```

## 修复的重大缺陷（迭代中实测发现）

1. **EntityTracker 绝对坐标当增量**：EntityPositionSync/TeleportEntity 是绝对位置，旧代码累加导致实体坐标漂移（y 漂到 150/-21），垂直过滤全灭。已拆分 setAbsolute/move 两条路径。
2. nearestHostile 只算水平距离：地下 60 格的怪被当"最近"。加 maxDy 过滤。
3. BotFactory 曾漏 controller.start()（历史）。

## 与原版真实玩家仍存在的差异（已知限制）

- 无 A* 寻路：直线+自动跳+卡住侧移，复杂地形/悬崖会绕不过（砍树测试中曾掉入洞穴，正确汇报"够不到"）。
- 砍树只砍 reach(4.5) 内的树干，不做搭柱子上树；挖矿限已加载区块，不做垂直下挖。
- 玩家推挤不模拟；聊天为未签名（[Not Secure]，客户端开"仅安全聊天"看不到机器人发言）。
- 水面行走已由物理修正（水中减速/上浮），但深水游泳路径仍简化。
- 手持工具选择：砍树/挖矿不自动切换到最优工具（按当前手持判定），后续可在任务链前加 set_slot。

## 已知问题列表（V2 交付时点）

1. 无 A* 寻路——复杂地形（洞穴、悬崖、迷宫）会绕不过或坠落（坠落已真实化，有伤害风险）。
2. 砍树只取 reach 内树干；挖矿不做垂直下挖；两者都限已加载区块。
3. 手持工具不自动切换最优（按当前快捷栏手持判定）。
4. 玩家推挤不模拟；未签名聊天（[Not Secure]）。
5. doMobSpawning gamerule 在 Paper-132 上设置失败（测试服环境问题，非本项目缺陷）；测试依赖 difficulty 切换。
6. 攻击 10s 超时会放弃高机动目标（击退把它们打远了）——后续可改为追击重置计时。
7. 和平服无法验证 auto_eat 阈值自动触发与战斗系统（协议链路均已验证）。

## V2.1 追加：已知限制处置（2026-08-31 晚）

| 原限制 | 处置 |
|---|---|
| 无 A\* 寻路 | ✅ `world/Pathfinder`（4邻接+跳1/落≤3，未加载=墙、不降到目标层以下、高落价逼绕路）；walkTo 自动寻路+航点跟随+周期重算 |
| 掉洞风险 | ✅ 双防护：A* 层面杜绝；直线兜底有防坠落护栏（前方 >3 格深坑即急停+诚实汇报"走不过去：前方有深坑"）+ 坠落底线（低于目标层 3 格停止） |
| 不自动切最优工具 | ✅ 挖掘前扫快捷栏选最优（能采集优先→有效工具速度），SetCarriedItem 切换。实测：镐挖原木自动切斧 1.69s（原 3s） |
| 攻击 10s 超时放弃 | ✅ 改进展续时：距离缩短或目标掉血即重置计时器；顺带修复 DamageEvent cause=-1（1.21.11 部分攻击归因在 directId）导致的反击锁空目标循环 |
| 和平服/gamerule 失效 | 环境问题非代码缺陷（Paper-132 doMobSpawning 指令无效）；测试已用 difficulty 切换覆盖 |

新增实测发现：tp 后首个移动包存在竞态（旧位置包触发 "moved too quickly" 被服务器弹回传送前位置）——传送同步后 300ms 移动静默期解决。
