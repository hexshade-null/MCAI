package com.mcaibridge.core;

import com.mcaibridge.config.BridgeConfig;
import com.mcaibridge.world.EntityTracker;
import com.mcaibridge.world.SurvivalManager;
import com.mcaibridge.world.WorldModel;

/**
 * 机器人装配工厂：把世界模型/实体跟踪/生存辅助/动作执行器/意图解析
 * 与 MCBot、PlayerController、ChatHandler 接成完整运行体（GUI 与无头模式共用）。
 */
public final class BotFactory {
    private BotFactory() {
    }

    /** 运行体句柄：调用方按需持有（GUI 卡片/无头运行器）。 */
    public record Handles(MCBot bot, ChatHandler chatHandler, PlayerController controller,
                          ActionExecutor executor, WorldModel world, EntityTracker entities,
                          SurvivalManager survival) {
    }

    public static Handles assemble(BridgeConfig cfg, MCBot bot) {
        AIBrain brain = new AIBrain(cfg);
        WorldModel world = new WorldModel();
        EntityTracker entities = new EntityTracker();
        SurvivalManager survival = new SurvivalManager(cfg, bot);

        PlayerController controller = new PlayerController(cfg, bot);
        ActionExecutor executor = new ActionExecutor(cfg, bot, controller, world, entities, survival);
        controller.setWorld(world);
        controller.setExecutor(executor);
        controller.setSurvival(survival);
        survival.setDeathListener(() -> {
            executor.clear();
            controller.stopMoving();
        });

        ChatHandler chatHandler = new ChatHandler(cfg, bot, brain);
        chatHandler.setController(controller);
        chatHandler.setParser(new IntentParser(cfg, brain, survival));
        chatHandler.setExecutor(executor);
        chatHandler.setWorldModules(entities, survival);
        executor.setReporter(chatHandler::sendActionReport);

        com.mcaibridge.ai.ContextManager context = new com.mcaibridge.ai.ContextManager(
                controller, survival, entities, new com.mcaibridge.world.WorldScanner(world));
        com.mcaibridge.ai.TaskPlanner planner = new com.mcaibridge.ai.TaskPlanner(executor);
        planner.setReporter(chatHandler::sendActionReport);
        chatHandler.setTaskPlanner(planner);
        chatHandler.setContext(context);

        bot.setChatHandler(chatHandler);
        bot.setController(controller);
        bot.setWorld(world);
        bot.setEntities(entities);
        bot.setSurvival(survival);
        controller.start();
        return new Handles(bot, chatHandler, controller, executor, world, entities, survival);
    }
}
