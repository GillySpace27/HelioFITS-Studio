package org.helioviewer.jhv.display;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.math.PolarBasis;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.metadata.FitsMetaData;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.opengl.GLText;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.opengl.text.SdfTextRenderer;
import org.helioviewer.jhv.view.View;

import org.joml.Vector3f;

/**
 * The sun pizza's labels (HS-25): each wedge named by the channel it shows ("17.1 nm" or
 * "171 &Aring;", the colour name after it when the palette asks), in a colour from that layer's
 * colour table.
 *
 * <p>Drawn once per viewport after the scene (GLRenderer.renderScene), in viewport pixels, so the
 * text reads upright at one size whatever the projection or the camera does. Each label's anchor
 * is a point on the cut worked out on the CPU in the frame the shader cuts in
 * (imageCommon.frag, clipPizza) and carried to the screen by the scene's own matrices
 * ({@link Transform#toNdc}): the image's own solar frame in Orthographic, the observer's frame
 * at the page radius the imagery lands at in Helioradial (half of MapScale.toUnitY, as
 * HelioradialGrid's ringRho; WarpGeometry.warpRadius put every label at the edge of the view,
 * found in a headless render), the page in Helioradial Unrolled and HPC. A recording captures the same
 * framebuffer, so the labels are in recorded movies too.
 *
 * <p>Wedges only: squares and polar tiles are not labelled. Nothing here holds state; the pure
 * helpers are package-level for PizzaLabelsCheck.
 */
public final class PizzaLabels {

    /** Label height in logical pixels at label size 100%. */
    static final double TEXT_SIZE = 14;
    /** A label's centre sits this many text heights inside its wedge's outer edge. */
    static final double MARGIN_LINES = 1.1;
    /** The share of the visible half field taken as the edge of a wedge that has no outer radius. */
    static final double FIELD = 0.95;
    /** Where in the colour table the label colour is read: a mid-to-bright entry. */
    static final double LUT_PICK = 0.7;
    /** Labels darker than this (Rec. 709 luma, 0 to 1) move along the table until they are not. */
    static final double MIN_LUMA = 0.45;

    // The SDF atlas has no A with a ring (resources/sdf/charset), so it is drawn as an A with a
    // smaller degree sign over it. Offsets in em of the label's font, from the atlas's own glyph
    // bounds (resources/sdf/atlas-128.json, "A" and the degree sign): centred on the A's advance,
    // the ring's foot just above the A's apex. Needs a visual check.
    static final char ANGSTROM = 'Å';
    private static final float RING_SCALE = 0.75f;
    private static final float RING_DX = 0.139f;
    private static final float RING_DY = 0.45f;
    /** Half the cap height of the font, in em: lifts the baseline so a label is centred on its anchor. */
    private static final float HALF_CAP = 0.36f;

    private PizzaLabels() {}

    // -- pure helpers ---------------------------------------------------------------------------

    /** One label: cell {@code cell} of a ring, owned by slot {@code slot}, centred on position angle {@code angle} (radians, counterclockwise from north, turn included). */
    record Cell(int cell, int slot, double angle) {}

    /**
     * The wedges of a ring with {@code n} slots repeated {@code repeats} times and turned by
     * {@code turn} radians, each at its mid angle: every wedge, or only the first repeat's.
     * The cell a mid angle falls in is the cell clipPizza puts it in, and its owner is
     * {@link Pizza#slotOfCell}, the shader's pizzaSlotOf. Pure.
     */
    static List<Cell> cells(int n, int repeats, int seed, double turn, Pizza.Labels labels) {
        if (n <= 0 || labels == Pizza.Labels.Off)
            return List.of();
        int k = n * Math.clamp(repeats, 1, Pizza.MAX_REPEATS);
        int count = labels == Pizza.Labels.First ? n : k;
        double w = 2 * Math.PI / k;
        List<Cell> out = new ArrayList<>(count);
        for (int c = 0; c < count; c++)
            out.add(new Cell(c, Pizza.slotOfCell(c, n, seed), wrapRadians(turn + (c + .5) * w)));
        return out;
    }

    static double wrapRadians(double a) {
        double t = a % (2 * Math.PI);
        return t < 0 ? t + 2 * Math.PI : t;
    }

    /**
     * Angstroms in one of a wavelength unit as a FITS WAVEUNIT card spells it; NaN for a unit not
     * known here. No card at all reads as angstrom, the unit Studio already shows a bare
     * wavelength in (HeliadTab's wavelength column). Pure.
     */
    static double angstromPerUnit(String unit) {
        return switch (unit.strip().toLowerCase(Locale.ROOT)) {
            case "", "a", "angstrom", "angstroms", "å", "0.1nm", "0.1 nm" -> 1;
            case "nm", "nanometer", "nanometers", "nanometre", "nanometres" -> 10;
            case "um", "µm", "μm", "micron", "microns", "micrometer", "micrometers", "micrometre", "micrometres" -> 1e4;
            case "mm" -> 1e7;
            case "cm" -> 1e8;
            case "m", "meter", "meters", "metre", "metres" -> 1e10;
            default -> Double.NaN;
        };
    }

