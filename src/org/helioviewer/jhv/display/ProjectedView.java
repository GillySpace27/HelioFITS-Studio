package org.helioviewer.jhv.display;

import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.math.MathUtils;
import org.helioviewer.jhv.math.PolarBasis;
import org.helioviewer.jhv.math.SphericalCoords;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.wcs.WcsProjection;

final class ProjectedView extends MapView {

    ProjectedView(Camera _camera, Position _viewpoint, GridType _gridType, MapMode _mode, MapScale[] _scales) {
        super(_camera, _viewpoint, _mode, _gridType, _scales);
    }

    @Override
    public Vec3 mouseToSurface(Viewport vp, int x, int y) {
        return unproject(mouseToMap(vp, x, y));
    }

    @Override
    public Vec2 mouseToScreen(Viewport vp, int x, int y) {
        double width = cameraWidth(vp);
        return new Vec2(
                ViewportMath.computeUpX(vp, width, camera.getTranslationX(), x),
                ViewportMath.computeUpY(vp, width, camera.getTranslationY(), y));
    }

    // Far enough outside the [-0.5, 0.5] map to clip under any zoom anyone would use.
    private static final Vec2 OFF_PAGE = new Vec2(1e4, 1e4);

    private Vec2 project(MapScale scale, Vec3 v) {
        return switch (mode) {
            case HPC -> projectHpc(v, scale);
            case Latitudinal -> projectLatitudinal(scale, v);
            case Helioradial -> projectHelioradial(scale, v);
            case HelioradialUnrolled -> projectHelioradialUnrolled(scale, v);
            // Off the page rather than null, because this returns a coordinate. A point the sky
            // projection cannot draw is one that is behind you or past its reach, and pushing it
            // far outside the map is the truthful answer for a single placed marker: it clips.
            // Lines do NOT come through here; they take the nullable path below and break instead.
            case ObserverSky -> {
                Vec2 pt = projectSky(scale, v);
                yield pt == null ? OFF_PAGE : pt;
            }
            case Orthographic -> throw new IllegalArgumentException("Orthographic mode is not projected");
        };
    }

    private Vec3 unproject(Vec2 pt) {
        return switch (mode) {
            case HPC -> unprojectHpc(pt.x, pt.y);
            case Latitudinal -> unprojectLatitudinal(pt.x, pt.y);
            case Helioradial, HelioradialUnrolled -> unprojectHelioradial(pt.x, pt.y);
            case ObserverSky -> SkyMap.unproject(viewpoint, Display.getSkyProjection(),
                    Display.getSkyLookLon(), Display.getSkyLookLat(), pt);
            case Orthographic -> throw new IllegalArgumentException("Orthographic mode is not projected");
        };
    }

    // See docs/non-ortho-projection-note.md for the shared Java/GLSL convention.
    private Vec2 projectLatitudinal(MapScale scale, Vec3 v) {
        double longitude = MathUtils.mapToMinus180To180(
                Math.toDegrees(SphericalCoords.longitude(v) + latiLongitudeOrigin()));
        double latitude = Math.toDegrees(SphericalCoords.latitude(v) - latiLatitudeOrigin());
        return new Vec2(scale.toUnitX(longitude) - 0.5, scale.toUnitY(latitude) - 0.5);
    }

    private Vec3 unprojectLatitudinal(double longitudeDeg, double latitudeDeg) {
        double longitude = Math.toRadians(longitudeDeg) - latiLongitudeOrigin();
        double latitude = Math.toRadians(latitudeDeg) + latiLatitudeOrigin();
        return latitude < -Math.PI / 2 || latitude > Math.PI / 2
                ? null
                : SphericalCoords.unit(longitude, latitude);
    }

    private Vec3 unprojectHelioradial(double angleDeg, double radius) {
        double theta = Math.toRadians(angleDeg);
        double x = PolarBasis.x(radius, theta);
        double y = PolarBasis.y(radius, theta);
        return WcsProjection.helioprojectiveToWorld(
                viewpoint,
                Math.atan2(x, viewpoint.distance),
                Math.atan2(y, Math.sqrt(x * x + viewpoint.distance * viewpoint.distance)));
    }

    private Vec2 projectHelioradial(MapScale scale, Vec3 v0) {
        Vec2 hpcXY = projectToHpcPlane(v0);
        double r = Math.hypot(hpcXY.x, hpcXY.y);
        if (r == 0)
            return new Vec2(0, 0);
        double t = Math.max(0, scale.toUnitY(r));
        double f = .5 * t / r;
        return new Vec2(f * hpcXY.x, f * hpcXY.y);
    }

    private Vec2 projectHelioradialUnrolled(MapScale scale, Vec3 v0) {
        Vec2 hpcXY = projectToHpcPlane(v0);
        double r = Math.hypot(hpcXY.x, hpcXY.y);
        double theta = PolarBasis.angle(hpcXY.x, hpcXY.y);
        return new Vec2(scale.toUnitX(Math.toDegrees(theta)) - 0.5, scale.toUnitY(r) - 0.5);
    }

