package org.helioviewer.jhv.plugins.swek;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.swing.ImageIcon;

import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.CMETracker;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.event.JHVEvent;
import org.helioviewer.jhv.event.JHVEventCache;
import org.helioviewer.jhv.event.JHVEventListener;
import org.helioviewer.jhv.event.JHVPositionInformation;
import org.helioviewer.jhv.event.JHVRelatedEvents;
import org.helioviewer.jhv.event.SWEKGroup;
import org.helioviewer.jhv.image.nio.NativeImageFactory;
import org.helioviewer.jhv.layers.ImageLayers;
import org.helioviewer.jhv.layers.AbstractLayer;
import org.helioviewer.jhv.math.MathUtils;
import org.helioviewer.jhv.math.PolarBasis;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.opengl.BufCoord;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.opengl.GLSLLine;
import org.helioviewer.jhv.opengl.GLSLTexture;
import org.helioviewer.jhv.opengl.GLText;
import org.helioviewer.jhv.opengl.GLTexture;
import org.helioviewer.jhv.time.TimeListener;

import org.json.JSONObject;

// has to be public for state
public final class SWEKLayer extends AbstractLayer implements JHVEventListener.Handle, TimeListener.Range {
    private record CactusArcParams(double angularWidthDegree, double principalAngleDegree, double distSun) {}

    private static final int DIVPOINTS = 10;
    private static final double LINEWIDTH = GLSLLine.LINEWIDTH_BASIC;
    private static final double LINEWIDTH_HIGHLIGHT = 4 * LINEWIDTH; // selection has to read at a glance
    private static final double POLYGON_RADIUS = Sun.Radius * 1.01;
    private static final double DIST_SUN_BEGIN = 2.4;
    private static final long CACTUS_MAX_TRAVEL_MS = 14L * 24 * 3600 * 1000; // lookback for propagated fronts (covers slow CMEs)

    private static final byte[] TRACK_FRONT = Colors.bytes(255, 140, 0);  // orange dot at the tracked CME's calculated front
    private static final byte[] TRACK_FREEZE = Colors.bytes(170, 60, 230); // purple circle at the freeze location (SCREEN_FRACTION)

    private static final HashMap<String, GLTexture> iconCacheId = new HashMap<>();
    private static final double ICON_ALPHA = 0.7;
    private static final double ICON_SIZE = 0.1;
    private static final double ICON_SIZE_HIGHLIGHTED = 0.16;

    private static final float[][] texCoord = {{0, 1}, {1, 1}, {0, 0}, {1, 0}};

    private static final double EXTEND_DIST_MIN = 2;
    private static final double EXTEND_DIST_MAX = 200;
    private static final double EXTEND_DIST_FALLBACK = 60; // only until something is loaded

    private SWEKContext swekContext;
    private boolean icons = true;
    private boolean extendCactus = true; // propagate CACTus fronts past the LASCO catalog end
    // R☉ to propagate fronts out to. 0 = auto, meaning follow the loaded field of view so an
    // extended front runs out at the edge of the data instead of at an arbitrary fixed radius.
    private double extendDistance = 0;

    private final GLSLLine lineEvent = new GLSLLine(true);
    private final BufVertex bufEvent = new BufVertex(512 * GLSLLine.stride); // pre-allocate
    private final GLSLLine lineThick = new GLSLLine(true);
    private final BufVertex bufThick = new BufVertex(64 * GLSLLine.stride); // pre-allocate

    private final GLSLTexture glslTexture = new GLSLTexture();
    private final BufCoord texBuf = new BufCoord(4 * 8);

    private long cachedEventsTime = Long.MIN_VALUE;
    private long cachedEventsStart = Long.MIN_VALUE;
    private long cachedEventsEnd = Long.MIN_VALUE;
    private List<JHVRelatedEvents> cachedActiveEvents = List.of();

    private long cachedPropTime = Long.MIN_VALUE;
    private long cachedPropStart, cachedPropEnd;
    private double cachedPropFov = Double.NaN;
    private List<JHVRelatedEvents> cachedProp = List.of();

