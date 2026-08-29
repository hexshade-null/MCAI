package com.mcaibridge.gui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;

/**
 * 皮肤平面预览：从 64x64 / 64x32 皮肤拼装正面人形（头/身/双臂/双腿）。
 */
public class SkinPreview extends Canvas {
    private static final int SCALE = 7;

    public SkinPreview() {
        super(16 * SCALE, 32 * SCALE);
    }

    public void render(byte[] png) {
        GraphicsContext g = getGraphicsContext2D();
        g.clearRect(0, 0, getWidth(), getHeight());
        if (png == null || png.length == 0) return;
        Image img = new Image(new ByteArrayInputStream(png));
        if (!img.isError() && img.getWidth() > 0) {
            boolean legacy = img.getHeight() == 32; // 64x32 旧版布局：左右肢体镜像
            drawPart(g, img, 8, 8, 8, 8, 4, 0);     // 头
            drawPart(g, img, 20, 20, 8, 12, 4, 8);  // 身体
            drawPart(g, img, 44, 20, 4, 12, 0, 8);  // 右臂
            if (legacy) drawPart(g, img, 44, 20, 4, 12, 12, 8);
            else drawPart(g, img, 36, 52, 4, 12, 12, 8);   // 左臂
            drawPart(g, img, 4, 20, 4, 12, 4, 20);  // 右腿
            if (legacy) drawPart(g, img, 4, 20, 4, 12, 8, 20);
            else drawPart(g, img, 20, 52, 4, 12, 8, 20);   // 左腿
        }
    }

    private void drawPart(GraphicsContext g, Image img, int sx, int sy, int sw, int sh, int dxUnits, int dyUnits) {
        g.drawImage(img, sx, sy, sw, sh, dxUnits * SCALE, dyUnits * SCALE, sw * SCALE, sh * SCALE);
    }
}
