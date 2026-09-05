package org.helioviewer.jhv.layers;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.HdrGain;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.image.lut.LUTLabels;
import org.helioviewer.jhv.input.InputController;
import org.helioviewer.jhv.input.InputPointerListener;
import org.helioviewer.jhv.input.InputPointerMotionListener;
import org.helioviewer.jhv.input.PointerEvent;
import org.helioviewer.jhv.metadata.DetectorMask;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.opengl.GLImage;
import org.helioviewer.jhv.opengl.GLSLShape;
import org.helioviewer.jhv.opengl.GLSLSolar;
import org.helioviewer.jhv.opengl.GLSLSolarShader;
import org.helioviewer.jhv.opengl.GLTexture;
import org.helioviewer.jhv.opengl.GLText;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.opengl.text.SdfTextRenderer;
import org.helioviewer.jhv.view.View;

/**
 * Draws a colour-table legend along the bottom of the viewport.
 * <p>
 * A LUT with entries in /luts/lut-labels.json is categorical and is drawn as discrete blocks with
 * their category names; every other LUT is drawn as a continuous gradient labelled with its name.
 * Several layers can show a legend at once, so each is given a slot and they stack upward.
 */
public final class Colorbar {

    // Fractions of viewport height.
    private static final double BAR_HEIGHT = 0.022;
    private static final double LABEL_HEIGHT = 0.017;
    private static final double MARGIN = 0.010;
    private static final double SLOT_GAP = 0.004;
    private static final double LABEL_PAD = 3;
    private static final float MIN_LABEL_SCALE = 0.45f;

    // The globe is routinely framed to fill the viewport edge to edge (Zoom-Fit, Actual Size), so
    // there is no guarantee of empty space at the bottom for the legend to sit in. A full-width
    // backing panel is what makes it read as a footer the image sits above rather than a HUD
    // straddling the image; the swatches and labels alone were fully opaque but only over their
    // own narrow strip, so the globe still showed through on both sides and between rows.
    private static final byte[] PANEL_BG = Colors.bytes(18, 18, 20, 235);
    private static final byte[] OVER_RANGE_EDGE = Colors.bytes(200, 200, 210, 235);
    private static final byte[] KNEE_MARK = Colors.bytes(230, 230, 240, 235);
    private static final double PANEL_PAD = 4; // px of breathing room around the swatches

    // Each enabled layer draws its own colorbar in a separate pass (ImageLayer.renderFloat), so
    // when several are stacked, whichever one happens to render last paints on top of the others.
    // A background reaching down to the viewport floor -- to read as one continuous footer instead
    // of a stack of separate boxes -- would then paint over every lower slot's already-drawn
    // swatches, near-erasing them behind its own near-opaque panel. Each slot's background must
    // stay confined to its own band; SLOT_GAP already leaves enough room that adjacent panels meet
    // without a visible seam.

    private static final int HOVER_OFFSET_X = 12;
    private static final int HOVER_OFFSET_Y = 20; // subtracted from mouseY, so the tooltip sits above the cursor -- the bar itself is already near the bottom edge

    private final GLSLShape quads = new GLSLShape(true);
    private final BufVertex vex = new BufVertex(8 * 1024 * GLSLShape.stride);

    // The continuous bar is not painted from the table's 256 entries any more. It is a one-row
    // half-float texture of the VALUES the bar stands for, drawn through GLSLSolarShader.legend,
    // which is getColor() and nothing else: Levels, response, the colour table, the HDR gain, its
    // mode and its knee are all applied by the same code that applies them to the picture. That is
    // what lets the bar be brighter than interface white on the EDR canvas, and what makes the
    // knee's expansion visible on it, and it is why there is no curve to keep in step here.
    private static final int RAMP_TEXELS = 1024;
    private GLTexture rampTex;
    private GLTexture blankMask; // getColor discards where the mask is zero; the legend has no occulter
    private final ShortBuffer ramp = BufferUtils.newShortBuffer(RAMP_TEXELS);
    private double rampOffset = Double.NaN, rampScale = Double.NaN, rampGain = Double.NaN, rampSplit = Double.NaN;
    private final List<String> hoverText = new ArrayList<>();
    // AWT/mouse convention (origin top-left); -1 sentinel keeps Viewport.contains() false until
    // the first real mouseMoved arrives.
    private int mouseX = -1, mouseY = -1;

