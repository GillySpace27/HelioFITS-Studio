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
        drawTextFloat(vp, lines, null, x, y);
    }

    /** As above, each line in its own RGBA (floats) when {@code colors} has one for it; the rest are light gray. */
    public static void drawTextFloat(Viewport vp, List<String> lines, @javax.annotation.Nullable List<float[]> colors, int x, int y) {
        drawTextFloat(vp, lines, colors, x, y, 1);
    }

    /** As above, at {@code sizeFactor} times the usual size. */
    public static void drawTextFloat(Viewport vp, List<String> lines, @javax.annotation.Nullable List<float[]> colors, int x, int y, double sizeFactor) {
        if (lines.isEmpty())
            return;

        SdfTextRenderer renderer = renderer();
        int textSize = (int) (logicalToPhysicalSize(FLOAT_TEXT_SIZE) * sizeFactor);
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
        int i = 0;
        for (String line : lines) {
            renderer.setColor(SHADOW_COLOR);
            renderer.draw(line, textX + SHADOW_OFFSET_X, baselineY + SHADOW_OFFSET_Y, 0, textScaleFactor);
            renderer.setColor(colors != null && i < colors.size() && colors.get(i) != null ? colors.get(i) : Colors.LightGrayFloat);
            renderer.draw(line, textX, baselineY, 0, textScaleFactor);
            baselineY -= lineStep;
            i++;
        }
        renderer.endRendering();
    }

    /** The width and height, in physical pixels, that {@link #drawTextFloat} gives {@code text}. */
    public static float[] floatTextSize(String text) {
        return floatTextSize(text, 1);
    }

    /** As above, at {@code sizeFactor} times the usual size. */
    public static float[] floatTextSize(String text, double sizeFactor) {
        SdfTextRenderer renderer = renderer();
        int textSize = (int) (logicalToPhysicalSize(FLOAT_TEXT_SIZE) * sizeFactor);
        return new float[]{renderer.measureWidth(text) * textSize / renderer.getFontSize(), textSize};
    }

    /** Lines centred in the viewport, half again the floating text size; for the empty-canvas hint. */
    public static void drawTextCentered(Viewport vp, List<String> lines) {
        drawText(vp, lines, false);
    }

    /** Like {@link #drawTextCentered}, but horizontally centered near the bottom edge, leaving the middle clear. */
    public static void drawTextBottom(Viewport vp, List<String> lines) {
        drawText(vp, lines, true);
    }

    private static void drawText(Viewport vp, List<String> lines, boolean bottom) {
        SdfTextRenderer renderer = renderer();
        int textSize = logicalToPhysicalSize(FLOAT_TEXT_SIZE * 3 / 2);
        float textScaleFactor = textSize / renderer.getFontSize();
        int lineStep = (int) (textSize * FLOAT_TEXT_LINE_HEIGHT);

        renderer.beginRendering(vp.width, vp.height);
        if (bottom) // wrap to the canvas width with a side margin; the block grows upward from the bottom margin
            lines = wrap(renderer, lines, vp.width - 2 * lineStep, textScaleFactor);
        int baselineY = bottom ? lineStep * (lines.size() + 1) // two lines of margin below the last line
                : vp.height / 2 + lineStep * (lines.size() - 1) / 2;
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

    /** Greedy word wrap of each line to maxWidth; a single word wider than that stays on its own line. */
    private static List<String> wrap(SdfTextRenderer renderer, List<String> lines, int maxWidth, float scale) {
        List<String> out = new java.util.ArrayList<>();
        for (String line : lines) {
            StringBuilder cur = new StringBuilder();
            for (String word : line.split(" ")) {
                String next = cur.isEmpty() ? word : cur + " " + word;
                if (!cur.isEmpty() && renderer.measureWidth(next) * scale > maxWidth) {
                    out.add(cur.toString());
                    cur = new StringBuilder(word);
                } else
                    cur = new StringBuilder(next);
            }
            out.add(cur.toString());
        }
        return out;
    }

    private GLText() {}
}
