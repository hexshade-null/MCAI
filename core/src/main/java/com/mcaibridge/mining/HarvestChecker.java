package com.mcaibridge.mining;

import com.mcaibridge.world.BlockIds;

/**
 * 可采集判定（决定挖掘公式的 ÷30 vs ÷100，wiki Breaking "Breaking blocks that can be harvested"）。
 * 规则：需要镐的方块必须有镐且等级够；泥土/原木等空手即可采集。
 */
public final class HarvestChecker {
    private HarvestChecker() {
    }

    /** 采集所需最低镐等级：-1=不需要镐；0=任意镐；1=石+；2=铁+；3=钻+。 */
    public static int requiredPickaxeLevel(String blockName) {
        if (blockName == null) return -1;
        return switch (blockName) {
            case "obsidian", "crying_obsidian" -> 3;
            case "diamond_ore", "deepslate_diamond_ore", "gold_ore", "deepslate_gold_ore",
                 "emerald_ore", "deepslate_emerald_ore", "redstone_ore", "deepslate_redstone_ore" -> 2;
            case "iron_ore", "deepslate_iron_ore", "copper_ore", "deepslate_copper_ore",
                 "lapis_ore", "deepslate_lapis_ore" -> 1;
            default -> {
                if (blockName.endsWith("_ore") || STONE_FAMILY_HINT.contains(blockName)) yield 0;
                yield -1; // 土/木/沙等空手可采集
            }
        };
    }

    public static boolean canHarvest(int heldItemId, int blockStateId) {
        String name = BlockIds.name(blockStateId);
        if (name == null) return true; // 未知方块按可采集近似
        int need = requiredPickaxeLevel(name);
        if (need < 0) return true;
        ToolSpeedRegistry.ToolInfo tool = ToolSpeedRegistry.tool(heldItemId);
        if (tool.type() != ToolSpeedRegistry.ToolType.PICKAXE) return false;
        return ToolSpeedRegistry.tierLevel(heldItemId) >= need;
    }

    private static final java.util.Set<String> STONE_FAMILY_HINT = java.util.Set.of(
            "stone", "granite", "diorite", "andesite", "tuff", "deepslate", "cobbled_deepslate",
            "cobblestone", "blackstone", "stone_bricks", "netherrack", "end_stone",
            "sandstone", "terracotta", "blue_ice", "reinforced_deepslate", "dripstone_block",
            "amethyst_block", "furnace", "spawner");
}