    private final class HoverListener implements InputPointerListener, InputPointerMotionListener {
        @Override
        public void mouseMoved(PointerEvent e) {
            mouseX = e.x();
            mouseY = e.y();
            DisplayController.display(); // render loop is redraw-on-demand; without this the tooltip lags behind the cursor
        }

        @Override
        public void mouseExited(PointerEvent e) {
            mouseX = mouseY = -1;
            DisplayController.display();
        }
    }

    private final HoverListener hoverListener = new HoverListener();

    void render(Viewport vp, GLImage glImage, View.ImageData imageData, boolean rhefActive, int slot) {
        LUT lut = glImage.getLUT();
        boolean inverted = glImage.getInvertLUT();
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
        double slotBottom = margin + slot * slotH; // this slot's own floor, not the viewport's
        double yBar = slotBottom + labelH; // labels sit under their swatches
        double yTop = yBar + barH;
        if (yTop > vp.height) // out of room; drop this legend rather than draw over the image
            return;

        // The bar is split whenever the HDR canvas is carrying headroom: the left part is the colour
        // table's 0 to 1, the right part is what lies above the top of the range, which is what
        // the display is actually showing as brighter-than-white. Not optional, because it is not
        // a decoration: without it the bar stops at 1 and every over-range pixel in the picture is
        // unrepresented on the legend beside it. Marking clipped pixels in the PICTURE is the View
        // menu's "show clipped pixels", which is a separate question and stays a separate switch.
        float gain = HdrGain.current(false);
        boolean headroom = gain > 1 && groups == null;
        double xSplit = headroom ? x0 + (x1 - x0) / OVER_RANGE_SPAN : x1;

        // Depth testing is on for the whole frame (GLRenderer sets it up once, for the 3D scene),
        // and is still active here: this is a flat screen-space overlay drawn after the sphere,
        // but at whatever pixels the sphere's curved surface happens to sit closer in the depth
        // buffer than this quad, the depth test discards this fragment and the sphere shows
        // through anyway -- inconsistently, following the sphere's curvature rather than draw
        // order. SdfTextRenderer already disables depth testing around itself for the same reason
        // (see its beginRendering/endRendering); these passes need the same treatment.
        GL.glDisable(GL.DEPTH_TEST);

        // 1. The backing panel, and a categorical table's blocks, as flat quads.
        vex.clear();
        // Full width, but confined to this slot's own band top and bottom -- see the comment on
        // PANEL_BG above for why it must not reach past that into a neighbouring slot's territory.
        quad(0, Math.max(0, slotBottom - PANEL_PAD), vp.width, yTop + PANEL_PAD, PANEL_BG);
        if (groups != null)
            buildBlocks(argb, inverted, groups, x0, x1, yBar, yTop);
        drawQuads(vp);

        // 2. A continuous table's ramp, through the picture's own pipeline.
        if (groups == null)
            drawRamp(vp, glImage, imageData, rhefActive, x0, xSplit, x1, yBar, yTop, headroom ? gain : 1);

        // 3. The marks on top: the knee, and the edge of the headroom section.
        if (groups == null) {
            vex.clear();
            buildKneeMark(x0, xSplit, yBar, yTop);
            if (headroom) {
                double t = Math.max(1, (yTop - yBar) * 0.06);
                quad(xSplit - t / 2, yBar, xSplit + t / 2, yTop, OVER_RANGE_EDGE);
            }
            drawQuads(vp);
        }
        GL.glEnable(GL.DEPTH_TEST);

        drawLabels(vp, lut, groups, x0, x1, yBar, labelH);
        updateHover(vp, lut, groups, glImage, imageData, rhefActive, x0, xSplit, x1, yBar, yTop, labelH, headroom ? gain : 1);
    }