    public SWEKLayer(JSONObject jo) {
        if (jo != null) {
            icons = jo.optBoolean("icons", icons);
            extendCactus = jo.optBoolean("extendCactus", extendCactus);
            extendDistance = jo.optDouble("extendDistance", extendDistance);
            if (extendDistance > 0) // 0 stays "auto"; anything else is the user's explicit reach
                extendDistance = Math.clamp(extendDistance, EXTEND_DIST_MIN, EXTEND_DIST_MAX);
            SWEKPlugin.restoreLayer(this);
        }
    }

    void setContext(SWEKContext _swekContext) {
        swekContext = _swekContext;
    }

    @Override
    public void serialize(JSONObject jo) {
        jo.put("icons", icons);
        jo.put("extendCactus", extendCactus);
        jo.put("extendDistance", extendDistance);
    }

    private static void bindTexture(SWEKGroup group) {
        String key = group.getName();
        GLTexture tex = iconCacheId.get(key);
        if (tex == null) {
            ImageIcon icon = SWEKIconBank.getIcon(group.getIconKey());
            BufferedImage bi = NativeImageFactory.createRGBAPremultipliedImage(icon.getIconWidth(), icon.getIconHeight());
            try {
                Graphics g = bi.createGraphics();
                try {
                    icon.paintIcon(null, g, 0, 0);
                } finally {
                    g.dispose();
                }

                tex = new GLTexture(GL.TEXTURE_2D, GLTexture.Unit.THREE);
                tex.bind();

                ByteBuffer data = NativeImageFactory.getByteBuffer(bi);
                GLTexture.copyByteImage(bi.getWidth(), bi.getHeight(), GL.LINEAR, data);
            } finally {
                NativeImageFactory.free(bi);
            }
            iconCacheId.put(key, tex);
        }
        tex.bind();
    }

    private static void drawInterpolated(int mres, double r_start, double r_end, double t_start, double t_end, Quat q, byte[] color, BufVertex vexBuf) {
        int steps = Math.max(1, mres);
        for (int i = 0; i <= steps; i++) {
            double alpha = 1. - i / (double) steps;
            double r = alpha * r_start + (1 - alpha) * r_end;
            double theta = alpha * t_start + (1 - alpha) * t_end;

            Vec3 res = q.rotateInverseVector(PolarBasis.vec3(r, theta));

            if (i == 0) {
                vexBuf.putVertex(res, Colors.Null);
            }
            vexBuf.putVertex(res, color);
        }
        vexBuf.repeatVertex(Colors.Null);
    }

    private static CactusArcParams cactusArcParams(JHVEvent evt, long timestamp) {
        double angularWidthDegree = SWEKData.readCMEAngularWidthDegree(evt);
        double principalAngleDegree = SWEKData.readCMEPrincipalAngleDegree(evt);
        double speed = SWEKData.readCMESpeed(evt);
        double distSun = DIST_SUN_BEGIN + speed * (timestamp - evt.start) / Sun.RadiusMeter;
        return new CactusArcParams(angularWidthDegree, principalAngleDegree, distSun);
    }

    private void drawCactusArc(JHVRelatedEvents evtr, JHVEvent evt, long timestamp) {
        CactusArcParams params = cactusArcParams(evt, timestamp);
        double angularWidthDegree = params.angularWidthDegree();
        double angularWidth = Math.toRadians(angularWidthDegree);
        double principalAngleDegree = params.principalAngleDegree();
        double principalAngle = Math.toRadians(principalAngleDegree);
        double distSun = params.distSun();
        int lineResolution = 2;
        int angularResolution = (int) (angularWidthDegree / 4);

        Quat q = evt.getPositionInformation().getEarth().toQuat();
        double thetaStart = principalAngle - angularWidth / 2.;
        double thetaEnd = principalAngle + angularWidth / 2.;

        BufVertex vexBuf = evtr.isHighlighted() ? bufThick : bufEvent;
        byte[] color = Colors.bytes(evtr.getColor());

        drawInterpolated(angularResolution, distSun, distSun, thetaStart, principalAngle, q, color, vexBuf);
        drawInterpolated(angularResolution, distSun, distSun, principalAngle, thetaEnd, q, color, vexBuf);
        drawInterpolated(lineResolution, DIST_SUN_BEGIN, distSun + 0.05, thetaStart, thetaStart, q, color, vexBuf);
        drawInterpolated(lineResolution, DIST_SUN_BEGIN, distSun + 0.05, principalAngle, principalAngle, q, color, vexBuf);
        drawInterpolated(lineResolution, DIST_SUN_BEGIN, distSun + 0.05, thetaEnd, thetaEnd, q, color, vexBuf);

        if (icons) {
            double sz = evtr.isHighlighted() ? ICON_SIZE_HIGHLIGHTED : ICON_SIZE;
            for (float[] el : texCoord) {
                double deltatheta = sz / distSun * (el[0] * 2 - 1);
                double deltar = sz * (el[1] * 2 - 1);
                double r = distSun - deltar;
                double theta = principalAngle - deltatheta;

                texBuf.putCoord(q.rotateInverseVector(PolarBasis.vec3(r, theta)), el);
            }
        }
    }

