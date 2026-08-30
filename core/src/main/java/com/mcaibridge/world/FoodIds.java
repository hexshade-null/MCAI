package com.mcaibridge.world;

import java.util.Set;

/**
 * 可食用物品的协议数字 id 表。1.21.11 协议物品为纯数字 id、库无名称注册表，
 * 表内 id 全部由测试服 /give 在线单槽实测（2026-08-30，见 TEST_REPORT）；未收录的可经
 * survival.food_item_ids 配置补充。
 */
public final class FoodIds {
    private FoodIds() {
    }

    /** 1.21.11 协议物品 id：apple/bread/steak/cooked_porkchop/golden_carrot/baked_potato/carrot/pumpkin_pie/cookie。 */
    public static final Set<Integer> DEFAULT = Set.of(
            893,   // apple
            953,   // bread
            1111,  // cooked_beef (steak)
            984,   // cooked_porkchop
            1232,  // golden_carrot
            1229,  // baked_potato
            1227,  // carrot
            1241,  // pumpkin_pie
            1102); // cookie
}
