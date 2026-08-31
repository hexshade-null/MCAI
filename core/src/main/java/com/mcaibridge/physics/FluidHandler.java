package com.mcaibridge.physics;

import com.mcaibridge.world.BlockIds;
import com.mcaibridge.world.WorldModel;

/**
 * 流体/梯子状态采样：给定实体脚部坐标，判定水/岩浆/梯子（供物理引擎分支）。
 */
public final class FluidHandler {
    private FluidHandler() {
    }

    public record FluidState(boolean inWater, boolean inLava, boolean onLadder, boolean headInWater) {
    }

    public static FluidState sample(WorldModel world, double x, double y, double z) {
        int fx = (int) Math.floor(x);
        int fz = (int) Math.floor(z);
        int feetY = (int) Math.floor(y + 0.1);
        int eyeY = (int) Math.floor(y + VanillaPhysics.EYE_HEIGHT);
        int feet = world.blockAt(fx, feetY, fz);
        int mid = world.blockAt(fx, feetY + 1, fz);
        int head = world.blockAt(fx, eyeY, fz);
        boolean feetWater = BlockIds.isWater(feet) || BlockIds.isWater(mid);
        boolean feetLava = BlockIds.isLava(feet) || BlockIds.isLava(mid);
        boolean ladder = BlockIds.isLadder(feet) || BlockIds.isLadder(mid);
        return new FluidState(feetWater, feetLava, ladder, BlockIds.isWater(head));
    }
}