    private void drawPolygon(MapView mv, Viewport vp, JHVRelatedEvents evtr, JHVEvent evt) {
        JHVPositionInformation pi = evt.getPositionInformation();
        if (pi == null)
            return;

        float[] points = pi.getBoundBox();
        if (points.length == 0) {
            return;
        }

        BufVertex vexBuf = evtr.isHighlighted() ? bufThick : bufEvent;
        byte[] color = Colors.bytes(evtr.getColor());

        // draw bounds
        float[] oldBoundaryPoint3d = new float[0];
        int plen = points.length / 3;
        for (int i = 0; i < plen; i++) {
            if (oldBoundaryPoint3d.length != 0) {
                for (int j = 0; j <= DIVPOINTS; j++) {
                    double alpha = 1. - j / (double) DIVPOINTS;
                    double xnew = alpha * oldBoundaryPoint3d[0] + (1 - alpha) * points[3 * i];
                    double ynew = alpha * oldBoundaryPoint3d[1] + (1 - alpha) * points[3 * i + 1];
                    double znew = alpha * oldBoundaryPoint3d[2] + (1 - alpha) * points[3 * i + 2];
                    double r = Math.sqrt(xnew * xnew + ynew * ynew + znew * znew);
                    vertices.set(j, new Vec3(xnew / r, ynew / r, znew / r));
                }
                mv.emitMapLine(vp, vertices, POLYGON_RADIUS, color, vexBuf);
            }
            oldBoundaryPoint3d = new float[]{points[3 * i], points[3 * i + 1], points[3 * i + 2]};
        }
    }

    private final List<Vec3> vertices = fixedSizeVertices(DIVPOINTS + 1);

    private static List<Vec3> fixedSizeVertices(int size) {
        List<Vec3> vertices = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
            vertices.add(Vec3.ZERO);
        return vertices;
    }

    private void drawImage3d(double x, double y, double z, double width, double height) {
        Vec3 targetDir = new Vec3(x, y, z);
        Quat q = Quat.rotate(Quat.createAxisY(Math.atan2(x, z)), Quat.createAxisX(-Math.asin(y / targetDir.length())));

        double width2 = width / 2.;
        double height2 = height / 2.;
        Vec3 r0 = q.rotateVector(new Vec3(-width2, -height2, 0));
        Vec3 r1 = q.rotateVector(new Vec3(width2, -height2, 0));
        Vec3 r2 = q.rotateVector(new Vec3(-width2, height2, 0));
        Vec3 r3 = q.rotateVector(new Vec3(width2, height2, 0));
        Vec3 p0 = new Vec3(r0.x + targetDir.x, r0.y + targetDir.y, r0.z + targetDir.z);
        Vec3 p1 = new Vec3(r1.x + targetDir.x, r1.y + targetDir.y, r1.z + targetDir.z);
        Vec3 p2 = new Vec3(r2.x + targetDir.x, r2.y + targetDir.y, r2.z + targetDir.z);
        Vec3 p3 = new Vec3(r3.x + targetDir.x, r3.y + targetDir.y, r3.z + targetDir.z);

        texBuf.putCoord(p0, texCoord[0]);
        texBuf.putCoord(p1, texCoord[1]);
        texBuf.putCoord(p2, texCoord[2]);
        texBuf.putCoord(p3, texCoord[3]);
    }