    // Hovering a categorical block exposes the raw index it covers (several pixel values can share
    // one legend block, e.g. a coronal hole and its boundary). Hovering a continuous ramp tries to
    // recover the physical pixel value the colour represents, undoing this layer's Levels/response
    // adjustment and then the FITS decoder's own stretch (see ImageBuffer.PhysicalScale and
    // FITSImage.inverseMapping); when that is not possible -- a server/JPX-backed layer never had
    // FITS DN to begin with, or RHEF's rank transform and difference deltas have no simple inverse
    // -- it falls back to the plain display-range percentage.
    private void updateHover(Viewport vp, LUT lut, List<LUTLabels.Group> groups, GLImage glImage, View.ImageData imageData,
                             boolean rhefActive, double x0, double xSplit, double x1, double yBar, double yTop, double labelH, float gain) {
        if (!vp.contains(mouseX, mouseY))
            return;
        double localX = mouseX - vp.x;
        double localY = vp.height - (mouseY - vp.yAWT);
        if (localX < x0 || localX > x1 || localY < yBar - labelH || localY > yTop)
            return;

        // The bar is two scales when there is headroom to show: [x0, xSplit] is the colour table
        // from 0 to 1, and [xSplit, x1] carries 1 up to the gain. The pointer has to be read on
        // whichever it is over, or the whole right-hand section would report as 1.
        double frac = localX <= xSplit
                ? Math.clamp((localX - x0) / (xSplit - x0), 0, 1)
                : 1 + (localX - xSplit) / (x1 - xSplit) * (gain - 1);
        String text;
        if (groups == null) {
            String physical = physicalValueText(frac, glImage, imageData, rhefActive);
            text = physical != null ? physical + " · " + lut.name() : String.format("%.0f%% · %s", frac * 100, lut.name());
        } else {
            double blockW = (x1 - x0) / groups.size();
            int g = Math.clamp((int) (frac * groups.size()), 0, groups.size() - 1);
            LUTLabels.Group group = groups.get(g);
            int[] indices = group.indices();
            double bx = x0 + g * blockW;
            double stripeW = blockW / indices.length;
            int s = Math.clamp((int) ((localX - bx) / stripeW), 0, indices.length - 1);
            // Block position comes from group order (lut-labels.json), never from the colortable,
            // so it names the same raw index regardless of "inverted" -- that flag only changes
            // which colour color() looks up for it, not which data value this screen slot is.
            text = "idx " + indices[s] + " · " + group.label();
        }
        hoverText.clear();
        hoverText.add(text);
        GLText.drawTextFloat(vp, hoverText, mouseX + HOVER_OFFSET_X, mouseY - HOVER_OFFSET_Y);
    }

    // frac is the value the shader fed into the LUT lookup: display-space, after this layer's own
    // Levels (brightOffset/brightScale) and, for AIA, its instrument response-factor correction --
    // see GLImage.applyFilters. Undo that first to get back to the decoder's raw [0,1] texture
    // value, then hand it to ImageBuffer.PhysicalScale to undo the stretch and min/max normalize.
    @Nullable
    private static String physicalValueText(double frac, GLImage glImage, View.ImageData imageData, boolean rhefActive) {
        if (rhefActive || glImage.getDifferenceMode() != GLImage.DifferenceMode.None)
            return null; // RHEF's rank transform and difference deltas have no simple physical inverse
        ImageBuffer.PhysicalScale scale = imageData.imageBuffer().physicalScale();
        if (scale == null)
            return null; // server/JPX-backed layer: the client never had the original FITS DN

        double denom = glImage.getBrightScale() * imageData.metaData().getResponseFactor();
        if (denom == 0)
            return null;
        double texRaw = (frac - glImage.getBrightOffset()) / denom;
        if (texRaw < 0)
            return null; // below this layer's Levels window: no one value corresponds

        // Above 1 there IS a value: the decoder stores the ratio to the top of the range up to
        // FITSImage.OVER_RANGE_CEILING, and PhysicalScale.toPhysical continues past 1 on purpose.
        // Refusing here was correct only while everything above white was clipped to white; on the
        // HDR canvas those pixels are the ones being shown, and they were the ones with no readout.
        String value = String.format("%.4g", scale.toPhysical(texRaw));
        return texRaw > 1 ? String.format("%s (%.2fx range)", value, texRaw) : value;
    }

    /**
     * How much of the bar's width the 0-to-1 range keeps when a headroom section is drawn. The
     * rest carries 1 to the gain, compressed, because the headroom is a factor of a few while the
     * range below it is the whole picture: giving them equal width would misrepresent both.
     */
    private static final double OVER_RANGE_SPAN = 1.35;

