package com.mcaibridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.world.SurvivalManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 意图解析器（言出法随的"脑"）：
 * 把玩家的自然语言指令解析成动作序列。有 API key 时走 LLM（严格 JSON 输出），
 * 无 key / 调用失败时降级为关键词规则；两侧都解析不出动作则返回 null（由调用方走普通聊天问答）。
 */
public class IntentParser {
    private static final Logger log = LoggerFactory.getLogger(IntentParser.class);
    private static final Set<String> KNOWN_TYPES = Set.of(
            "walk_to", "follow", "stop", "dig", "attack", "eat", "command");

    /** 一次解析结果：say=要说的回复；actions=要执行的动作。 */
    public static class Plan {
        public String say = "";
        public final List<ActionExecutor.Action> actions = new ArrayList<>();

        public boolean hasAction() {
            return !actions.isEmpty();
        }
    }

    /** 解析时的世界状态快照。 */
    public record Ctx(String sender, String pos, float health, int food, String nearby) {
    }

    private static final String SYSTEM_PROMPT = """
            你是 Minecraft 生存模式 AI 玩家的"言出法随"规划器。玩家对你说的话可能是行动指令，也可能只是闲聊。
            根据当前状态决定要执行的动作，只输出一个 JSON 对象，禁止输出任何其他文字或代码块标记：
            {"say":"对玩家说的简短中文回复（一两句）","actions":[{"type":"..."}]}

            可用动作（严格执行，不要发明其他 type）：
            - {"type":"walk_to","x":数字,"z":数字} 走到坐标
            - {"type":"follow","target":"玩家名或英文生物类型"} 跟随玩家或生物（如 zombie、pig）
            - {"type":"stop"} 停止移动
            - {"type":"dig"} 挖脚下方块；或 {"type":"dig","x":数字,"y":数字,"z":数字} 挖指定方块
            - {"type":"attack","target":"玩家名或英文生物类型"} 攻击目标；不写 target 默认攻击最近的敌对生物
            - {"type":"eat"} 吃东西（需要快捷栏里有食物）
            - {"type":"command","cmd":"不带斜杠的服务器指令"}

            规则：
            1. 闲聊/提问不需要动作，actions 给空数组，用 say 自然回答。
            2. 指令不明确（缺坐标、目标不存在）时 actions 空数组，say 里追问。
            3. 会伤害玩家自己或他人的破坏性指令可以拒绝，say 说明原因。
            4. 相对方位（如"你身后"）没有信息时，让玩家给坐标。
            """;

    private final BridgeConfig cfg;
    private final AIBrain brain;
    private final SurvivalManager survival;

    public IntentParser(BridgeConfig cfg, AIBrain brain, SurvivalManager survival) {
        this.cfg = cfg;
        this.brain = brain;
        this.survival = survival;
    }

    /** 解析意图；返回 null 表示没有可执行的意图（按普通聊天处理）。 */
    public Plan parse(String text, Ctx ctx) {
        if (!brain.isMock()) {
            try {
                Plan p = llmParse(text, ctx);
                if (p != null) return p;
            } catch (Exception e) {
                log.warn("LLM 意图解析失败，降级关键词: {}", e.toString());
            }
        }
        return keywordParse(text, ctx);
    }