    private void drawIcon(JHVRelatedEvents evtr, JHVEvent evt) {
        JHVPositionInformation pi = evt.getPositionInformation();
        if (pi == null)
            return;

        Vec3 pt = pi.centralPoint();
        if (pt != null) {
            double sz = evtr.isHighlighted() ? ICON_SIZE_HIGHLIGHTED : ICON_SIZE;
            drawImage3d(pt.x, pt.y, pt.z, sz, sz);
        }
    }

    private void drawImageScale(double theta, double r, double width, double height) {
        double width2 = width / 4.;
        double height2 = height / 4.;

        texBuf.putCoord((float) (theta - width2), (float) (r - height2), 0, 1, texCoord[0]);
        texBuf.putCoord((float) (theta + width2), (float) (r - height2), 0, 1, texCoord[1]);
        texBuf.putCoord((float) (theta - width2), (float) (r + height2), 0, 1, texCoord[2]);
        texBuf.putCoord((float) (theta + width2), (float) (r + height2), 0, 1, texCoord[3]);
    }

    private void drawIconScale(MapView mv, Viewport vp, JHVRelatedEvents evtr, JHVEvent evt) {
        JHVPositionInformation pi = evt.getPositionInformation();
        if (pi == null)
            return;

        Vec3 pt = pi.centralPoint();
        if (pt != null) {
            Vec2 tf = mv.projectToScreen(vp, pt);
            double sz = evtr.isHighlighted() ? ICON_SIZE_HIGHLIGHTED : ICON_SIZE;
            drawImageScale(tf.x, tf.y, sz, sz);
        }
    }

    // Warped screen radius of physical radius r on the Sun-centered disk; matches
    // RadialWarpGrid.ringRho so arcs and markers sit exactly on the grid rings.
    private static double ringRho(MapScale scale, double r) {
        return .5 * scale.toUnitY(r);
    }

    // CACTus arc for the Sun-centered disk projection (RadialWarp): same wedge as the
    // orthographic arc, but placed in the disk's world coordinates — physical radius r
    // maps to the warped screen radius ringRho(scale, r), and PolarBasis puts the
    // angle at north-up/CCW, matching the disk grid. The front sits on the grid ring at
    // distSun, so with CME tracking engaged it holds a fixed screen radius.
    private void drawCactusArcDisk(JHVRelatedEvents evtr, JHVEvent evt, long timestamp, MapScale scale) {
        CactusArcParams params = cactusArcParams(evt, timestamp);
        double principalAngle = Math.toRadians(params.principalAngleDegree());
        double halfWidth = Math.toRadians(params.angularWidthDegree()) / 2.;
        double thetaStart = principalAngle - halfWidth;
        double thetaEnd = principalAngle + halfWidth;
        double rhoFront = ringRho(scale, params.distSun());
        double rhoInner = ringRho(scale, DIST_SUN_BEGIN);

        BufVertex vexBuf = evtr.isHighlighted() ? bufThick : bufEvent;
        byte[] color = Colors.bytes(evtr.getColor());

        // outer arc: sweep the angle at the front radius
        int steps = Math.max(2, (int) (params.angularWidthDegree() / 2));
        for (int i = 0; i <= steps; i++) {
            Vec3 p = PolarBasis.vec3(rhoFront, thetaStart + (thetaEnd - thetaStart) * i / steps);
            if (i == 0)
                vexBuf.putVertex(p, Colors.Null);
            vexBuf.putVertex(p, color);
        }
        vexBuf.repeatVertex(Colors.Null);

        // radial spokes at both edges and the principal angle, inner edge to front
        for (double theta : new double[]{thetaStart, principalAngle, thetaEnd}) {
            vexBuf.putVertex(PolarBasis.vec3(rhoInner, theta), Colors.Null);
            vexBuf.putVertex(PolarBasis.vec3(rhoInner, theta), color);
            vexBuf.putVertex(PolarBasis.vec3(rhoFront, theta), color);
            vexBuf.repeatVertex(Colors.Null);
        }

        if (icons) { // marker at the front, so a disk wedge is as findable as one in the other modes
            double sz = evtr.isHighlighted() ? ICON_SIZE_HIGHLIGHTED : ICON_SIZE;
            Vec3 at = PolarBasis.vec3(rhoFront, principalAngle);
            drawImageScale(at.x, at.y, sz, sz);
        }
    }

