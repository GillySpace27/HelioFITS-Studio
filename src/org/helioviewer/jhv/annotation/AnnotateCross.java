package org.helioviewer.jhv.annotation;

import java.util.List;

import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.math.SphericalCoords;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.FOVShape;

import org.json.JSONObject;

final class AnnotateCross extends AbstractAnnotateable {

    private static final int SUBDIVISIONS = 2;
    private static final List<Vec3> crossVertices = fixedSizeVertices(SUBDIVISIONS + 1);

    AnnotateCross(JSONObject jo) {
        super(jo);
    }

    static void drawCross(MapView mv, Viewport vp, double longitude, double latitude, byte[] color, BufVertex vexBuf) {
        double delta = 2.5 * Math.PI / 180;
        interpolatedDraw(mv, vp, longitude + delta, latitude, longitude - delta, latitude, color, vexBuf);
        interpolatedDraw(mv, vp, longitude, latitude + delta, longitude, latitude - delta, color, vexBuf);
    }

    private static void interpolatedDraw(MapView mv, Viewport vp, double longitude1, double latitude1, double longitude2, double latitude2, byte[] color, BufVertex vexBuf) {
        for (int i = 0; i <= SUBDIVISIONS; i++) {
            Vec3 pc = interpolateSpherical(i / (double) SUBDIVISIONS, longitude1, latitude1, longitude2, latitude2);
            crossVertices.set(i, pc);
        }
        mv.emitMapLine(vp, crossVertices, ANNOTATION_RADIUS, color, vexBuf);
    }

    @Override
    public void draw(MapView mv, Viewport vp, BufVertex vexBuf) {
        if (sky || startPoint == null)
            return;

        byte[] color = color(false);
        drawCross(mv, vp, SphericalCoords.longitude(startPoint), SphericalCoords.latitude(startPoint), color, vexBuf);
    }

    @Override
    public void mousePressed(Viewport vp, int x, int y) {
        Vec3 pt = surfaceOrSky(vp, x, y);
        if (pt != null)
            startPoint = pt;
    }

    // Off the disk: a cross in the plane of the sky. Its arms grow with the distance from the
    // Sun (judgement), so it is still visible in a field tens of solar radii wide.
    @Override
    public void drawTransformed(MapView mv, double lineThickness, BufVertex lineBuf, BufVertex centerBuf) {
        if (!sky || startPoint == null)
            return;

        byte[] color = color(false);
        boolean flat = mv.isHpc();
        double x = startPoint.x, y = startPoint.y;
        double arm = 0.04 * Math.max(1, Math.hypot(x, y));
        skyArm(x - arm, y, x + arm, y, flat, lineThickness, color, lineBuf);
        skyArm(x, y - arm, x, y + arm, flat, lineThickness, color, lineBuf);
    }

    private static void skyArm(double x0, double y0, double x1, double y1, boolean flat, double thickness, byte[] color, BufVertex buf) {
        buf.startLine((float) x0, (float) y0, (float) FOVShape.computeZ(x0, y0, flat, thickness), 1, color);
        buf.putVertex((float) x1, (float) y1, (float) FOVShape.computeZ(x1, y1, flat, thickness), 1, color);
        buf.endLine();
    }

    @Override
    public void mouseDragged(Viewport vp, int x, int y) {}

    @Override
    public void mouseReleased() {}

    @Override
    public boolean beingDragged() {
        return startPoint != null;
    }

    @Override
    public boolean isDraggable() {
        return false;
    }

}