    private Plan llmParse(String text, Ctx ctx) throws Exception {
        String state = "位置 " + ctx.pos() + "，血量 " + (int) ctx.health() + "/20，饥饿 " + ctx.food() + "/20"
                + (ctx.nearby() == null || ctx.nearby().isBlank() ? "，附近没有实体" : "，附近实体: " + ctx.nearby())
                + (survival.isDead() ? "（已死亡）" : "");
        String raw = brain.chat(SYSTEM_PROMPT, text + "\n[当前状态] " + state);
        JsonObject root = extractJson(raw);
        if (root == null) return null;

        Plan plan = new Plan();
        if (root.has("say") && !root.get("say").isJsonNull()) {
            plan.say = root.get("say").getAsString().trim();
        }
        if (root.has("actions") && root.get("actions").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("actions");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String type = o.has("type") && !o.get("type").isJsonNull() ? o.get("type").getAsString() : "";
                if (!KNOWN_TYPES.contains(type)) continue;
                JsonObject args = o.getAsJsonObject();
                plan.actions.add(new ActionExecutor.Action(type, args));
            }
        }
        if (plan.say.isEmpty() && !plan.hasAction()) return null;
        log.info("LLM 意图: say=\"{}\" actions={}", plan.say, plan.actions.size());
        return plan;
    }

    /** 从 LLM 原始输出里抠出 JSON（容忍 ```json 围栏与前后闲话）。 */
    private static JsonObject extractJson(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return JsonParser.parseString(s.substring(start, end + 1)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /** 无 API key / LLM 失败时的关键词规则（兼容迭代二的内置指令）。 */
    private Plan keywordParse(String text, Ctx ctx) {
        String s = text.trim();
        Plan plan = new Plan();

        if (s.startsWith("/")) {
            plan.say = "执行指令: " + s;
            plan.actions.add(new ActionExecutor.Action("command", arg("cmd", s.substring(1))));
            return plan;
        }
        if (s.matches("(停|停下|停止|站住|stop)\\S{0,2}")) {
            plan.say = "好的，我停下";
            plan.actions.add(new ActionExecutor.Action("stop", null));
            return plan;
        }
        if (s.matches("(过来|跟我来|跟随|跟上|follow)\\S{0,2}")) {
            if (ctx.sender() == null || ctx.sender().isBlank()) return null;
            plan.say = "好的，我这就来！";
            plan.actions.add(new ActionExecutor.Action("follow", arg("target", ctx.sender())));
            return plan;
        }
        if (s.matches("(挖|dig)\\S{0,2}")) {
            plan.say = "开挖脚下的方块！";
            plan.actions.add(new ActionExecutor.Action("dig", null));
            return plan;
        }
        var m3 = java.util.regex.Pattern.compile("(?:挖|dig)\\s*(-?\\d+)[ ,，]+(-?\\d+)[ ,，]+(-?\\d+)").matcher(s);
        if (m3.find()) {
            JsonObject a = arg("x", Double.parseDouble(m3.group(1)));
            a.addProperty("y", Double.parseDouble(m3.group(2)));
            a.addProperty("z", Double.parseDouble(m3.group(3)));
            plan.say = "开挖 (" + m3.group(1) + ", " + m3.group(2) + ", " + m3.group(3) + ")";
            plan.actions.add(new ActionExecutor.Action("dig", a));
            return plan;
        }
        var m = java.util.regex.Pattern.compile("(?:走到|走向|去)\\s*(-?[0-9.]+)[ ,，]+(-?[0-9.]+)").matcher(s);
        if (m.find()) {
            plan.say = "走向 (" + m.group(1) + ", " + m.group(2) + ")";
            JsonObject a = arg("x", Double.parseDouble(m.group(1)));
            a.addProperty("z", Double.parseDouble(m.group(2)));
            plan.actions.add(new ActionExecutor.Action("walk_to", a));
            return plan;
        }
        if (s.matches("(在哪|位置|坐标)\\S{0,2}")) {
            plan.say = "我在 " + ctx.pos();
            return plan;
        }
        if (s.matches("(打|攻击|干掉|干他|attack)\\S{0,4}")) {
            plan.say = "接敌攻击！";
            plan.actions.add(new ActionExecutor.Action("attack", new JsonObject()));
            return plan;
        }
        if (s.matches("(吃|进食|吃饭|吃点东西)\\S{0,2}")) {
            plan.say = "我看看有什么吃的";
            plan.actions.add(new ActionExecutor.Action("eat", null));
            return plan;
        }
        return null;
    }

    private static JsonObject arg(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    private static JsonObject arg(String key, double value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }
}