    // While CME tracking is engaged (RadialWarp or RectWarp), mark it: an orange dot at the
    // front's calculated location (physical radius -> warped screen position) and a purple circle
    // at the fixed "freeze" screen radius (SCREEN_FRACTION), both on the tracked position angle.
    // If the solve is right, the front sits on the freeze radius and the two are concentric.
    private void drawTrackerMarkers(MapView mv, Viewport vp, MapScale scale) {
        double paDeg = CMETracker.positionAngleDeg();
        double frac = CMETracker.screenFraction();
        double rCme = CMETracker.currentFront();
        Vec3 freeze;
        Vec3 front;
        if (mv.isRadialWarp()) { // Sun-centered disk: screen fraction f -> rho = 0.5*f (matches ringRho)
            double pa = Math.toRadians(paDeg);
            freeze = PolarBasis.vec3(0.5 * frac, pa);
            front = PolarBasis.vec3(ringRho(scale, rCme), pa);
        } else { // RectWarp unwrap: x = angle, y = warped radius normalized to [-0.5, 0.5]
            double x = (scale.toUnitX(paDeg) - 0.5) * vp.aspect;
            freeze = new Vec3(x, frac - 0.5, 0);
            front = new Vec3(x, scale.toUnitY(rCme) - 0.5, 0);
        }
        ringInto(freeze, 0.028, TRACK_FREEZE); // the freeze location
        ringInto(front, 0.007, TRACK_FRONT);   // the calculated front (small -> reads as a dot)
    }

    private void ringInto(Vec3 center, double radius, byte[] color) {
        int n = 48;
        for (int i = 0; i <= n; i++) {
            double a = 2 * Math.PI * i / n;
            Vec3 p = new Vec3(center.x + radius * Math.cos(a), center.y + radius * Math.sin(a), 0);
            if (i == 0)
                bufEvent.putVertex(p, Colors.Null);
            bufEvent.putVertex(p, color);
        }
        bufEvent.repeatVertex(Colors.Null);
    }

    private void drawCactusArcScale(Viewport vp, JHVRelatedEvents evtr, JHVEvent evt, long timestamp, MapScale scale) {
        CactusArcParams params = cactusArcParams(evt, timestamp);
        double angularWidthDegree = params.angularWidthDegree();
        double principalAngleDegree = params.principalAngleDegree();
        double distSun = params.distSun();

        double thetaStart = MathUtils.mapTo0To360(principalAngleDegree - angularWidthDegree / 2.);
        double thetaEnd = MathUtils.mapTo0To360(principalAngleDegree + angularWidthDegree / 2.);

        BufVertex vexBuf = evtr.isHighlighted() ? bufThick : bufEvent;
        byte[] color = Colors.bytes(evtr.getColor());

        float x = (float) ((scale.toUnitX(thetaStart) - 0.5) * vp.aspect);
        float y = (float) (scale.toUnitY(DIST_SUN_BEGIN) - 0.5);
        vexBuf.putVertex(x, y, 0, 1, Colors.Null);
        vexBuf.repeatVertex(color);

        y = (float) (scale.toUnitY(distSun + 0.05) - 0.5);
        vexBuf.putVertex(x, y, 0, 1, color);
        vexBuf.repeatVertex(Colors.Null);

        x = (float) ((scale.toUnitX(principalAngleDegree) - 0.5) * vp.aspect);
        y = (float) (scale.toUnitY(DIST_SUN_BEGIN) - 0.5);
        vexBuf.putVertex(x, y, 0, 1, Colors.Null);
        vexBuf.repeatVertex(color);

        y = (float) (scale.toUnitY(distSun + 0.05) - 0.5);
        vexBuf.putVertex(x, y, 0, 1, color);
        vexBuf.repeatVertex(Colors.Null);

        x = (float) ((scale.toUnitX(thetaEnd) - 0.5) * vp.aspect);
        y = (float) (scale.toUnitY(DIST_SUN_BEGIN) - 0.5);
        vexBuf.putVertex(x, y, 0, 1, Colors.Null);
        vexBuf.repeatVertex(color);

        y = (float) (scale.toUnitY(distSun + 0.05) - 0.5);
        vexBuf.putVertex(x, y, 0, 1, color);
        vexBuf.repeatVertex(Colors.Null);

        y = (float) (scale.toUnitY(distSun) - 0.5);
        vexBuf.putVertex(x, y, 0, 1, Colors.Null);
        vexBuf.repeatVertex(color);

        x = (float) ((scale.toUnitX(thetaStart) - 0.5) * vp.aspect);
        vexBuf.putVertex(x, y, 0, 1, color);
        vexBuf.repeatVertex(Colors.Null);

        if (icons) {
            double sz = evtr.isHighlighted() ? ICON_SIZE_HIGHLIGHTED : ICON_SIZE;
            drawImageScale((scale.toUnitX(principalAngleDegree) - 0.5) * vp.aspect,
                    scale.toUnitY(distSun) - 0.5, sz, sz);
        }
    }