    @Nullable
    private Vec2 projectSky(MapScale scale, Vec3 v) {
        return SkyMap.project(viewpoint, scale, Display.getSkyProjection(),
                Display.getSkyLookLon(), Display.getSkyLookLat(), v);
    }

    private Vec2 projectToHpcPlane(Vec3 v0) {
        Vec3 v = toHpcViewpointSpace(v0);
        double fovScale = viewpoint.distance / (viewpoint.distance - v.z);
        return new Vec2(fovScale * v.x, fovScale * v.y);
    }

    private Vec2 projectHpc(Vec3 v, MapScale scale) {
        // External solar points arrive in world space; HPC projection is defined in viewpoint space.
        return projectHpcViewpointSpace(toHpcViewpointSpace(v), viewpoint.distance, scale);
    }

    private Vec3 unprojectHpc(double longitudeDeg, double latitudeDeg) {
        return WcsProjection.helioprojectiveToWorld(viewpoint, Math.toRadians(longitudeDeg), Math.toRadians(latitudeDeg));
    }

    @Override
    public Vec2 projectToScreen(Viewport vp, Vec3 v) {
        MapScale scale = scale(vp);
        Vec2 pt = project(scale, v);
        return mode == MapMode.Helioradial ? pt : new Vec2(pt.x * vp.aspect, pt.y);
    }

    @Override
    public void emitMapLine(Viewport vp, List<Vec3> vertices, double radius, byte[] color, BufVertex vexBuf) {
        MapScale scale = scale(vp);
        if (vertices.isEmpty())
            return;
        if (mode == MapMode.HPC) {
            emitHpcLine(scale, vp, vertices, color, vexBuf);
            return;
        }
        if (mode == MapMode.Helioradial) {
            emitHelioradialLine(scale, vertices, color, vexBuf);
            return;
        }
        if (mode == MapMode.ObserverSky) {
            emitSkyLine(scale, vp, vertices, color, vexBuf);
            return;
        }

        Vec2 current = project(scale, vertices.getFirst());
        startProjectedLine(vp, current, color, vexBuf);
        for (int i = 1; i < vertices.size(); i++) {
            Vec2 previous = current;
            current = project(scale, vertices.get(i));
            emitWrappedVertex(vp, previous, current, color, vexBuf);
        }
        vexBuf.endLine();
    }

    private void emitHelioradialLine(MapScale scale, List<Vec3> vertices, byte[] color, BufVertex vexBuf) {
        Vec2 current = projectHelioradial(scale, vertices.getFirst());
        vexBuf.startLine((float) current.x, (float) current.y, 0, 1, color);
        for (int i = 1; i < vertices.size(); i++) {
            current = projectHelioradial(scale, vertices.get(i));
            vexBuf.putVertex((float) current.x, (float) current.y, 0, 1, color);
        }
        vexBuf.endLine();
    }

    /**
     * A line on the observer's sky, broken wherever the projection cannot carry it.
     *
     * <p>Same shape as the HPC path, and needed for the same reason: this is a map of directions,
     * so a curve that leaves the drawable part of the sky has genuinely left the picture. Joining
     * across the gap would draw a chord through the middle of the field that no part of the curve
     * ever passed through.
     */
    private void emitSkyLine(MapScale scale, Viewport vp, List<Vec3> vertices, byte[] color, BufVertex vexBuf) {
        boolean lineOpen = false;
        for (Vec3 vertex : vertices) {
            Vec2 current = projectSky(scale, vertex);
            if (current == null) {
                if (lineOpen)
                    vexBuf.endLine();
                lineOpen = false;
                continue;
            }

            if (lineOpen)
                emitProjectedVertex(vp, current, color, vexBuf);
            else
                startProjectedLine(vp, current, color, vexBuf);
            lineOpen = true;
        }
        if (lineOpen)
            vexBuf.endLine();
    }

    private void emitHpcLine(MapScale scale, Viewport vp, List<Vec3> vertices, byte[] color, BufVertex vexBuf) {
        // HPC is a visible-hemisphere map, so hidden segments must terminate the strip.
        boolean lineOpen = false;
        for (Vec3 vertex : vertices) {
            Vec2 current = projectVisibleHpcSurfacePoint(vertex, scale);
            if (current == null) {
                if (lineOpen)
                    vexBuf.endLine();
                lineOpen = false;
                continue;
            }

            if (lineOpen)
                emitProjectedVertex(vp, current, color, vexBuf);
            else
                startProjectedLine(vp, current, color, vexBuf);
            lineOpen = true;
        }
        if (lineOpen)
            vexBuf.endLine();
    }

