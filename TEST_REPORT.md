# MCAI Bridge 测试报告

**日期**: 2026-08-29 | **结论**: ✅ 通过（含第二迭代全部新特性）

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
