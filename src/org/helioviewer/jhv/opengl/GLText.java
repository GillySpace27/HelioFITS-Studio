package org.helioviewer.jhv.opengl;

import java.util.List;

import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.opengl.text.SdfTextRenderer;

public final class GLText {
    public static final float[] SHADOW_COLOR = {0.1f, 0.1f, 0.1f, 0.75f};
    public static final int SHADOW_OFFSET_X = 2;
    public static final int SHADOW_OFFSET_Y = -2;
    private static final int FLOAT_TEXT_SIZE = 14;
    private static final float FLOAT_TEXT_LINE_HEIGHT = 1.1f;

    private static SdfTextRenderer renderer;

    public static SdfTextRenderer renderer() {
        if (renderer == null)
            renderer = new SdfTextRenderer();
        return renderer;
    }

    public static void dispose() {
        if (renderer != null) {
            renderer.dispose();
            renderer = null;
        }
    }

    private static int logicalToPhysicalSize(int logicalSize) {
        return (int) (logicalSize * Display.pixelScale[1]);
    }

    public static void drawTextFloat(Viewport vp, List<String> lines, int x, int y) {
        if (lines.isEmpty())
            return;

        SdfTextRenderer renderer = renderer();
        int textSize = logicalToPhysicalSize(FLOAT_TEXT_SIZE);
        float textScaleFactor = textSize / renderer.getFontSize();
        int lineStep = (int) (textSize * FLOAT_TEXT_LINE_HEIGHT);

        float width = 0;
        for (String line : lines)
            width = Math.max(width, renderer.measureWidth(line) * textScaleFactor);
        float height = textSize * FLOAT_TEXT_LINE_HEIGHT * lines.size();

        int textX = x;
        int textY = y;
        if (textX + width > vp.width)
            textX -= (int) (textX + width - vp.width);
        if (textY + height - textSize > vp.height)
            textY -= (int) (textY + height - textSize - vp.height);

        renderer.beginRendering(vp.width, vp.height);
        int baselineY = vp.height - textY;
        for (String line : lines) {
            renderer.setColor(SHADOW_COLOR);
            renderer.draw(line, textX + SHADOW_OFFSET_X, baselineY + SHADOW_OFFSET_Y, 0, textScaleFactor);
            renderer.setColor(Colors.LightGrayFloat);
            renderer.draw(line, textX, baselineY, 0, textScaleFactor);
            baselineY -= lineStep;
        }
        renderer.endRendering();
    }

    /** Lines centred in the viewport, half again the floating text size; for the empty-canvas hint. */
    public static void drawTextCentered(Viewport vp, List<String> lines) {
        SdfTextRenderer renderer = renderer();
        int textSize = logicalToPhysicalSize(FLOAT_TEXT_SIZE * 3 / 2);
        float textScaleFactor = textSize / renderer.getFontSize();
        int lineStep = (int) (textSize * FLOAT_TEXT_LINE_HEIGHT);

        renderer.beginRendering(vp.width, vp.height);
        int baselineY = vp.height / 2 + lineStep * (lines.size() - 1) / 2;
        for (String line : lines) {
            int x = (int) ((vp.width - renderer.measureWidth(line) * textScaleFactor) / 2);
            renderer.setColor(SHADOW_COLOR);
            renderer.draw(line, x + SHADOW_OFFSET_X, baselineY + SHADOW_OFFSET_Y, 0, textScaleFactor);
            renderer.setColor(Colors.LightGrayFloat);
            renderer.draw(line, x, baselineY, 0, textScaleFactor);
            baselineY -= lineStep;
        }
        renderer.endRendering();
    }

    private GLText() {}
}