    /** A header's wavelength in angstroms, or NaN when the measurement is not a positive number or the unit is unknown. Pure. */
    static double wavelengthAngstrom(String measurement, String unit) {
        double value;
        try {
            value = Double.parseDouble(measurement.strip());
        } catch (NumberFormatException e) { // "magnetogram", "Orange Clear": not a wavelength
            return Double.NaN;
        }
        double a = value * angstromPerUnit(unit);
        return Double.isFinite(a) && a > 0 ? a : Double.NaN;
    }

    /** "17.1 nm", or "171 &Aring;" in angstroms; null for NaN. Pure. */
    @Nullable
    static String wavelengthText(double angstrom, boolean inAngstrom) {
        if (!Double.isFinite(angstrom) || angstrom <= 0)
            return null;
        if (inAngstrom)
            return plain(angstrom, 1) + " " + ANGSTROM;
        return plain(angstrom / 10, 2) + " nm";
    }

    private static String plain(double v, int decimals) {
        return BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** The wavelength, or the layer's name when there is none, then the colour name when asked for and given. Pure. */
    static String labelText(@Nullable String wavelength, String name, boolean colourNames, @Nullable String colourName) {
        String base = wavelength != null ? wavelength : name;
        if (colourNames && colourName != null && !colourName.isBlank())
            return base + " " + colourName.strip();
        return base;
    }

    /**
     * Where a label's centre goes between a wedge's inner and outer edges (any one radial unit):
     * {@code margin} inside the outer edge for a ring that starts at the centre, but never below
     * the middle; the middle for the outer ring. Pure.
     */
    static double labelRadius(boolean outerRing, double inner, double outer, double margin) {
        double middle = .5 * (inner + outer);
        return outerRing ? middle : Math.max(outer - margin, middle);
    }

    /**
     * The label colour from a colour table (ARGB entries, as {@code LUT.lut8()} gives them): the
     * entry {@link #LUT_PICK} of the way up the table as drawn (inverted or not), or the nearest
     * entry to it that is at least {@link #MIN_LUMA} bright, or the brightest entry if none is.
     * RGBA, 0 to 1, opaque. Pure.
     */
    static float[] lutColour(int[] lut8, boolean invert) {
        if (lut8.length == 0)
            return new float[]{.75f, .75f, .75f, 1};
        int last = lut8.length - 1;
        int start = (int) Math.round(LUT_PICK * last);
        int best = -1;
        double bestLuma = -1;
        for (int d = 0; d <= last && bestLuma < 0; d++) // the nearest entry either way that is bright enough
            for (int i : new int[]{start + d, start - d})
                if (i >= 0 && i <= last && luma(lut8[invert ? last - i : i]) >= MIN_LUMA) {
                    best = lut8[invert ? last - i : i];
                    bestLuma = luma(best);
                    break;
                }
        if (bestLuma < 0)
            for (int e : lut8)
                if (luma(e) > bestLuma) {
                    best = e;
                    bestLuma = luma(e);
                }
        return new float[]{((best >> 16) & 0xFF) / 255f, ((best >> 8) & 0xFF) / 255f, (best & 0xFF) / 255f, 1};
    }

    static double luma(int argb) {
        return (.2126 * ((argb >> 16) & 0xFF) + .7152 * ((argb >> 8) & 0xFF) + .0722 * (argb & 0xFF)) / 255;
    }

    /** A label's text width in font units: the A with a ring is as wide as an A. */
    static float measure(SdfTextRenderer renderer, String text) {
        return renderer.measureWidth(text.replace(ANGSTROM, 'A'));
    }

    // -- drawing --------------------------------------------------------------------------------

    private record Placed(String text, float[] colour, double x, double y) {}

    /** The labels for one viewport, if the pizza is on, labelled, cut in wedges and in a projection it can be drawn in. GL thread. */
    public static void render(MapView mv, Viewport vp) {
        if (!Pizza.isOn() || Pizza.getLabels() == Pizza.Labels.Off || Pizza.getShape() != Pizza.Shape.Wedges || !Pizza.supports(mv.mode()))
            return;

        SdfTextRenderer renderer = GLText.renderer();
        double textPx = TEXT_SIZE * Pizza.getLabelScale() * Display.pixelScale[1] * Display.captureScale;
        float scale = (float) (textPx / renderer.getFontSize());
        boolean screen = Pizza.getSpace() == Pizza.Space.Screen;
        boolean twoRings = Pizza.isTwoRings();

        List<Placed> placed = new ArrayList<>();
        for (int ring = Pizza.INNER; ring <= (twoRings ? Pizza.OUTER : Pizza.INNER); ring++) {
            List<Pizza.Slot> slots = Pizza.slots(ring);
            if (slots.isEmpty())
                continue;
            // Per slot, once: the text and colour of each layer.
            int n = slots.size();
            String[] texts = new String[n];
            float[][] colours = new float[n][];
            for (Pizza.Slot s : slots) {
                texts[s.index()] = text(s.layer());
                colours[s.index()] = colour(s.layer());
            }
            for (Cell c : cells(n, Pizza.getRepeats(ring), Pizza.getSeed(), Pizza.turnRadians(ring), Pizza.getLabels())) {
                ImageLayer layer = slots.get(c.slot()).layer();
                View.ImageData data = layer.getImageData();
                if (data == null || !layer.isVisible(vp.idx))
                    continue;
                Vec2 at = screen
                        ? screenAnchor(vp, ring, twoRings, c.angle(), textPx)
                        : sunAnchor(mv, vp, data.metaData(), ring, twoRings, c.angle(), textPx);
                if (at != null && Double.isFinite(at.x) && Double.isFinite(at.y))
                    placed.add(new Placed(texts[c.slot()], colours[c.slot()], at.x, at.y));
            }
        }
        if (placed.isEmpty())
            return;

        float em = renderer.getFontSize() * scale;
        renderer.beginRendering(vp.width, vp.height);
        renderer.setColor(GLText.SHADOW_COLOR); // shadows first, all in one draw, as TimestampLayer does
        for (Placed p : placed)
            draw(renderer, p.text(), (float) (p.x() - .5 * measure(renderer, p.text()) * scale) + GLText.SHADOW_OFFSET_X,
                    (float) (p.y() - HALF_CAP * em) + GLText.SHADOW_OFFSET_Y, scale);
        for (Placed p : placed) {
            renderer.setColor(p.colour());
            draw(renderer, p.text(), (float) (p.x() - .5 * measure(renderer, p.text()) * scale), (float) (p.y() - HALF_CAP * em), scale);
        }
        renderer.endRendering();
    }

    private static String text(ImageLayer layer) {
        View.ImageData data = layer.getImageData();
        String wavelength = null;
        if (data != null) {
            MetaData m = data.metaData();
            if (m instanceof FitsMetaData f)
                wavelength = wavelengthText(wavelengthAngstrom(f.getMeasurement(), f.getWaveUnit()), Pizza.isAngstrom());
        }
        return labelText(wavelength, layer.getName(), Pizza.isColourNames(), Pizza.getColourName(layer));
    }

    private static float[] colour(ImageLayer layer) {
        org.helioviewer.jhv.image.ImageDisplaySettings settings = layer.getDisplaySettings();
        return lutColour(settings.getLUT().lut8(), settings.getInvertLUT());
    }

    /** Draws text with the A with a ring composed from glyphs the atlas has. */
    private static void draw(SdfTextRenderer renderer, String text, float x, float y, float scale) {
        int i = text.indexOf(ANGSTROM);
        if (i < 0) {
            renderer.draw(text, x, y, 0, scale);
            return;
        }
        String before = text.substring(0, i);
        renderer.draw(before, x, y, 0, scale);
        float ax = x + renderer.measureWidth(before) * scale;
        renderer.draw("A", ax, y, 0, scale);
        float em = renderer.getFontSize() * scale;
        renderer.draw("°", ax + RING_DX * em, y + RING_DY * em, 0, scale * RING_SCALE);
        draw(renderer, text.substring(i + 1), ax + renderer.measureWidth("A") * scale, y, scale);
    }

    /** A wedge cut in viewport pixels (Space Screen) is labelled about the viewport's centre, as the shader cuts it. */
    private static Vec2 screenAnchor(Viewport vp, int ring, boolean twoRings, double angle, double textPx) {
        double half = .5 * Math.min(vp.width, vp.height);
        // The screen cut has no radii: both rings cover the viewport, so they are labelled at two radii.
        double split = twoRings ? .55 * half : half;
        double r = ring == Pizza.OUTER
                ? labelRadius(true, split, half, MARGIN_LINES * textPx)
                : labelRadius(false, 0, split, MARGIN_LINES * textPx);
        return new Vec2(.5 * vp.width + PolarBasis.x(r, angle), .5 * vp.height + PolarBasis.y(r, angle));
    }

    /** A ring's edges in solar radii, outer +infinity when it has none; spokes of uneven length are labelled inside the shortest. */
    private static double[] ringEdges(int ring, boolean twoRings) {
        double outer = Pizza.getOuterRadius();
        double jitter = Pizza.getJitter();
        double inner = 0;
        if (twoRings) {
            if (ring == Pizza.OUTER)
                inner = Pizza.getBoundary();
            else
                outer = Pizza.getBoundary();
        }
        return new double[]{inner, outer > 0 ? outer * (1 - jitter) : Double.POSITIVE_INFINITY};
    }

    /** A wedge cut in the Sun's frame (Space Sun): the label's anchor in viewport pixels, or null when the ring is out of sight. */
    @Nullable
    private static Vec2 sunAnchor(MapView mv, Viewport vp, MetaData meta, int ring, boolean twoRings, double angle, double textPx) {
        double width = mv.cameraWidth(vp);
        double margin = MARGIN_LINES * textPx * width / vp.height; // world units per pixel: Transform.ortho and ortho2D alike
        MapScale scale = mv.scale(vp);
        double[] edge = ringEdges(ring, twoRings);

        if (mv.isHelioradialUnrolled()) {
            // A wedge is a vertical strip of the page: position angle across, radius up. Label near its top.
            double visibleTop = Math.min(.5, -mv.cameraTranslationY() + .5 * width);
            double bottom = edge[0] > 0 ? scale.toUnitY(edge[0]) - .5 : -.5;
            double top = Math.min(Double.isFinite(edge[1]) ? scale.toUnitY(edge[1]) - .5 : .5, visibleTop);
            if (top <= bottom)
                return null;
            double x = vp.aspect * (scale.toUnitX(Math.toDegrees(angle)) - .5);
            return project(vp, null, x, labelRadius(ring == Pizza.OUTER, bottom, top, margin), 0);
        }

        double visible = FIELD * .5 * width * Math.min(1, vp.aspect);
        double distance = meta.getViewpoint().distance;
        // World units per solar radius near the centre of an HPC page, which is linear in angle.
        double hpcK = vp.aspect * (scale.toUnitX(Math.toDegrees(Math.atan2(1, distance))) - scale.toUnitX(0));
        double inner = toWorld(mv, scale, hpcK, edge[0]);
        double outer = Math.min(Double.isFinite(edge[1]) ? toWorld(mv, scale, hpcK, edge[1]) : Double.POSITIVE_INFINITY, visible);
        if (!(outer > inner))
            return null;
        double r = labelRadius(ring == Pizza.OUTER, inner, outer, margin);

        if (mv.isHpc()) {
            // The shader's hpcXY is the plane through the Sun's centre, seen from this image's
            // observer (imageCommon.frag, helioprojectiveToHpcXY): back to the page's angles.
            double rs = r / hpcK;
            double x = PolarBasis.x(rs, angle), y = PolarBasis.y(rs, angle);
            double lon = Math.atan2(x, distance);
            double lat = Math.atan2(y, Math.hypot(x, distance));
            return project(vp, null, vp.aspect * (scale.toUnitX(Math.toDegrees(lon)) - .5), scale.toUnitY(Math.toDegrees(lat)) - .5, 0);
        }
        double x = PolarBasis.x(r, angle), y = PolarBasis.y(r, angle);
        if (mv.isHelioradial()) // the observer's frame, as ImageLayer draws the warped surface and GridLayer its rings
            return project(vp, mv.viewpoint().toQuat(), x, y, 0);
        // Orthographic: the image's own frame (ImageLayer, sourceView), on the sphere's near side when on the disk.
        Quat frame = meta.getWcsHeader().projection.isSurfaceMap() ? mv.viewRotation() : meta.getViewpoint().toQuat();
        return project(vp, frame, x, y, r < 1 ? Math.sqrt(1 - r * r) : 0);
    }

    /** A heliocentric distance in solar radii as a radius in the scene's world units. */
    private static double toWorld(MapView mv, MapScale scale, double hpcK, double r) {
        if (r <= 0)
            return 0;
        if (mv.isHelioradial()) // the page radius the imagery lands at (as HelioradialGrid's ringRho): measured, not warpRadius
            return .5 * scale.toUnitY(r);
        if (mv.isHpc())
            return r * hpcK;
        return r;
    }

    /** A world point, in {@code frame} when given, to viewport pixels through the scene's matrices. */
    private static Vec2 project(Viewport vp, @Nullable Quat frame, double x, double y, double z) {
        Vector3f ndc = new Vector3f();
        if (frame != null) {
            Transform.pushView();
            Transform.rotateViewInverse(frame);
        }
        try {
            Transform.toNdc((float) x, (float) y, (float) z, ndc);
        } finally {
            if (frame != null)
                Transform.popView();
        }
        return new Vec2((ndc.x + 1) * .5 * vp.width, (ndc.y + 1) * .5 * vp.height);
    }
}
