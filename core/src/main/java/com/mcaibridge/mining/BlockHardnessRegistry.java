package com.mcaibridge.mining;

import com.mcaibridge.world.BlockIds;

import java.util.Map;

/**
 * 1.21.11 方块硬度表 + 挖掘时间公式。
 * 数值来源：minecraft.wiki/w/Breaking 与 Module:Hardness values（2026-08-31 抓取，注释保留出处）。
 * 公式：每 tick 伤害 = 速度 ÷ 硬度 ÷ (可采集 ? 30 : 100)；伤害≥1 秒破；ticks = ceil(1/伤害)。
 * 水下无激流附魔 / 离地：速度 ÷5（本表提供修正入口，调用方判定条件）。
 */
public final class BlockHardnessRegistry {
    private BlockHardnessRegistry() {
    }

    /** 方块名 → 硬度（未收录=1.0 近似； bedrock=-1 不可破坏；水/岩浆=100 不作挖掘目标）。 */
    private static final Map<String, Double> HARDNESS = Map.ofEntries(
            // 原木与木质
            Map.entry("oak_log", 2.0), Map.entry("spruce_log", 2.0), Map.entry("birch_log", 2.0),
            Map.entry("jungle_log", 2.0), Map.entry("acacia_log", 2.0), Map.entry("dark_oak_log", 2.0),
            Map.entry("mangrove_log", 2.0), Map.entry("cherry_log", 2.0), Map.entry("pale_oak_log", 2.0),
            Map.entry("oak_planks", 2.0),
            // 矿石
            Map.entry("coal_ore", 3.0), Map.entry("deepslate_coal_ore", 4.5),
            Map.entry("copper_ore", 3.0), Map.entry("deepslate_copper_ore", 4.5),
            Map.entry("iron_ore", 3.0), Map.entry("deepslate_iron_ore", 4.5),
            Map.entry("gold_ore", 3.0), Map.entry("deepslate_gold_ore", 4.5),
            Map.entry("diamond_ore", 3.0), Map.entry("deepslate_diamond_ore", 4.5),
            Map.entry("emerald_ore", 3.0), Map.entry("deepslate_emerald_ore", 4.5),
            Map.entry("lapis_ore", 3.0), Map.entry("deepslate_lapis_ore", 4.5),
            Map.entry("redstone_ore", 3.0), Map.entry("deepslate_redstone_ore", 4.5),
            Map.entry("ancient_debris", 30.0),
            // 岩石族
            Map.entry("stone", 1.5), Map.entry("granite", 1.5), Map.entry("diorite", 1.5),
            Map.entry("andesite", 1.5), Map.entry("tuff", 1.5), Map.entry("blackstone", 1.5),
            Map.entry("stone_bricks", 1.5), Map.entry("mud_bricks", 1.5), Map.entry("bookshelf", 1.5),
            Map.entry("amethyst_block", 1.5), Map.entry("dripstone_block", 1.5),
            Map.entry("deepslate", 3.0), Map.entry("cobbled_deepslate", 3.5),
            Map.entry("cobblestone", 2.0), Map.entry("bricks", 2.0), Map.entry("crafting_table", 2.5),
            Map.entry("chest", 2.5), Map.entry("furnace", 3.5), Map.entry("spawner", 5.0),
            Map.entry("obsidian", 50.0), Map.entry("crying_obsidian", 50.0),
            Map.entry("reinforced_deepslate", 55.0),
            Map.entry("sandstone", 0.8), Map.entry("terracotta", 1.25),
            Map.entry("netherrack", 0.4), Map.entry("end_stone", 3.0),
            // 土族与其他
            Map.entry("dirt", 0.5), Map.entry("coarse_dirt", 0.5), Map.entry("podzol", 0.5),
            Map.entry("grass_block", 0.6), Map.entry("sand", 0.5), Map.entry("red_sand", 0.5),
            Map.entry("gravel", 0.6), Map.entry("clay", 0.6), Map.entry("mud", 0.5),
            Map.entry("hay_block", 0.5), Map.entry("sponge", 0.6),
            Map.entry("snow_block", 0.2), Map.entry("ice", 0.5), Map.entry("packed_ice", 0.5),
            Map.entry("blue_ice", 2.8), Map.entry("wool", 0.8), Map.entry("glass", 0.3),
            Map.entry("glowstone", 0.3), Map.entry("sea_lantern", 0.3), Map.entry("shroomlight", 1.0),
            Map.entry("moss_block", 0.1), Map.entry("sculk", 0.2),
            Map.entry("pumpkin", 1.0), Map.entry("melon", 1.0),
            Map.entry("nether_wart_block", 1.0), Map.entry("warped_wart_block", 1.0),
            Map.entry("leaves", 0.2),
            // 流体与不可破坏
            Map.entry("water", 100.0), Map.entry("lava", 100.0), Map.entry("bedrock_rock", -1.0));

    /** 空气/未知默认按 1.0；-1 表示不可破坏。 */
    public static double hardness(int blockStateId) {
        String name = BlockIds.name(blockStateId);
        if (name == null) return 1.0;
        if (name.equals("bedrock")) return -1.0;
        return HARDNESS.getOrDefault(name, 1.0);
    }

    public static boolean isUnbreakable(int blockStateId) {
        return blockStateId == 0 || hardness(blockStateId) < 0 || BlockIds.isFluid(blockStateId);
    }

    /**
     * 挖掘所需秒数（wiki Breaking 公式）。canHarvest 见 {@link HarvestChecker}。
     * speedMultiplier 由 ToolSpeedRegistry 提供；underwater/offGround 时 ×0.2（÷5）。
     */
    public static double breakSeconds(int blockStateId, double speedMultiplier, boolean canHarvest,
                                      boolean offGround, boolean inWaterWithoutAquaAffinity) {
        double hardness = hardness(blockStateId);
        if (hardness < 0) return Double.POSITIVE_INFINITY;
        if (hardness == 0) return 0;
        double speed = speedMultiplier;
        if (offGround) speed *= 0.2;
        if (inWaterWithoutAquaAffinity) speed *= 0.2;
        double damage = speed / hardness / (canHarvest ? 30.0 : 100.0);
        if (damage >= 1) return 0;
        return Math.ceil(1.0 / damage) / 20.0;
    }
}
