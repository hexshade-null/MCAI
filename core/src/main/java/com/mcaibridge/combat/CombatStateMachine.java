package com.mcaibridge.combat;

import com.google.gson.JsonObject;
import com.mcaibridge.ai.ContextManager;
import com.mcaibridge.core.ActionExecutor;
import com.mcaibridge.core.PlayerController;
import com.mcaibridge.world.EntityTracker;
import com.mcaibridge.world.SurvivalManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 战斗状态机（自主生存反应，非和平服生效）：
 * IDLE → (被打) LOCKING → 血量>50% ATTACKING（反击）→ 30-50% RETREATING（边退边吃）
 * → <30% FLEEING（逃跑+回血）；玩家指令攻击为 HUNTING（由意图层直接提交 attack 动作）。
 * 状态切换带语言反馈（限频）。survival.combat_auto=false 时整体停用。
 */
public class CombatStateMachine {
    private static final Logger log = LoggerFactory.getLogger(CombatStateMachine.class);
    private static final long LINE_COOLDOWN_MS = 5000;

    public enum State { IDLE, LOCKING, ATTACKING, RETREATING, FLEEING, HUNTING }

    private final BridgeConfigRef cfgRef;
    private final PlayerController controller;
    private final EntityTracker entities;
    private final SurvivalManager survival;
    private final ActionExecutor executor;
    private final ContextManager context;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcai-combat");
        t.setDaemon(true);
        return t;
    });
    private volatile Consumer<String> reporter;

    private volatile State state = State.IDLE;
    private volatile int currentFoe = -1;
    private volatile long lastLineAt;
    private volatile long fleeUntil;

    /** 避免循环依赖：配置经函数取值。 */
    public interface BridgeConfigRef {
        boolean combatAuto();

        com.mcaibridge.config.BridgeConfig config();
    }

    public CombatStateMachine(BridgeConfigRef cfgRef, PlayerController controller, EntityTracker entities,
                              SurvivalManager survival, ActionExecutor executor, ContextManager context) {
        this.cfgRef = cfgRef;
        this.controller = controller;
        this.entities = entities;
        this.survival = survival;
        this.executor = executor;
        this.context = context;
    }

    public void setReporter(Consumer<String> reporter) {
        this.reporter = reporter;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, 500, 500, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public State state() {
        return state;
    }

    /** DamageListener 回调：被打。 */
    public void onHurt(int attackerEntityId, String attackerName) {
        if (!cfgRef.combatAuto() || survival.isDead()) return;
        if (state == State.FLEEING || state == State.RETREATING) {
            say("打不过打不过，先溜了！");
            return;
        }
        currentFoe = attackerEntityId;
        if (state != State.ATTACKING) {
            transition(State.LOCKING);
            say(pick(new String[]{"干嘛打我！", "你惹错人了！", "找打是吧！"}));
        }
        if (survival.getHealth() > 10f) {
            engage(attackerEntityId);
        }
    }

    /** 玩家指令攻击（意图层调用）：进入 HUNTING。 */
    public void onHuntCommand(int targetId) {
        currentFoe = targetId;
        transition(State.HUNTING);
    }

    private void engage(int foeId) {
        JsonObject a = new JsonObject();
        a.addProperty("target", String.valueOf(foeId));
        // attack 支持 target=实体id 字符串（resolveTargetId 需识别数字）
        executor.submit(List.of(new ActionExecutor.Action("attack", a)));
        transition(State.ATTACKING);
    }

    /** 500ms 决策拍。 */
    private void tick() {
        try {
            if (!cfgRef.combatAuto() || survival.isDead()) {
                if (state != State.IDLE) transition(State.IDLE);
                return;
            }
            float hp = survival.getHealth();
            double[] p = controller.position();
            switch (state) {
                case ATTACKING -> {
                    var foe = currentFoe >= 0 ? entities.get(currentFoe) : null;
                    if (foe == null) {
                        transition(State.IDLE);
                        say(pick(new String[]{"哼，跑了。", "解决一个。"}));
                        currentFoe = -1;
                        break;
                    }
                    if (hp <= 10f && hp > 6f) { // 30-50%：边退边吃
                        transition(State.RETREATING);
                        say(pick(new String[]{"退后！先吃口东西。", "有点疼，撤一步。"}));
                        retreatStep(p);
                    } else if (hp <= 6f) { // <30%：逃跑+回血
                        beginFlee(p);
                    }
                }
                case RETREATING -> {
                    survival.eatNow();
                    retreatStep(p);
                    if (hp > 14f) { // 缓过来了，继续打
                        if (currentFoe >= 0 && entities.get(currentFoe) != null) {
                            transition(State.LOCKING);
                            say("缓过来了，继续！");
                            engage(currentFoe);
                        } else {
                            transition(State.IDLE);
                        }
                    } else if (hp <= 6f) {
                        beginFlee(p);
                    }
                }
                case FLEEING -> {
                    survival.eatNow();
                    if (System.currentTimeMillis() > fleeUntil && hp > 15f) {
                        transition(State.IDLE);
                        say("呼…安全了。");
                    } else if (System.currentTimeMillis() > fleeUntil) {
                        fleeUntil = System.currentTimeMillis() + 5000; // 续跑
                        retreatStep(p);
                    }
                }
                default -> {
                    // IDLE：无需动作
                }
            }
        } catch (Exception e) {
            log.debug("combat tick 异常: {}", e.toString());
        }
    }

    private void retreatStep(double[] p) {
        // 远离当前敌人的方向走 6 格
        var foe = currentFoe >= 0 ? entities.get(currentFoe) : null;
        double ax = foe != null ? foe.x : p[0] + 1;
        double az = foe != null ? foe.z : p[2] + 1;
        double dx = p[0] - ax, dz = p[2] - az;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) { dx = 1; dz = 0; len = 1; }
        executor.submit(List.of(new ActionExecutor.Action("walk_to",
                args(p[0] + dx / len * 6, p[2] + dz / len * 6))));
    }

    private void beginFlee(double[] p) {
        transition(State.FLEEING);
        say(pick(new String[]{"先溜了！", "打不过，跑路！", "撤！"}));
        fleeUntil = System.currentTimeMillis() + 6000;
        retreatStep(p);
        survival.eatNow();
    }

    // ---- 工具 ----

    private JsonObject args(double x, double z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("z", z);
        return o;
    }

    private void transition(State s) {
        if (state != s) {
            log.info("战斗状态: {} → {}", state, s);
            state = s;
        }
    }

    private void say(String line) {
        long now = System.currentTimeMillis();
        if (now - lastLineAt < LINE_COOLDOWN_MS) return;
        lastLineAt = now;
        Consumer<String> r = reporter;
        if (r != null) r.accept(line);
    }

    private static String pick(String[] lines) {
        return lines[java.util.concurrent.ThreadLocalRandom.current().nextInt(lines.length)];
    }
}
