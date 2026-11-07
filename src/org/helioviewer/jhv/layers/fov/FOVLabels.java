package org.helioviewer.jhv.layers.fov;

import java.util.List;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.opengl.GLText;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.opengl.text.SdfTextRenderer;

import org.joml.Vector3f;

/**
 * Names of the fields drawn as cones, written flat on the screen at the far end of each field, the
 * way HelioFleet labels its instruments: always upright and the same size whatever the zoom.
 */
final class FOVLabels {

    /**
     * {@code anchor} in the coordinates being drawn in (world in 3D, map units in 2D); {@code color}
     * premultiplied bytes, as the platform's; {@code align} 0 puts the text right of the anchor,
     * 1 left of it, 0.5 centred on it.
     */
    record Label(String text, double[] anchor, byte[] color, double align) {
        Label(String text, double[] anchor, byte[] color) {
            this(text, anchor, color, 0);
        }
    }

    private static final double TEXT_PX = 13;

    private FOVLabels() {}

    /** Projects with the scene's current matrices, so call it where the cones were drawn. */
    static void draw(Viewport vp, List<Label> labels) {
        if (labels.isEmpty())
            return;
        SdfTextRenderer renderer = GLText.renderer();
        float scale = (float) (TEXT_PX * Display.pixelScale[1] * Display.captureScale / renderer.getFontSize());
        int n = labels.size();
        float[] xs = new float[n], ys = new float[n];
        boolean[] on = new boolean[n];
        Vector3f ndc = new Vector3f();
        for (int i = 0; i < n; i++) {
            double[] a = labels.get(i).anchor();
            Transform.toNdc((float) a[0], (float) a[1], (float) a[2], ndc);
            on[i] = Float.isFinite(ndc.x) && Float.isFinite(ndc.y) && Math.abs(ndc.x) <= 1 && Math.abs(ndc.y) <= 1;
            float align = (float) labels.get(i).align();
            float gap = 4 * scale * renderer.getFontSize() / (float) TEXT_PX;
            float w = renderer.measureWidth(labels.get(i).text()) * scale;
            xs[i] = (float) ((ndc.x + 1) * .5 * vp.width) + gap * (1 - 2 * align) - align * w;
            ys[i] = (float) ((ndc.y + 1) * .5 * vp.height);
        }
        // Fields whose far ends meet (everything centred on the Sun) would print on top of one
        // another: move each label down a line until it clears those already placed.
        float line = 1.25f * renderer.getFontSize() * scale;
        float[] ws = new float[n];
        for (int i = 0; i < n; i++) {
            if (!on[i])
                continue;
            ws[i] = renderer.measureWidth(labels.get(i).text()) * scale;
            for (int tries = 0; tries < n && overlaps(i, xs, ys, ws, on, line); tries++)
                ys[i] -= line;
        }
        renderer.beginRendering(vp.width, vp.height);
        renderer.setColor(GLText.SHADOW_COLOR); // shadows first, all in one draw
        for (int i = 0; i < n; i++)
            if (on[i])
                renderer.draw(labels.get(i).text(), xs[i] + GLText.SHADOW_OFFSET_X, ys[i] + GLText.SHADOW_OFFSET_Y, 0, scale);
        for (int i = 0; i < n; i++) {
            if (!on[i])
                continue;
            renderer.setColor(brighten(labels.get(i).color()));
            renderer.draw(labels.get(i).text(), xs[i], ys[i], 0, scale);
        }
        renderer.endRendering();
    }

    private static boolean overlaps(int i, float[] xs, float[] ys, float[] ws, boolean[] on, float line) {
        for (int j = 0; j < i; j++)
            if (on[j] && xs[i] < xs[j] + ws[j] && xs[j] < xs[i] + ws[i] && Math.abs(ys[i] - ys[j]) < line)
                return true;
        return false;
    }

    /** The platform colour, mixed toward white when it is dark (Earth's blue, STEREO's navy) so it reads on black. */
    static float[] brighten(byte[] c) {
        float r = (c[0] & 0xff) / 255f, g = (c[1] & 0xff) / 255f, b = (c[2] & 0xff) / 255f;
        float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        float k = luma < 0.5f ? 0.55f * (1 - luma / 0.5f) + 0.15f : 0;
        return new float[]{r + (1 - r) * k, g + (1 - g) * k, b + (1 - b) * k, 1};
    }

}