    @Override
    public void emitMapPoints(Viewport vp, List<Vec3> vertices, double size, double radius, byte[] color, BufVertex vexBuf) {
        MapScale scale = scale(vp);
        if (mode == MapMode.HPC) {
            emitHpcPoints(scale, vp, vertices, size, color, vexBuf);
            return;
        }

        float pointSize = (float) size;
        if (mode == MapMode.ObserverSky) {
            for (Vec3 vertex : vertices) {
                Vec2 pt = projectSky(scale, vertex);
                if (pt != null)
                    vexBuf.putVertex((float) (pt.x * vp.aspect), (float) pt.y, 0, pointSize, color);
            }
            return;
        }
        if (mode == MapMode.Helioradial) {
            for (Vec3 vertex : vertices) {
                Vec2 pt = projectHelioradial(scale, vertex);
                vexBuf.putVertex((float) pt.x, (float) pt.y, 0, pointSize, color);
            }
            return;
        }
        for (Vec3 vertex : vertices) {
            Vec2 pt = project(scale, vertex);
            vexBuf.putVertex((float) (pt.x * vp.aspect), (float) pt.y, 0, pointSize, color);
        }
    }

    private void emitHpcPoints(MapScale scale, Viewport vp, List<Vec3> vertices, double size, byte[] color, BufVertex vexBuf) {
        // Skip back-side surface points in HPC instead of projecting them through the map.
        float pointSize = (float) size;
        for (Vec3 vertex : vertices) {
            Vec2 pt = projectVisibleHpcSurfacePoint(vertex, scale);
            if (pt != null)
                vexBuf.putVertex((float) (pt.x * vp.aspect), (float) pt.y, 0, pointSize, color);
        }
    }

    @Override
    public Vec2 mouseToMap(Viewport vp, int x, int y) {
        double width = cameraWidth(vp);
        MapScale scale = scale(vp);
        if (mode == MapMode.Helioradial)
            return mouseToHelioradialMap(width, vp, scale, x, y);
        return new Vec2(
                scale.toMapX(ViewportMath.computeUpX(vp, width, camera.getTranslationX(), x) / vp.aspect + 0.5),
                scale.toMapY(ViewportMath.computeUpY(vp, width, camera.getTranslationY(), y) + 0.5));
    }

    private Vec2 mouseToHelioradialMap(double width, Viewport vp, MapScale scale, int x, int y) {
        double upX = ViewportMath.computeUpX(vp, width, camera.getTranslationX(), x);
        double upY = ViewportMath.computeUpY(vp, width, camera.getTranslationY(), y);
        double t = 2 * Math.hypot(upX, upY);
        return new Vec2(Math.toDegrees(PolarBasis.angle(upX, upY)), scale.toMapY(t));
    }

    private Vec3 toHpcViewpointSpace(Vec3 v) {
        return viewpoint.toQuat().rotateVector(v);
    }

    private static Vec2 projectHpcViewpointSpace(Vec3 view, double observerDistance, MapScale scale) {
        double zeta = observerDistance - view.z;
        double longitude = Math.atan2(view.x, zeta);
        double latitude = Math.atan2(view.y, Math.sqrt(view.x * view.x + zeta * zeta));
        return new Vec2(
                scale.toUnitX(Math.toDegrees(longitude)) - 0.5,
                scale.toUnitY(Math.toDegrees(latitude)) - 0.5);
    }

    private Vec2 projectVisibleHpcSurfacePoint(Vec3 vertex, MapScale scale) {
        Vec3 view = toHpcViewpointSpace(vertex);
        if (view.z < 0)
            return null;
        return projectHpcViewpointSpace(view, viewpoint.distance, scale);
    }

    private static void emitWrappedVertex(Viewport vp, Vec2 previous, Vec2 current, byte[] color, BufVertex vexBuf) {
        if (Math.abs(previous.x - current.x) > 0.5)
            emitHorizontalWrap(vp, current, previous, color, vexBuf);
        emitProjectedVertex(vp, current, color, vexBuf);
    }

    private static void emitHorizontalWrap(Viewport vp, Vec2 current, Vec2 previous, byte[] color, BufVertex vexBuf) {
        double edge;
        if (current.x <= 0 && previous.x >= 0)
            edge = 0.5;
        else if (current.x >= 0 && previous.x <= 0)
            edge = -0.5;
        else
            return;

        double dx = current.x + 2 * edge - previous.x;
        // Opposite seam endpoints have the same unwrapped X coordinate.
        float y = (float) (dx == 0 ? current.y : previous.y + (edge - previous.x) / dx * (current.y - previous.y));
        float x = (float) (edge * vp.aspect);
        vexBuf.putVertex(x, y, 0, 1, color);
        vexBuf.endLine();
        vexBuf.startLine(-x, y, 0, 1, color);
    }

    private static void startProjectedLine(Viewport vp, Vec2 projected, byte[] color, BufVertex vexBuf) {
        vexBuf.startLine((float) (projected.x * vp.aspect), (float) projected.y, 0, 1, color);
    }

    private static void emitProjectedVertex(Viewport vp, Vec2 projected, byte[] color, BufVertex vexBuf) {
        vexBuf.putVertex((float) (projected.x * vp.aspect), (float) projected.y, 0, 1, color);
    }

}