    private static final int MOUSE_OFFSET_X = 25;
    private static final int MOUSE_OFFSET_Y = 25;

    private void drawText(Viewport vp, JHVRelatedEvents mouseOverJHVEvent, int x, int y, long currentTime) {
        GLText.drawTextFloat(vp, SWEKData.visibleParameterLines(mouseOverJHVEvent.getClosestTo(currentTime)), x + MOUSE_OFFSET_X, y + MOUSE_OFFSET_Y);
    }

    private void renderEvents(Viewport vp) {
        lineEvent.setVertex(bufEvent);
        lineThick.setVertex(bufThick);
        lineEvent.renderLine(vp, LINEWIDTH);
        lineThick.renderLine(vp, LINEWIDTH_HIGHLIGHT);
    }

    private void renderIcons(MapView mv, List<JHVRelatedEvents> evs, long currentTime) {
        glslTexture.setCoord(texBuf);
        int idx = 0;
        for (JHVRelatedEvents evtr : evs) {
            JHVEvent evt = evtr.getClosestTo(currentTime);
            if (mv.isLatitudinal() && evt.isCactus()) // no icon quad emitted for CACTus there
                continue;
            bindTexture(evtr.getSupplier().group());
            glslTexture.renderTexture(GL.TRIANGLE_STRIP, Colors.floats(evtr.getColor(), ICON_ALPHA), idx, 4);
            idx += 4;
        }
    }

    private List<JHVRelatedEvents> activeEvents(long time) {
        long start = Player.getStartTime();
        long end = Player.getEndTime();
        if (time != cachedEventsTime || start != cachedEventsStart || end != cachedEventsEnd) {
            cachedEventsTime = time;
            cachedEventsStart = start;
            cachedEventsEnd = end;
            cachedActiveEvents = JHVEventCache.getEvents(time, time);
        }
        return cachedActiveEvents;
    }

    private void invalidateActiveEvents() {
        cachedEventsTime = Long.MIN_VALUE;
        cachedPropTime = Long.MIN_VALUE;
    }

