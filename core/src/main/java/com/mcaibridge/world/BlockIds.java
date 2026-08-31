package com.mcaibridge.world;

import java.util.Map;

/**
 * 1.21.11 协议数字 id → 方块语义（2026-08-31 测试服 /setblock 实测，见 TEST_REPORT）。
 * 库无方块名注册表，此表是扫描/挖掘/流体识别的唯一依据；范围段=同方块多状态（按属性展开）。
 * 来源交叉验证：水+岩浆相邻生成黑曜石验证 obsidian=3168；红石矿发光态验证 2 态布局。
 */
public final class BlockIds {
    private BlockIds() {
    }

    public record Range(String name, int base, int end) {
        public boolean contains(int id) {
            return id >= base && id <= end;
        }
    }

    /** 原木（axis x/y/z 三态连续，实测 axis=y 默认值 ±1）。 */
    public static final Range[] LOGS = {
            new Range("oak_log", 136, 138),        // 实测 x=136 y=137
            new Range("spruce_log", 139, 141),     // 默认 140
            new Range("birch_log", 142, 144),
            new Range("jungle_log", 145, 147),
            new Range("acacia_log", 148, 150),
            new Range("cherry_log", 151, 153),
            new Range("dark_oak_log", 154, 156),
            new Range("pale_oak_log", 157, 159),
            new Range("mangrove_log", 160, 162),
    };

    /** 矿石（normal/deepslate 成对；红石带发光态 2×2）。 */
    public static final Range[] ORES = {
            new Range("coal_ore", 133, 133), new Range("deepslate_coal_ore", 134, 134),
            new Range("copper_ore", 25111, 25111), new Range("deepslate_copper_ore", 25112, 25112),
            new Range("iron_ore", 131, 131), new Range("deepslate_iron_ore", 132, 132),
            new Range("gold_ore", 129, 129), new Range("deepslate_gold_ore", 130, 130), // 相邻对推断
            new Range("diamond_ore", 5106, 5106), new Range("deepslate_diamond_ore", 5107, 5107),
            new Range("emerald_ore", 9372, 9372), new Range("deepslate_emerald_ore", 9373, 9373),
            new Range("lapis_ore", 563, 563), new Range("deepslate_lapis_ore", 564, 564),
            new Range("redstone_ore", 6681, 6682), new Range("deepslate_redstone_ore", 6683, 6684), // 含 lit 态
    };

    /** 岩石族（单态，用于硬度/工具匹配）。 */
    public static final Map<String, Integer> SINGLE = Map.ofEntries(
            Map.entry("stone", 1), Map.entry("granite", 2), Map.entry("diorite", 4),
            Map.entry("andesite", 6), Map.entry("tuff", 23250), Map.entry("deepslate", 27722),
            Map.entry("cobbled_deepslate", 27724), Map.entry("cobblestone", 14),
            Map.entry("blackstone", 21629), Map.entry("netherrack", 6796),
            Map.entry("end_stone", 9276), Map.entry("obsidian", 3168), Map.entry("bedrock", 85),
            Map.entry("dirt", 10), Map.entry("sand", 118), Map.entry("gravel", 124),
            Map.entry("clay", 6745), Map.entry("oak_planks", 15));

    public static final Range GRASS_BLOCK = new Range("grass_block", 8, 9);   // snowy 两态
    public static final Range WATER = new Range("water", 86, 101);            // 86+level
    public static final Range LAVA = new Range("lava", 102, 117);             // 102+level
    public static final Range LADDER = new Range("ladder", 5519, 5526);       // facing×waterlogged

    public static boolean isLog(int id) {
        for (Range r : LOGS) if (r.contains(id)) return true;
        return false;
    }

    public static boolean isOre(int id) {
        for (Range r : ORES) if (r.contains(id)) return true;
        return false;
    }

    public static boolean isWater(int id) {
        return WATER.contains(id);
    }

    public static boolean isLava(int id) {
        return LAVA.contains(id);
    }

    public static boolean isFluid(int id) {
        return isWater(id) || isLava(id);
    }

    public static boolean isLadder(int id) {
        return LADDER.contains(id);
    }

    /** id → 方块名（表外返回 null，调用方退化为"非空=实心"）。 */
    public static String name(int id) {
        if (id == 0) return "air";
        for (Range r : LOGS) if (r.contains(id)) return r.name();
        for (Range r : ORES) if (r.contains(id)) return r.name();
        if (GRASS_BLOCK.contains(id)) return "grass_block";
        if (isWater(id)) return "water";
        if (isLava(id)) return "lava";
        if (isLadder(id)) return "ladder";
        for (var e : SINGLE.entrySet()) {
            if (e.getValue() == id) return e.getKey();
        }
        return null;
    }
}
