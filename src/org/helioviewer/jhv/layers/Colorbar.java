package org.helioviewer.jhv.layers;

import java.util.List;

import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.image.lut.LUTLabels;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.opengl.GLSLShape;
import org.helioviewer.jhv.opengl.GLText;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.opengl.text.SdfTextRenderer;

/**
 * Draws a colour-table legend along the bottom of the viewport.
 * <p>
 * A LUT with entries in /luts/lut-labels.json is categorical and is drawn as discrete blocks with
 * their category names; every other LUT is drawn as a continuous gradient labelled with its name.
 * Several layers can show a legend at once, so each is given a slot and they stack upward.
 */
final class Colorbar {

    // Fractions of viewport height.
    private static final double BAR_HEIGHT = 0.022;
    private static final double LABEL_HEIGHT = 0.017;
    private static final double MARGIN = 0.010;
    private static final double SLOT_GAP = 0.004;
    private static final int GRADIENT_STEPS = 128;
    private static final double LABEL_PAD = 3;
    private static final float MIN_LABEL_SCALE = 0.45f;

    private final GLSLShape quads = new GLSLShape(true);
    private final BufVertex vex = new BufVertex(8 * 1024 * GLSLShape.stride);

    void render(Viewport vp, LUT lut, boolean inverted, int slot) {
        if (lut == null)
            return;

        List<LUTLabels.Group> groups = LUTLabels.get(lut.name());
        int[] argb = lut.lut8();
        if (argb.length == 0)
            return;

        double barH = vp.height * BAR_HEIGHT;
        double labelH = vp.height * LABEL_HEIGHT;
        double margin = vp.height * MARGIN;
        double slotH = barH + labelH + vp.height * SLOT_GAP;

        double x0 = margin;
        double x1 = vp.width - margin;
        double yBar = margin + slot * slotH + labelH; // labels sit under their swatches
        double yTop = yBar + barH;
        if (yTop > vp.height) // out of room; drop this legend rather than draw over the image
            return;

        vex.clear();
        if (groups == null)
            buildGradient(argb, inverted, x0, x1, yBar, yTop);
        else
            buildBlocks(argb, inverted, groups, x0, x1, yBar, yTop);

        Transform.pushProjection();
        Transform.setOrtho2DProjection(0, vp.width, 0, vp.height);
        Transform.pushView();
        Transform.setIdentityView();
        quads.setVertexRepeatable(vex);
        quads.renderShape(GL.TRIANGLES);
        Transform.popView();
        Transform.popProjection();

        drawLabels(vp, lut, groups, x0, x1, yBar, labelH);
    }

    /** Continuous LUT: a smooth ramp across the full width. */
    private void buildGradient(int[] argb, boolean inverted, double x0, double x1, double yBar, double yTop) {
        double w = (x1 - x0) / GRADIENT_STEPS;
        for (int i = 0; i < GRADIENT_STEPS; i++) {
            double frac = i / (double) (GRADIENT_STEPS - 1);
            int idx = (int) Math.round(frac * (argb.length - 1));
            quad(x0 + i * w, yBar, x0 + (i + 1) * w, yTop, color(argb, idx, inverted));
        }
    }

    /**
     * Categorical LUT: one block per group, equal width. A group naming several indices (a coronal
     * hole and its boundary) shows them as adjacent stripes inside its own block, so the legend
     * stays aligned with the label row beneath it.
     */
    private void buildBlocks(int[] argb, boolean inverted, List<LUTLabels.Group> groups,
                             double x0, double x1, double yBar, double yTop) {
        double blockW = (x1 - x0) / groups.size();
        for (int g = 0; g < groups.size(); g++) {
            int[] indices = groups.get(g).indices();
            double bx = x0 + g * blockW;
            double stripeW = blockW / indices.length;
            for (int s = 0; s < indices.length; s++)
                quad(bx + s * stripeW, yBar, bx + (s + 1) * stripeW, yTop, color(argb, indices[s], inverted));
        }
    }

    private void drawLabels(Viewport vp, LUT lut, List<LUTLabels.Group> groups,
                            double x0, double x1, double yBar, double labelH) {
        SdfTextRenderer renderer = GLText.renderer();
        float natural = (float) (labelH * 0.85) / renderer.getFontSize();
        double y = yBar - labelH;
        renderer.beginRendering(vp.width, vp.height);

        if (groups == null) {
            drawShadowed(renderer, lut.name(), x0, y, natural);
            renderer.endRendering();
            return;
        }

        double blockW = (x1 - x0) / groups.size();
        double avail = blockW - 2 * LABEL_PAD;

        // Shrink so the widest label fits its block, but not past MIN_LABEL_SCALE -- below that
        // the text is unreadable anyway and truncation is the better trade.
        float scaleFactor = natural;
        for (LUTLabels.Group group : groups) {
            float w = renderer.measureWidth(group.label()) * natural;
            if (w > avail)
                scaleFactor = Math.min(scaleFactor, (float) (natural * avail / w));
        }
        scaleFactor = Math.max(scaleFactor, natural * MIN_LABEL_SCALE);

        for (int g = 0; g < groups.size(); g++) {
            String label = fit(renderer, groups.get(g).label(), scaleFactor, avail);
            double w = renderer.measureWidth(label) * scaleFactor;
            double cx = x0 + g * blockW + (blockW - w) / 2; // centred under its block
            drawShadowed(renderer, label, cx, y, scaleFactor);
        }
        renderer.endRendering();
    }

    /** Trim with an ellipsis when even the shrunk label overflows its block. */
    private static String fit(SdfTextRenderer renderer, String label, float scaleFactor, double avail) {
        if (renderer.measureWidth(label) * scaleFactor <= avail)
            return label;
        for (int n = label.length() - 1; n > 0; n--) {
            String candidate = label.substring(0, n) + '.'; // the SDF font has no U+2026
            if (renderer.measureWidth(candidate) * scaleFactor <= avail)
                return candidate;
        }
        return "";
    }

    private static void drawShadowed(SdfTextRenderer renderer, String text, double x, double y, float scaleFactor) {
        renderer.setColor(GLText.SHADOW_COLOR);
        renderer.draw(text, (int) x + GLText.SHADOW_OFFSET_X, (int) y + GLText.SHADOW_OFFSET_Y, 0, scaleFactor);
        renderer.setColor(Colors.LightGrayFloat);
        renderer.draw(text, (int) x, (int) y, 0, scaleFactor);
    }

    /** Mirrors the shader's inversion so the legend matches what is on screen. */
    private static byte[] color(int[] argb, int index, boolean inverted) {
        int i = Math.clamp(inverted ? argb.length - 1 - index : index, 0, argb.length - 1);
        int p = argb[i];
        return Colors.bytes((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
    }

    private void quad(double ax, double ay, double bx, double by, byte[] col) {
        vex.putVertex((float) ax, (float) ay, 0, 1, col);
        vex.putVertex((float) bx, (float) ay, 0, 1, col);
        vex.putVertex((float) bx, (float) by, 0, 1, col);

        vex.putVertex((float) ax, (float) ay, 0, 1, col);
        vex.putVertex((float) bx, (float) by, 0, 1, col);
        vex.putVertex((float) ax, (float) by, 0, 1, col);
    }

    void init() {
        quads.init();
    }

    void dispose() {
        quads.dispose();
    }
}