    // CACTus events past their catalog end whose front, propagated at the catalog radial speed,
    // is still within the user's extend distance. Empty unless the "extend" toggle is on. Disjoint
    // from activeEvents() (which ends at the LASCO edge), so drawing both never double-counts one.
    // NB: the front here is a constant-speed extrapolation beyond where LASCO measured the CME.
    private List<JHVRelatedEvents> propagatingCactus(long time) {
        if (!extendCactus)
            return List.of();
        double fov = effectiveExtendDistance();
        if (fov <= DIST_SUN_BEGIN)
            return List.of();
        // Memoized on (time, movie range, fov) like activeEvents(), so the repeated per-viewport /
        // per-frame calls during playback don't re-scan + re-parse the event set every frame.
        long start = Player.getStartTime();
        long end = Player.getEndTime();
        if (time != cachedPropTime || start != cachedPropStart || end != cachedPropEnd || fov != cachedPropFov) {
            cachedPropTime = time;
            cachedPropStart = start;
            cachedPropEnd = end;
            cachedPropFov = fov;
            List<JHVRelatedEvents> out = new ArrayList<>();
            for (JHVRelatedEvents evtr : JHVEventCache.getEvents(time - CACTUS_MAX_TRAVEL_MS, time)) {
                if (evtr.getEnd() >= time) // still within its catalog window -> already drawn by activeEvents
                    continue;
                JHVEvent evt = evtr.getClosestTo(time);
                if (evt.isCactus() && cactusArcParams(evt, time).distSun() <= fov)
                    out.add(evtr);
            }
            cachedProp = out;
        }
        return cachedProp;
    }

