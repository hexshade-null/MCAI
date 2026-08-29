# MCAI Bridge 修复报告

**日期**: 2026-08-29 | 第二迭代共 6 轮修复，未超限制。

## 编译/构建类

1. **多模块仓库解析失败**：gui 解析 core 依赖找不到 opencollab 仓库 → 依赖仓库集中到 `settings.gradle` 的 `dependencyResolutionManagement`（PREFER_PROJECT 会屏蔽 settings 级仓库，需删除模块内 repositories 块）。
2. **core 依赖不传递**：`implementation` → `java-library` + `api`。
3. **shadowJar copy 任务写法**：`tasks.named('shadowJar').archiveFile` → `.get().archiveFile`。
4. **资源文件丢失**：平移时目标目录未建 + `2>/dev/null` 吞错导致模板被删 → 重建并记入教训。
5. **`--profile`/皮肤字段**：BridgeConfig 增加 skinFile/skinModel 透传 + normalizeProfiles 继承。

## 运行期（按发现顺序）

6. **皮肤未上传**：全局 `skin.file` 未落入合成档案 → normalizeProfiles 继承 skinFile/skinModel。
7. **机器人外发聊天被服务器静默丢弃**：`MinecraftProtocol(GameProfile, null)` 路径在离线服上聊天被丢 → 回退到验证过的 `MinecraftProtocol(username)`（profile 仅用于本地展示）。
8. **探针聊天偶发丢失（根因排查，耗最久）**：用 40 行独立客户端 + 原版服务器逐步二分，排除插件/SVC/构建/世界/难度/时钟/tick；最终在 paper 日志发现 `Async Chat Thread RejectedExecutionException` —— 前期排查中误改的 `paper-global.yml chat-executor-*-size: 1` 导致聊天任务被单线程池拒收。回滚为 -1 后 `PROBE_RESULT=PASS`。另注意 join 后立即聊天有概率被 Paper 忽略，探针已将发送延迟至加入后 4 秒。
9. **无 SVC 时插件整体加载失败**：VoiceRelay 类引用 SVC API → 桥接插件以反射构造 + 本地 Shutdownable 接口解耦，皮肤功能不再依赖 SVC 存在。
10. **Edge-TTS 握手 400**：补充 Origin/UA 头后仍被拒（疑似 DRM 令牌/网络限制）→ 保持 `fallback_text` 文字回退，标记"需人工介入"（可换接本地 OpenAI 兼容 TTS，接口已可插拔）。

## 保留的工程对策

- `reply_via: plugin`：AI 回复经 paper 插件广播，绕开 Paper-132 对机器人外发聊天的偶发静默丢弃（该问题在原版服务器上不存在，且可用独立客户端复现，与本项目代码无关）。
- 探针新增 `SKIN_PROPERTY=PRESENT/ABSENT` 断言，作为皮肤链路的确定性验收标准。
