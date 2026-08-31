package com.mcaibridge.mining;

import java.util.Map;
import java.util.Set;

/**
 * 1.21.11 工具/武器注册表：协议物品 id（2026-08-31 测试服 /give 实测）→ 类型与速度；
 * 挖掘速度倍率与可采集判定来源：minecraft.wiki/w/Breaking（2/4/5/6/8/9/金12）。
 * 实测 id 布局：同材质 sword/shovel/pickaxe/axe/hoe 连续 5 个一组。
 */
public final class ToolSpeedRegistry {
    private ToolSpeedRegistry() {
    }

    public enum ToolType { SWORD, SHOVEL, PICKAXE, AXE, HOE, HAND }

    public record ToolInfo(ToolType type, double speed) {
    }

    // 基础 id = sword；+1 shovel；+2 pickaxe；+3 axe；+4 hoe
    private static final int[] TIER_BASE = {911, 926, 921, 931, 936, 941}; // wooden, golden, stone, iron, diamond, netherite
    private static final double[] TIER_SPEED = {2.0, 12.0, 4.0, 6.0, 8.0, 9.0};
    private static final String[] TIER_NAME = {"wooden", "golden", "stone", "iron", "diamond", "netherite"};
    private static final int[] TIER_LEVEL = {0, 0, 1, 2, 3, 4}; // 采集等级：木/金=0 石=1 铁=2 钻/合金=3+

    /** 手持物品 id → 工具信息；空手/未知 → HAND(1.0)。 */
    public static ToolInfo tool(int itemId) {
        if (itemId == 0) return new ToolInfo(ToolType.HAND, 1.0);
        for (int t = 0; t < TIER_BASE.length; t++) {
            if (itemId >= TIER_BASE[t] && itemId <= TIER_BASE[t] + 4) {
                ToolType type = switch (itemId - TIER_BASE[t]) {
                    case 0 -> ToolType.SWORD;
                    case 1 -> ToolType.SHOVEL;
                    case 2 -> ToolType.PICKAXE;
                    case 3 -> ToolType.AXE;
                    default -> ToolType.HOE;
                };
                return new ToolInfo(type, TIER_SPEED[t]);
            }
        }
        return new ToolInfo(ToolType.HAND, 1.0);
    }

    public static String tierName(int itemId) {
        for (int t = 0; t < TIER_BASE.length; t++) {
            if (itemId >= TIER_BASE[t] && itemId <= TIER_BASE[t] + 4) return TIER_NAME[t];
        }
        return "hand";
    }

    public static int tierLevel(int itemId) {
        for (int t = 0; t < TIER_BASE.length; t++) {
            if (itemId >= TIER_BASE[t] && itemId <= TIER_BASE[t] + 4) return TIER_LEVEL[t];
        }
        return -1;
    }

    /** 该工具是否加速挖掘此方块（wiki "Best tools"）。 */
    public static boolean isEffective(ToolType tool, String blockName) {
        if (blockName == null) return false;
        return switch (tool) {
            case PICKAXE -> STONE_FAMILY.contains(blockName) || blockName.endsWith("_ore")
                    || blockName.equals("obsidian") || blockName.equals("crying_obsidian")
                    || blockName.equals("amethyst_block") || blockName.equals("furnace");
            case AXE -> blockName.endsWith("_log") || blockName.endsWith("_planks")
                    || blockName.equals("crafting_table") || blockName.equals("chest") || blockName.equals("bookshelf")
                    || blockName.equals("pumpkin") || blockName.equals("melon");
            case SHOVEL -> blockName.equals("dirt") || blockName.equals("grass_block") || blockName.equals("sand")
                    || blockName.equals("gravel") || blockName.equals("clay") || blockName.equals("snow_block")
                    || blockName.equals("mud") || blockName.equals("soul_sand");
            case HOE -> blockName.equals("leaves") || blockName.endsWith("_leaves") || blockName.equals("hay_block")
                    || blockName.endsWith("_wart_block") || blockName.equals("moss_block");
            case SWORD -> false; // 剑 1.5× 仅对蛛网（本表暂不含）
            default -> false;
        };
    }

    /**
     * 挖掘速度倍率：正确工具用等级速度，否则 1.0（空手）。
     */
    public static double speedFor(int itemId, int blockStateId) {
        ToolInfo tool = tool(itemId);
        String name = com.mcaibridge.world.BlockIds.name(blockStateId);
        return isEffective(tool.type(), name) ? tool.speed() : 1.0;
    }

    // ---- 武器攻击冷却（wiki：Attack speed，冷却秒 = 1/攻速）----
    // 剑 1.6/s=0.625s；斧 0.8~1.0/s（按材质）；镐 1.2/s；锹 1.0/s；手/其他 4/s=0.25s

    /** 手持武器冷却秒数。 */
    public static double attackCooldownSeconds(int itemId) {
        ToolInfo tool = tool(itemId);
        return switch (tool.type()) {
            case SWORD -> 0.625;
            case AXE -> 1.0;
            case PICKAXE -> 1.0 / 1.2;
            case SHOVEL -> 1.0;
            default -> 0.25;
        };
    }

    /** 石族与矿石集合（pickaxe 生效判定用）。 */
    private static final Set<String> STONE_FAMILY = Set.of(
            "stone", "granite", "diorite", "andesite", "tuff", "deepslate", "cobbled_deepslate",
            "cobblestone", "blackstone", "stone_bricks", "netherrack", "end_stone",
            "sandstone", "terracotta", "blue_ice", "reinforced_deepslate", "dripstone_block");
}