    @Override
    public void render(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx])
            return;
        long currentTime = mv.viewpoint().time.milli;
        List<JHVRelatedEvents> evs = activeEvents(currentTime);
        List<JHVRelatedEvents> prop = propagatingCactus(currentTime); // empty unless the extend toggle is on
        if (evs.isEmpty() && prop.isEmpty())
            return;

        for (JHVRelatedEvents evtr : evs) {
            JHVEvent evt = evtr.getClosestTo(currentTime);
            if (evt.isCactus()) {
                drawCactusArc(evtr, evt, currentTime);
            } else {
                drawPolygon(mv, vp, evtr, evt);
                if (icons) {
                    drawIcon(evtr, evt);
                }
            }
        }
        // Extended fronts keep their icon too, so a wedge stays just as findable (and clickable)
        // after it passes the catalog window as it was before.
        for (JHVRelatedEvents evtr : prop)
            drawCactusArc(evtr, evtr.getClosestTo(currentTime), currentTime);

        renderEvents(vp);
        List<JHVRelatedEvents> iconEvents = concat(evs, prop); // must match what emitted icon quads
        if (icons && !iconEvents.isEmpty()) {
            renderIcons(mv, iconEvents, currentTime);
        }
    }

    // evs + prop without copying when one side is empty; renderIcons walks the icon buffer in the
    // same order the draw calls filled it, so the two lists have to agree.
    private static List<JHVRelatedEvents> concat(List<JHVRelatedEvents> a, List<JHVRelatedEvents> b) {
        if (b.isEmpty())
            return a;
        if (a.isEmpty())
            return b;
        List<JHVRelatedEvents> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    @Override
    public void renderScale(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx])
            return;
        long currentTime = mv.viewpoint().time.milli;
        List<JHVRelatedEvents> evs = activeEvents(currentTime);
        boolean radial = mv.isRectWarp() || mv.isRadialWarp();
        List<JHVRelatedEvents> prop = radial ? propagatingCactus(currentTime) : List.of(); // empty unless extend toggle on
        boolean markers = radial && CMETracker.isTracking();
        if (evs.isEmpty() && prop.isEmpty() && !markers)
            return;

        MapScale scale = mv.scale(vp);
        for (JHVRelatedEvents evtr : evs) {
            JHVEvent evt = evtr.getClosestTo(currentTime);
            if (evt.isCactus() && mv.isRectWarp()) {
                drawCactusArcScale(vp, evtr, evt, currentTime, scale);
            } else if (evt.isCactus() && mv.isRadialWarp()) {
                drawCactusArcDisk(evtr, evt, currentTime, scale);
            } else {
                drawPolygon(mv, vp, evtr, evt);
                if (icons) {
                    drawIconScale(mv, vp, evtr, evt);
                }
            }
        }
        for (JHVRelatedEvents evtr : prop) { // extrapolated CACTus fronts past the LASCO edge, out to the loaded FOV
            JHVEvent evt = evtr.getClosestTo(currentTime);
            if (mv.isRectWarp())
                drawCactusArcScale(vp, evtr, evt, currentTime, scale);
            else
                drawCactusArcDisk(evtr, evt, currentTime, scale);
        }
        if (markers)
            drawTrackerMarkers(mv, vp, scale);

        renderEvents(vp);
        List<JHVRelatedEvents> iconEvents = concat(evs, prop);
        if (icons && !iconEvents.isEmpty()) {
            renderIcons(mv, iconEvents, currentTime);
        }
    }

    @Override
    public void renderFullFloat(Viewport vp) {
        if (!enabled)
            return;
        if (swekContext != null && swekContext.mouseOverJHVEvent() != null) {
            drawText(vp, swekContext.mouseOverJHVEvent(), swekContext.mouseOverX(), swekContext.mouseOverY(), swekContext.mouseOverTime());
        }
    }

    @Override
    public void remove() {
        setEnabled(false);
        dispose();
    }

    @Override
    public String getName() {
        return "SWEK Events";
    }

    @Override
    public void setEnabled(boolean _enabled) {
        super.setEnabled(_enabled);

        if (enabled) {
            JHVEventCache.registerHandler(this);
            Player.addTimeRangeListener(this);
            requestEvents(true, Player.getStartTime(), Player.getEndTime());
        } else {
            invalidateActiveEvents();
            JHVEventCache.highlight(null);
            Player.removeTimeRangeListener(this);
            JHVEventCache.unregisterHandler(this);
        }
        SWEKPlugin.layerStateChanged(this);
    }

    @Override
    public void init() {
        lineEvent.init();
        lineThick.init();
        glslTexture.init();
    }

    @Override
    public void dispose() {
        lineEvent.dispose();
        lineThick.dispose();
        glslTexture.dispose();
        iconCacheId.values().forEach(GLTexture::delete);
        iconCacheId.clear();
    }

    private long startTime = Player.getStartTime();
    private long endTime = Player.getEndTime();

    private void requestEvents(boolean force, long start, long end) {
        if (force || start < startTime || end > endTime) {
            startTime = start;
            endTime = end;
            JHVEventCache.requestForInterval(start, end, this);
        }
    }

    @Override
    public void timeRangeChanged(long start, long end) {
        invalidateActiveEvents();
        requestEvents(false, start, end);
    }

    @Override
    public void newEventsReceived() {
        if (enabled) {
            invalidateActiveEvents();
            DisplayController.display();
        }
    }

    @Override
    public void cacheUpdated() {
        if (!enabled)
            return;
        invalidateActiveEvents();
        requestEvents(true, Player.getStartTime(), Player.getEndTime());
    }

    // The extended fronts currently drawn. The picker needs the same set the renderer uses, or a
    // wedge past its catalog window stays visible but stops being selectable.
    List<JHVRelatedEvents> propagatingNow(long time) {
        return propagatingCactus(time);
    }

    boolean isIcons() {
        return icons;
    }

    void setIcons(boolean _icons) {
        icons = _icons;
        DisplayController.display();
    }

    boolean isExtendCactus() {
        return extendCactus;
    }

    void setExtendCactus(boolean _extend) {
        extendCactus = _extend;
        cachedPropTime = Long.MIN_VALUE; // toggling must not serve a stale list
        DisplayController.display();
    }

    static double extendDistanceMin() {
        return EXTEND_DIST_MIN;
    }

    static double extendDistanceMax() {
        return EXTEND_DIST_MAX;
    }

    // The reach actually used: the user's explicit distance, or the loaded FOV while on auto, so
    // extended fronts run out at the edge of the data.
    double effectiveExtendDistance() {
        if (extendDistance > 0)
            return extendDistance;
        double fov = ImageLayers.getLargestRadialSize();
        return fov > EXTEND_DIST_MIN ? Math.min(fov, EXTEND_DIST_MAX) : EXTEND_DIST_FALLBACK;
    }

    boolean isExtendDistanceAuto() {
        return extendDistance <= 0;
    }

    void setExtendDistance(double _distance) {
        extendDistance = Math.clamp(_distance, EXTEND_DIST_MIN, EXTEND_DIST_MAX);
        cachedPropTime = Long.MIN_VALUE; // changing the reach must not serve a stale list
        DisplayController.display();
    }

    void setExtendDistanceAuto() {
        extendDistance = 0;
        cachedPropTime = Long.MIN_VALUE;
        DisplayController.display();
    }

}
