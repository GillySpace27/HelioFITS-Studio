package org.helioviewer.jhv.opengl;

import org.helioviewer.jhv.layers.grid.ViewpointMarker;
import org.helioviewer.jhv.math.Quat;
import org.joml.Vector3f;

/**
 * The camera and eye glyphs face the screen whatever the view rotation: seen through the scene's own
 * matrices every quad corner lands on a grid of whole half-cells around the observer's pixel, so
 * the glyph is a flat, undistorted picture 11 cells wide.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.opengl.ViewpointGlyphCheck
 */
public final class ViewpointGlyphCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        int w = 800, h = 600;
        double cell = 4, distance = 215;
        Quat[] views = {Quat.createXY(0, 0), Quat.createXY(0.4, 1.1), Quat.createXY(-1.2, 2.5)};
        for (ViewpointMarker.Style style : new ViewpointMarker.Style[]{ViewpointMarker.Style.CAMERA, ViewpointMarker.Style.EYE}) {
            for (Quat q : views) {
                Transform.ortho(w / (double) h, 600, 3, -2, q);
                BufVertex buf = new BufVertex(ViewpointMarker.MAX_VERTICES);
                boolean built = ViewpointMarker.build(buf, style, distance, cell, w, h, new byte[]{1, 2, 3, 4});
                expect(style + ": glyph built", built && buf.getCount() > 0 && buf.getCount() % 6 == 0);
                if (!built)
                    continue;
                Vector3f c = Transform.toNdc(0, 0, (float) distance, new Vector3f());
                double cx = (c.x + 1) * .5 * w, cy = (c.y + 1) * .5 * h;
                java.nio.ByteBuffer bb = buf.toBuffer().order(java.nio.ByteOrder.nativeOrder());
                int stride = BufVertex.BYTES_PER_VERTEX;
                double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
                boolean onGrid = true;
                for (int i = 0; i < buf.getCount(); i++) {
                    float x = bb.getFloat(i * stride), y = bb.getFloat(i * stride + 4), z = bb.getFloat(i * stride + 8);
                    Vector3f n = Transform.toNdc(x, y, z, new Vector3f());
                    double px = (n.x + 1) * .5 * w - cx, py = (n.y + 1) * .5 * h - cy;
                    onGrid &= Math.abs(px / (cell / 2) - Math.rint(px / (cell / 2))) < 0.05
                            && Math.abs(py / (cell / 2) - Math.rint(py / (cell / 2))) < 0.05;
                    minX = Math.min(minX, px);
                    maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py);
                    maxY = Math.max(maxY, py);
                }
                expect(style + ": every corner on the half-cell grid under view " + q, onGrid);
                expect(style + ": 11 cells wide on screen (" + (maxX - minX) + " px)", Math.abs(maxX - minX - 11 * cell) < 0.2);
                expect(style + ": centred on the observer's pixel", Math.abs(minX + maxX) < 0.2);
            }
        }
        System.out.println(failures == 0 ? "ViewpointGlyphCheck: PASS" : "ViewpointGlyphCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private ViewpointGlyphCheck() {}
}