    private void drawQuads(Viewport vp) {
        if (vex.getCount() == 0)
            return;
        Transform.pushProjection();
        Transform.setOrtho2DProjection(0, vp.width, 0, vp.height);
        Transform.pushView();
        Transform.setIdentityView();
        quads.setVertexRepeatable(vex);
        quads.renderShape(GL.TRIANGLES);
        Transform.popView();
        Transform.popProjection();
    }

    /**
     * The ramp, drawn by getColor() over a texture of the values the bar stands for.
     *
     * <p>The bar's horizontal axis is the value the colour table is indexed by, after Levels:
     * 0 to 1 across the table's part and 1 to the gain across the headroom. The shader applies
     * Levels itself, so what the texture holds is the value BEFORE them, worked back through the
     * same offset and scale GLImage binds. Then the bar and the picture disagree only if
     * getColor() disagrees with itself.
     *
     * <p>The full-screen strip is drawn into a viewport set to the bar's own rectangle, which is
     * how a quad in clip space becomes a bar in pixels without a second vertex buffer.
     */
    private void drawRamp(Viewport vp, GLImage glImage, View.ImageData imageData, boolean rhefActive,
                          double x0, double xSplit, double x1, double yBar, double yTop, float gain) {
        double offset = glImage.getBrightOffset();
        double scale = glImage.getBrightScale() * (rhefActive ? 1 : imageData.metaData().getResponseFactor());
        if (scale == 0)
            return;
        double split = (xSplit - x0) / (x1 - x0);
        if (offset != rampOffset || scale != rampScale || gain != rampGain || split != rampSplit) {
            rampOffset = offset;
            rampScale = scale;
            rampGain = gain;
            rampSplit = split;
            ramp.clear();
            for (int i = 0; i < RAMP_TEXELS; i++) {
                double p = (i + .5) / RAMP_TEXELS;
                double indexed = p <= split ? p / split : 1 + (p - split) / (1 - split) * (gain - 1);
                ramp.put(Float.floatToFloat16((float) ((indexed - offset) / scale)));
            }
            ramp.flip();
            rampTex.bind();
            GLTexture.copyHalfImage(RAMP_TEXELS, 1, GL.LINEAR, ramp);
        }

        GLSLSolarShader.legend.use();
        glImage.applyFilters(rhefActive, true); // display block and colour table; not its image or mask
        rampTex.bind();
        blankMask.bind();

        int px = vp.x + (int) Math.round(x0), py = vp.yGL + (int) Math.round(yBar);
        int pw = (int) Math.round(x1 - x0), ph = (int) Math.round(yTop - yBar);
        if (pw < 1 || ph < 1)
            return;
        GL.glViewport(px, py, pw, ph);
        GLSLSolar.quad.render();
        GL.glViewport(vp.x, vp.yGL, vp.width, vp.height);
    }

    /**
     * A line where the knee sits, when a knee mode is in use.
     *
     * <p>The knee is a fraction of the data range feeding the colour table, so it is a position
     * along the 0-to-1 part of the bar: everything to its right is what the HDR mapping expands.
     * Marking it is the difference between "the highlights are brighter" and knowing which ones.
     */
    private void buildKneeMark(double x0, double x1, double yBar, double yTop) {
        HdrGain.Mode mode = HdrGain.mode();
        if (HdrGain.current(false) <= 1 || (mode != HdrGain.Mode.HardKnee && mode != HdrGain.Mode.SoftKnee))
            return;
        double x = x0 + HdrGain.knee() * (x1 - x0);
        double w = Math.max(1, (yTop - yBar) * 0.05);
        quad(x - w / 2, yBar, x + w / 2, yTop, KNEE_MARK);
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
        rampTex = new GLTexture(GL.TEXTURE_2D, GLTexture.Unit.ZERO);
        blankMask = new GLTexture(GL.TEXTURE_2D, GLTexture.Unit.THREE);
        blankMask.bind();
        blankMask.copyImageBuffer(DetectorMask.NONE.getImageBuffer(), GL.NEAREST);
        rampOffset = rampScale = rampGain = rampSplit = Double.NaN; // texture objects are new: upload again
        InputController.addListener(hoverListener);
    }

    void dispose() {
        quads.dispose();
        if (rampTex != null)
            rampTex.delete();
        if (blankMask != null)
            blankMask.delete();
        InputController.removeListener(hoverListener);
    }
}
