package org.helioviewer.jhv.layers.grid;

import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.SkyProjection;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.layers.GridLayer;
import org.helioviewer.jhv.math.FastFormat;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GLSLLine;
import org.helioviewer.jhv.opengl.GLText;
import org.helioviewer.jhv.opengl.text.SdfTextRenderer;

/**
 * The native grid of a zenithal projection: rings of constant angular distance from where the view
 * is aimed, and spokes of constant azimuth about it.
 *
 * <p>This is the grid the projection is actually built on, which is why it replaces the rectangular
 * axis grid the other flat modes get. A cartesian ruling of the projection plane is drawable but
 * says nothing: its coordinate is the native radius R, a number nobody reads, and its lines are not
 * curves of anything. Rings and spokes are the coordinates the map preserves, so each ring is a
 * real locus, every point on it the same angle from the centre of the field.
 *
 * <p>It also makes the difference between the three styles legible instead of merely visible. All
 * of them draw a ring of constant angle as a perfect circle, since that is what makes a projection
 * zenithal; what changes is the SPACING of those circles. Evenly spaced is azimuthal equidistant
 * and nothing else, so the grid states which projection is running without anyone reading the menu.
 */
public final class SkyGrid {

    private static final int TEXT_SIZE = 12;
    private static final int SUBDIVISIONS = 180;
    private static final int MAX_RINGS = 16;
    // In normalized page units, where the field edge is 0.5. Below this the rings stop being
    // separable and start being a wash, which is what happens near the edge of a gnomonic view.
    private static final double MIN_RING_SPACING = 0.035;
    private static final double[] RING_FACTORS = {1, 2, 5};

    private final GLSLLine line = new GLSLLine(false);
    private final double[] rings = new double[MAX_RINGS]; // degrees of angular distance
    private final double[] ringRadii = new double[MAX_RINGS]; // the same, as page radii

    public void init() {
        line.init();
    }

    public void dispose() {
        line.dispose();
    }

    public void render(MapView mv, Viewport vp, boolean showLabels, double spokeStep, byte[] color,
                       double lineScale, float[] labelColor, double labelSize, double labelAngle) {
        MapScale scale = mv.scale(vp);
        int ringCount = chooseRings(scale, Display.getSkyProjection());
        updateLine(ringCount, spokeStep, color);
        line.renderLine(vp, GridMath.LINEWIDTH * lineScale);
        if (showLabels)
            drawLabels(mv, vp, ringCount, labelColor, labelSize, labelAngle);
    }

    /**
     * A 1-2-5 ladder in degrees of angular distance, out to the field the user set.
     *
     * <p>Chosen in ANGLE and then mapped, not chosen in page radius. That ordering is the point: a
     * ring has to be able to be labelled "10 degrees from the centre" and be exactly that in every
     * projection, so the ladder lives in the quantity the labels name and the projection decides
     * where each rung lands.
     */
    private int chooseRings(MapScale scale, SkyProjection projection) {
        double field = Display.getSkyFieldDegrees();
        int count = 0;
        double lastRadius = -1;
        double decade = 0.01;
        while (decade <= field && count < MAX_RINGS) {
            for (double factor : RING_FACTORS) {
                double degrees = factor * decade;
                if (degrees > field || count == MAX_RINGS)
                    continue;
                double radius = pageRadius(scale, projection, degrees);
                if (radius - lastRadius < MIN_RING_SPACING)
                    continue;
                rings[count] = degrees;
                ringRadii[count] = radius;
                count++;
                lastRadius = radius;
            }
            decade *= 10;
        }
        return count;
    }

    /**
     * Where a given angular distance lands on the page, in the normalized units the flat modes
     * draw in: 0 at the centre, 0.5 at the top edge.
     *
     * <p>Through the scale rather than by dividing by the field, so the ring and the imagery are
     * placed by one mapping. That is the same discipline the world-space grids follow, and for the
     * same reason: two ways of computing the same position is how a grid ends up beside the picture
     * it annotates instead of on it.
     */
    private static double pageRadius(MapScale scale, SkyProjection projection, double degrees) {
        double native_ = Math.toDegrees(projection.radiusFromAngle(Math.toRadians(degrees)));
        return scale.toUnitY(native_) - 0.5;
    }

    private void updateLine(int ringCount, double spokeStep, byte[] color) {
        int spokes = (int) Math.round(360 / spokeStep);
        int noPoints = ringCount * (SUBDIVISIONS + 3) + 4 * spokes;
        BufVertex vexBuf = new BufVertex(noPoints * GLSLLine.stride);

        for (int i = 0; i < ringCount; i++) {
            float radius = (float) ringRadii[i];
            for (int j = 0; j <= SUBDIVISIONS; j++) {
                double a = 2 * Math.PI * j / SUBDIVISIONS;
                float x = (float) (radius * Math.cos(a));
                float y = (float) (radius * Math.sin(a));
                if (j == 0)
                    vexBuf.putVertex(x, y, 0, 1, Colors.Null);
                vexBuf.putVertex(x, y, 0, 1, color);
                if (j == SUBDIVISIONS)
                    vexBuf.putVertex(x, y, 0, 1, Colors.Null);
            }
        }

        // Spokes start at the innermost ring, not at the centre. Aimed at the Sun the centre is
        // the disk, and a dozen lines converging on it would bury the one part of the picture with
        // detail in it. The same choice HelioradialGrid makes, for the same reason.
        float inner = (float) (ringCount > 0 ? ringRadii[0] : 0);
        float outer = 0.5f;
        for (int s = 0; s < spokes; s++) {
            double a = Math.toRadians(s * spokeStep);
            double sin = Math.sin(a), cos = Math.cos(a);
            vexBuf.putVertex((float) (-inner * sin), (float) (inner * cos), 0, 1, Colors.Null);
            vexBuf.repeatVertex(color);
            vexBuf.putVertex((float) (-outer * sin), (float) (outer * cos), 0, 1, color);
            vexBuf.repeatVertex(Colors.Null);
        }
        line.setVertex(vexBuf);
    }

    private void drawLabels(MapView mv, Viewport vp, int ringCount, float[] color, double labelSize, double labelAngle) {
        SdfTextRenderer renderer = GLText.renderer();
        double width = mv.cameraWidth(vp);
        double worldTextHeight = TEXT_SIZE * labelSize / GridLayer.GRID_LABEL_SIZE_REF
                * Display.pixelScale[1] * Math.min(width, 1) / vp.height;
        float textScaleFactor = (float) (worldTextHeight / renderer.getFontSize());
        float labelOffset = (float) (0.1 * worldTextHeight);
        double angle = Math.toRadians(labelAngle);
        double sin = Math.sin(angle), cos = Math.cos(angle);

        renderer.setColor(color);
        renderer.begin3DRendering();
        for (int i = 0; i < ringCount; i++) {
            double radius = ringRadii[i];
            renderer.draw(label(rings[i]),
                    (float) (sin * radius + labelOffset), (float) (cos * radius + labelOffset), 0, textScaleFactor);
        }
        renderer.end3DRendering();
    }

    // Degrees of angular distance from the centre of the field. The ladder is 1-2-5, so a whole
    // number of degrees needs no decimals and the sub-degree rungs need at most two.
    private static String label(double degrees) {
        return (degrees >= 1 ? FastFormat.rounded1(degrees) : FastFormat.rounded2(degrees)) + "\u00b0";
    }

}
