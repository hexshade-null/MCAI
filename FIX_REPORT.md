# MCAI Bridge 修复报告

**日期**: 2026-08-29 | 共 2 轮修复（未超 3 轮限制），无需人工介入。

## 第 1 轮：编译错误（修复 3 个文件，符合单轮 ≤3 文件限制）

首次 `./gradlew build` 失败，4 个错误。依赖解析成功，属 API 签名偏差：1.21.11-SNAPSHOT（2026-05-12 构建）与 GitHub master（26.x）示例存在差异，全部用 `javap` 对实际 jar 校准后修复。

| 文件 | 错误 | 修复 |
|---|---|---|
| `src/main/java/com/mcaibridge/core/ProbeRunner.java` | `cannot find symbol: MinecraftConstants`（缺 import）；`disconnect()` 无参重载不存在 | 补 `import ...protocol.MinecraftConstants`；改为 `disconnect(String)` |
| `src/main/java/com/mcaibridge/core/MCBot.java` | `disconnect()` 无参重载不存在 | `shutdown()` 中改为 `s.disconnect("客户端关闭")` |
| `src/main/java/com/mcaibridge/core/TextUtil.java` | `TranslationArgument` 不能隐式转 `Component`（adventure 4.25 行为） | `arg.asComponent()`（经 javap 确认 `TranslationArgumentLike.asComponent()` 存在） |

结果：`./gradlew build` BUILD SUCCESSFUL，产出 `build/libs/mcaibridge-1.0.0-all.jar`。

## 第 2 轮：运行期无法登录（配置修复，0 个源码文件）

**现象**：Bridge 连接成功后立即被服务端断开（原因为空），反复重连。

**定位**：Paper `logs/latest.log`：
```
java.lang.IllegalArgumentException: The name of the profile contains invalid characters: 测试Jane
```

**根因**：Minecraft 1.21 登录协议要求玩家名匹配 `[A-Za-z0-9_]`（≤16 字符），中文名被服务端校验拒绝。这是 MC 协议硬约束。

**修复**：`test-config.yml` 中 `bot.name: "测试Jane" → "AI_Jane"`（最小改动，源码无缺陷无需修改）。

结果：AI_Jane 成功登入并通过 `--probe` 全链路自检（PROBE_RESULT=PASS）。

## 备注与建议（非阻塞）

- `auth/MicrosoftAuth.java` 的 MinecraftAuth 5.x API 已按 javap 校准（`login((hc, cfg) -> new DeviceCodeMsaAuthService(...))`），但设备码流程需真实微软账号才能端到端验证，本次未覆盖。
- 建议后续在 `BridgeConfig` 加载时预校验 bot 名（非 ASCII 时 WARN 提示），避免用户踩同样的坑。
