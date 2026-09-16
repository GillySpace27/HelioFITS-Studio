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
import org.helioviewer.jhv.event.EventCache;
import org.helioviewer.jhv.event.EventGeometry;
import org.helioviewer.jhv.event.EventListener;
import org.helioviewer.jhv.event.RelatedEvents;
import org.helioviewer.jhv.event.SWEKDownloader;
import org.helioviewer.jhv.event.SWEKGroup;
import org.helioviewer.jhv.event.SolarEvent;
import org.helioviewer.jhv.image.nio.NativeImageFactory;
import org.helioviewer.jhv.layers.AbstractLayer;
import org.helioviewer.jhv.layers.ImageLayers;
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
public final class SWEKLayer extends AbstractLayer implements EventListener.Handle, TimeListener.Range {
    record ActiveEvent(RelatedEvents relatedEvents, SolarEvent event) {}

    private record CactusArcParams(double angularWidthDegree, double principalAngleDegree, double distSun) {}

    private static final int DIVPOINTS = 10;
    private static final double LINEWIDTH = GLSLLine.LINEWIDTH_BASIC;
    private static final double LINEWIDTH_HIGHLIGHT = 4 * LINEWIDTH; // selection has to read at a glance
    private static final double POLYGON_RADIUS = Sun.Radius * 1.01;
    private static final long CACTUS_MAX_TRAVEL_MS = 14L * 24 * 3600 * 1000; // lookback for propagated fronts (covers slow CMEs)

    private static final byte[] TRACK_FRONT = Colors.bytes(255, 140, 0);   // orange dot at the tracked CME's calculated front
    private static final byte[] TRACK_FREEZE = Colors.bytes(170, 60, 230); // purple circle at the freeze location (SCREEN_FRACTION)

    private static final double EXTEND_DIST_MIN = 2;
    private static final double EXTEND_DIST_MAX = 200;
    private static final double EXTEND_DIST_FALLBACK = 60; // only until something is loaded

    private static final HashMap<String, GLTexture> iconCacheId = new HashMap<>();
    private static final double ICON_ALPHA = 0.7;
    private static final double ICON_SIZE = 0.1;
    private static final double ICON_SIZE_HIGHLIGHTED = 0.16;

    private static final float[][] texCoord = {{0, 1}, {1, 1}, {0, 0}, {1, 0}};

    private SWEKContext swekContext;
    private boolean icons = true;
    private boolean extendCactus = true; // propagate CACTus fronts past the LASCO catalog end
    // R☉ to propagate fronts out to. 0 = auto, meaning follow the loaded field of view so an
    // extended front runs out at the edge of the data instead of at an arbitrary fixed radius.
    private double extendDistance = 0;

    private final GLSLLine lineEvent = new GLSLLine(true);
    private final BufVertex bufEvent = new BufVertex();
    private final GLSLLine lineThick = new GLSLLine(true);
    private final BufVertex bufThick = new BufVertex();

    private final GLSLTexture glslTexture = new GLSLTexture();
    private final BufCoord texBuf = new BufCoord(4 * 8);

    private long cachedEventsTime = Long.MIN_VALUE;
    private List<ActiveEvent> cachedActiveEvents = List.of();

    private long cachedPropTime = Long.MIN_VALUE;
    private long cachedPropStart, cachedPropEnd;
    private double cachedPropFov = Double.NaN;
    private List<ActiveEvent> cachedProp = List.of();

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

                ByteBuffer data = NativeImageFactory.getByteBuffer(bi);
                tex.upload2D(GLTexture.Format.RGBA8, bi.getWidth(), bi.getHeight(), GL.LINEAR, data);
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

            if (i == 0)
                vexBuf.startLine(res, color);
            else
                vexBuf.putVertex(res, color);
        }
        vexBuf.endLine();
    }

    private static CactusArcParams cactusArcParams(SolarEvent evt, long timestamp) {
        double angularWidthDegree = evt.getCMEParameters().angularWidthDegree();
        double principalAngleDegree = evt.getCMEParameters().principalAngleDegree();
        double distSun = SWEKData.cactusDistance(evt, timestamp);
        return new CactusArcParams(angularWidthDegree, principalAngleDegree, distSun);
    }

    private void drawCactusArc(RelatedEvents relatedEvents, SolarEvent evt, long timestamp) {
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

        BufVertex vexBuf = relatedEvents.isHighlighted() ? bufThick : bufEvent;
        byte[] color = Colors.bytes(relatedEvents.getColor());

        drawInterpolated(angularResolution, distSun, distSun, thetaStart, principalAngle, q, color, vexBuf);
        drawInterpolated(angularResolution, distSun, distSun, principalAngle, thetaEnd, q, color, vexBuf);
        drawInterpolated(lineResolution, SWEKData.CACTUS_START_RADIUS, distSun + 0.05, thetaStart, thetaStart, q, color, vexBuf);
        drawInterpolated(lineResolution, SWEKData.CACTUS_START_RADIUS, distSun + 0.05, principalAngle, principalAngle, q, color, vexBuf);
        drawInterpolated(lineResolution, SWEKData.CACTUS_START_RADIUS, distSun + 0.05, thetaEnd, thetaEnd, q, color, vexBuf);

        if (icons) {
            double sz = relatedEvents.isHighlighted() ? ICON_SIZE_HIGHLIGHTED : ICON_SIZE;
            for (float[] el : texCoord) {
                double deltatheta = sz / distSun * (el[0] * 2 - 1);
                double deltar = sz * (el[1] * 2 - 1);
                double r = distSun - deltar;
                double theta = principalAngle - deltatheta;

                texBuf.putCoord(q.rotateInverseVector(PolarBasis.vec3(r, theta)), el);
            }
        }
    }

    private void drawPolygon(MapView mv, Viewport vp, RelatedEvents relatedEvents, SolarEvent evt) {
        EventGeometry pi = evt.getPositionInformation();
        if (pi == null)
            return;

        float[] points = pi.getBoundBox();
        if (points.length == 0) {
            return;
        }

        BufVertex vexBuf = relatedEvents.isHighlighted() ? bufThick : bufEvent;
        byte[] color = Colors.bytes(relatedEvents.getColor());

        // draw bounds
        int plen = points.length / 3;
        for (int i = 1; i < plen; i++) {
            int previous = 3 * (i - 1), current = 3 * i;
            for (int j = 0; j <= DIVPOINTS; j++) {
                double alpha = 1. - j / (double) DIVPOINTS;
                double xnew = alpha * points[previous] + (1 - alpha) * points[current];
                double ynew = alpha * points[previous + 1] + (1 - alpha) * points[current + 1];
                double znew = alpha * points[previous + 2] + (1 - alpha) * points[current + 2];
                double r = Math.sqrt(xnew * xnew + ynew * ynew + znew * znew);
                vertices.set(j, new Vec3(xnew / r, ynew / r, znew / r));
            }
            mv.emitMapLine(vp, vertices, POLYGON_RADIUS, color, vexBuf);
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

    private void drawIcon(RelatedEvents relatedEvents, SolarEvent evt) {
        EventGeometry pi = evt.getPositionInformation();
        if (pi == null)
            return;

        Vec3 pt = pi.centralPoint();
        if (pt != null) {
            double sz = relatedEvents.isHighlighted() ? ICON_SIZE_HIGHLIGHTED : ICON_SIZE;
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

    private void drawIconScale(MapView mv, Viewport vp, RelatedEvents relatedEvents, SolarEvent evt) {
        EventGeometry pi = evt.getPositionInformation();
        if (pi == null)
            return;

        Vec3 pt = pi.centralPoint();
        if (pt != null) {
            Vec2 tf = mv.projectToScreen(vp, pt);
            double sz = relatedEvents.isHighlighted() ? ICON_SIZE_HIGHLIGHTED : ICON_SIZE;
            drawImageScale(tf.x, tf.y, sz, sz);
        }
    }

    private static void putLineScale(BufVertex vexBuf, float x0, float y0, float x1, float y1, byte[] color) {
        vexBuf.startLine(x0, y0, 0, 1, color);
        vexBuf.putVertex(x1, y1, 0, 1, color);
        vexBuf.endLine();
    }

    // Warped screen radius of physical radius r on the Sun-centered disk; matches the disk grid's
    // own ring placement so arcs and markers sit exactly on the grid rings.
    private static double ringRho(MapScale scale, double r) {
        return .5 * scale.toUnitY(r);
    }

    // CACTus arc for the Sun-centered disk projection (Helioradial): the same wedge as the
    // orthographic arc, but placed in the disk's world coordinates. Physical radius r maps to the
    // warped screen radius ringRho(scale, r), and PolarBasis puts the angle at north-up/CCW,
    // matching the disk grid. The front sits on the grid ring at distSun, so with CME tracking
    // engaged it holds a fixed screen radius.
    private void drawCactusArcDisk(RelatedEvents relatedEvents, SolarEvent evt, long timestamp, MapScale scale) {
        CactusArcParams params = cactusArcParams(evt, timestamp);
        double principalAngle = Math.toRadians(params.principalAngleDegree());
        double halfWidth = Math.toRadians(params.angularWidthDegree()) / 2.;
        double thetaStart = principalAngle - halfWidth;
        double thetaEnd = principalAngle + halfWidth;
        double rhoFront = ringRho(scale, params.distSun());
        double rhoInner = ringRho(scale, SWEKData.CACTUS_START_RADIUS);

        BufVertex vexBuf = relatedEvents.isHighlighted() ? bufThick : bufEvent;
        byte[] color = Colors.bytes(relatedEvents.getColor());

        // outer arc: sweep the angle at the front radius
        int steps = Math.max(2, (int) (params.angularWidthDegree() / 2));
        for (int i = 0; i <= steps; i++) {
            Vec3 p = PolarBasis.vec3(rhoFront, thetaStart + (thetaEnd - thetaStart) * i / steps);
            if (i == 0)
                vexBuf.startLine(p, color);
            else
                vexBuf.putVertex(p, color);
        }
        vexBuf.endLine();

        // radial spokes at both edges and the principal angle, inner edge to front
        for (double theta : new double[]{thetaStart, principalAngle, thetaEnd}) {
            vexBuf.startLine(PolarBasis.vec3(rhoInner, theta), color);
            vexBuf.putVertex(PolarBasis.vec3(rhoFront, theta), color);
            vexBuf.endLine();
        }

        if (icons) { // marker at the front, so a disk wedge is as findable as one in the other modes
            double sz = relatedEvents.isHighlighted() ? ICON_SIZE_HIGHLIGHTED : ICON_SIZE;
            Vec3 at = PolarBasis.vec3(rhoFront, principalAngle);
            drawImageScale(at.x, at.y, sz, sz);
        }
    }

    // While CME tracking is engaged (Helioradial or HelioradialUnrolled), mark it: an orange dot at
    // the front's calculated location (physical radius to warped screen position) and a purple
    // circle at the fixed "freeze" screen radius (SCREEN_FRACTION), both on the tracked position
    // angle. If the solve is right, the front sits on the freeze radius and the two are concentric.
    private void drawTrackerMarkers(MapView mv, Viewport vp, MapScale scale) {
        double paDeg = CMETracker.positionAngleDeg();
        double frac = CMETracker.screenFraction();
        double rCme = CMETracker.currentFront();
        Vec3 freeze;
        Vec3 front;
        if (mv.isHelioradial()) { // Sun-centered disk: screen fraction f -> rho = 0.5*f (matches ringRho)
            double pa = Math.toRadians(paDeg);
            freeze = PolarBasis.vec3(0.5 * frac, pa);
            front = PolarBasis.vec3(ringRho(scale, rCme), pa);
        } else { // HelioradialUnrolled unwrap: x = angle, y = warped radius normalized to [-0.5, 0.5]
            double x = (scale.toUnitX(paDeg) - 0.5) * vp.aspect;
            freeze = new Vec3(x, frac - 0.5, 0);
            front = new Vec3(x, scale.toUnitY(rCme) - 0.5, 0);
        }
        ringInto(freeze, 0.028, TRACK_FREEZE); // the freeze location
        ringInto(front, 0.007, TRACK_FRONT);   // the calculated front (small, so it reads as a dot)
    }

    private void ringInto(Vec3 center, double radius, byte[] color) {
        int n = 48;
        for (int i = 0; i <= n; i++) {
            double a = 2 * Math.PI * i / n;
            Vec3 p = new Vec3(center.x + radius * Math.cos(a), center.y + radius * Math.sin(a), 0);
            if (i == 0)
                bufEvent.startLine(p, color);
            else
                bufEvent.putVertex(p, color);
        }
        bufEvent.endLine();
    }

    private void drawCactusArcScale(Viewport vp, RelatedEvents relatedEvents, SolarEvent evt, long timestamp, MapScale scale) {
        CactusArcParams params = cactusArcParams(evt, timestamp);
        double angularWidthDegree = params.angularWidthDegree();
        double principalAngleDegree = params.principalAngleDegree();
        double distSun = params.distSun();

        double thetaStart = MathUtils.mapTo0To360(principalAngleDegree - angularWidthDegree / 2.);
        double thetaEnd = MathUtils.mapTo0To360(principalAngleDegree + angularWidthDegree / 2.);

        BufVertex vexBuf = relatedEvents.isHighlighted() ? bufThick : bufEvent;
        byte[] color = Colors.bytes(relatedEvents.getColor());

        float x = (float) ((scale.toUnitX(thetaStart) - 0.5) * vp.aspect);
        float y = (float) (scale.toUnitY(SWEKData.CACTUS_START_RADIUS) - 0.5);
        putLineScale(vexBuf, x, y, x, (float) (scale.toUnitY(distSun + 0.05) - 0.5), color);

        x = (float) ((scale.toUnitX(principalAngleDegree) - 0.5) * vp.aspect);
        y = (float) (scale.toUnitY(SWEKData.CACTUS_START_RADIUS) - 0.5);
        putLineScale(vexBuf, x, y, x, (float) (scale.toUnitY(distSun + 0.05) - 0.5), color);

        x = (float) ((scale.toUnitX(thetaEnd) - 0.5) * vp.aspect);
        y = (float) (scale.toUnitY(SWEKData.CACTUS_START_RADIUS) - 0.5);
        putLineScale(vexBuf, x, y, x, (float) (scale.toUnitY(distSun + 0.05) - 0.5), color);

        y = (float) (scale.toUnitY(distSun) - 0.5);
        putLineScale(vexBuf, x, y, (float) ((scale.toUnitX(thetaStart) - 0.5) * vp.aspect), y, color);

        if (icons) {
            double sz = relatedEvents.isHighlighted() ? ICON_SIZE_HIGHLIGHTED : ICON_SIZE;
            drawImageScale((scale.toUnitX(principalAngleDegree) - 0.5) * vp.aspect,
                    scale.toUnitY(distSun) - 0.5, sz, sz);
        }
    }

    private static final int MOUSE_OFFSET_X = 25;
    private static final int MOUSE_OFFSET_Y = 25;

    private void drawText(Viewport vp, RelatedEvents mouseOverEvents, int x, int y, long currentTime) {
        GLText.drawTextFloat(vp, SWEKData.visibleParameterLines(mouseOverEvents.getClosestTo(currentTime)), x + MOUSE_OFFSET_X, y + MOUSE_OFFSET_Y);
    }

    private void renderEvents(Viewport vp) {
        lineEvent.uploadAndClear(bufEvent);
        lineThick.uploadAndClear(bufThick);
        lineEvent.renderLine(vp, LINEWIDTH);
        lineThick.renderLine(vp, LINEWIDTH_HIGHLIGHT);
    }

    private void renderIcons(MapView mv, List<ActiveEvent> evs) {
        glslTexture.setCoord(texBuf);
        int idx = 0;
        for (ActiveEvent active : evs) {
            RelatedEvents relatedEvents = active.relatedEvents();
            SolarEvent evt = active.event();
            if (mv.isLatitudinal() && evt.isCactus()) // no icon quad emitted for CACTus there
                continue;
            if (!evt.isCactus()) {
                EventGeometry pi = evt.getPositionInformation();
                if (pi == null || pi.centralPoint() == null)
                    continue;
            }
            bindTexture(evt.getSupplier().group());
            glslTexture.renderTexture(GL.TRIANGLE_STRIP, Colors.floats(relatedEvents.getColor(), ICON_ALPHA), idx, 4);
            idx += 4;
        }
    }

    List<ActiveEvent> activeEvents(long time) {
        if (time != cachedEventsTime) {
            List<ActiveEvent> active = new ArrayList<>();
            for (RelatedEvents relatedEvents : EventCache.getEvents(time, time))
                active.add(new ActiveEvent(relatedEvents, relatedEvents.getClosestTo(time)));
            cachedActiveEvents = active;
            cachedEventsTime = time;
        }
        return cachedActiveEvents;
    }

    private void invalidateActiveEvents() {
        cachedEventsTime = Long.MIN_VALUE;
        cachedPropTime = Long.MIN_VALUE;
    }

    // CACTus events past their catalog end whose front, propagated at the catalog radial speed, is
    // still within the user's extend distance. Empty unless the "extend" toggle is on. Disjoint
    // from activeEvents() (which ends at the LASCO edge), so drawing both never double-counts one.
    // NB: the front here is a constant-speed extrapolation beyond where LASCO measured the CME.
    private List<ActiveEvent> propagatingCactus(long time) {
        if (!extendCactus)
            return List.of();
        double fov = effectiveExtendDistance();
        if (fov <= SWEKData.CACTUS_START_RADIUS)
            return List.of();
        // Memoized on (time, movie range, fov) like activeEvents(), so the repeated per-viewport /
        // per-frame calls during playback don't re-scan the event set every frame.
        long start = Player.getStartTime();
        long end = Player.getEndTime();
        if (time != cachedPropTime || start != cachedPropStart || end != cachedPropEnd || fov != cachedPropFov) {
            cachedPropTime = time;
            cachedPropStart = start;
            cachedPropEnd = end;
            cachedPropFov = fov;
            List<ActiveEvent> out = new ArrayList<>();
            for (RelatedEvents relatedEvents : EventCache.getEvents(time - CACTUS_MAX_TRAVEL_MS, time)) {
                if (relatedEvents.getEnd() >= time) // still within its catalog window, so activeEvents already drew it
                    continue;
                SolarEvent evt = relatedEvents.getClosestTo(time);
                if (evt.isCactus() && SWEKData.cactusDistance(evt, time) <= fov)
                    out.add(new ActiveEvent(relatedEvents, evt));
            }
            cachedProp = out;
        }
        return cachedProp;
    }

    // evs + prop without copying when one side is empty; renderIcons walks the icon buffer in the
    // same order the draw calls filled it, so the two lists have to agree.
    private static List<ActiveEvent> concat(List<ActiveEvent> a, List<ActiveEvent> b) {
        if (b.isEmpty())
            return a;
        if (a.isEmpty())
            return b;
        List<ActiveEvent> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    @Override
    public void render(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx])
            return;
        long currentTime = mv.viewpoint().time.milli;
        List<ActiveEvent> evs = activeEvents(currentTime);
        List<ActiveEvent> prop = propagatingCactus(currentTime); // empty unless the extend toggle is on
        if (evs.isEmpty() && prop.isEmpty())
            return;

        for (ActiveEvent active : evs) {
            RelatedEvents relatedEvents = active.relatedEvents();
            SolarEvent evt = active.event();
            if (evt.isCactus()) {
                drawCactusArc(relatedEvents, evt, currentTime);
            } else {
                drawPolygon(mv, vp, relatedEvents, evt);
                if (icons) {
                    drawIcon(relatedEvents, evt);
                }
            }
        }
        // Extended fronts keep their icon too, so a wedge stays just as findable (and clickable)
        // after it passes the catalog window as it was before.
        for (ActiveEvent active : prop)
            drawCactusArc(active.relatedEvents(), active.event(), currentTime);

        renderEvents(vp);
        List<ActiveEvent> iconEvents = concat(evs, prop); // must match what emitted icon quads
        if (icons && !iconEvents.isEmpty()) {
            renderIcons(mv, iconEvents);
        }
    }

    @Override
    public void renderScale(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx])
            return;
        long currentTime = mv.viewpoint().time.milli;
        List<ActiveEvent> evs = activeEvents(currentTime);
        boolean radial = mv.isHelioradialUnrolled() || mv.isHelioradial();
        List<ActiveEvent> prop = radial ? propagatingCactus(currentTime) : List.of(); // empty unless extend toggle on
        boolean markers = radial && CMETracker.isTracking();
        if (evs.isEmpty() && prop.isEmpty() && !markers)
            return;

        MapScale scale = mv.scale(vp);
        for (ActiveEvent active : evs) {
            RelatedEvents relatedEvents = active.relatedEvents();
            SolarEvent evt = active.event();
            if (evt.isCactus() && mv.isHelioradialUnrolled()) {
                drawCactusArcScale(vp, relatedEvents, evt, currentTime, scale);
            } else if (evt.isCactus() && mv.isHelioradial()) {
                drawCactusArcDisk(relatedEvents, evt, currentTime, scale);
            } else {
                drawPolygon(mv, vp, relatedEvents, evt);
                if (icons) {
                    drawIconScale(mv, vp, relatedEvents, evt);
                }
            }
        }
        for (ActiveEvent active : prop) { // extrapolated CACTus fronts past the LASCO edge, out to the loaded FOV
            if (mv.isHelioradialUnrolled())
                drawCactusArcScale(vp, active.relatedEvents(), active.event(), currentTime, scale);
            else
                drawCactusArcDisk(active.relatedEvents(), active.event(), currentTime, scale);
        }
        if (markers)
            drawTrackerMarkers(mv, vp, scale);

        renderEvents(vp);
        List<ActiveEvent> iconEvents = concat(evs, prop);
        if (icons && !iconEvents.isEmpty()) {
            renderIcons(mv, iconEvents);
        }
    }

    @Override
    public void renderFullFloat(Viewport vp) {
        if (!enabled)
            return;
        if (swekContext != null && swekContext.mouseOverEvents() != null) {
            drawText(vp, swekContext.mouseOverEvents(), swekContext.mouseOverX(), swekContext.mouseOverY(), swekContext.mouseOverTime());
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
            EventCache.registerHandler(this);
            Player.addTimeRangeListener(this);
            requestEvents(true, Player.getStartTime(), Player.getEndTime());
        } else {
            invalidateActiveEvents();
            EventCache.highlight(null);
            Player.removeTimeRangeListener(this);
            EventCache.unregisterHandler(this);
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
            SWEKDownloader.requestForInterval(start, end);
        }
    }

    @Override
    public void timeRangeChanged(long start, long end) {
        invalidateActiveEvents();
        requestEvents(false, start, end);
    }

    @Override
    public void cacheUpdated() {
        if (!enabled)
            return;
        invalidateActiveEvents();
        requestEvents(true, Player.getStartTime(), Player.getEndTime());
        DisplayController.display();
    }

    boolean isIcons() {
        return icons;
    }

    void setIcons(boolean _icons) {
        icons = _icons;
        DisplayController.display();
    }

    // The extended fronts currently drawn. The picker needs the same set the renderer uses, or a
    // wedge past its catalog window stays visible but stops being selectable.
    List<ActiveEvent> propagatingNow(long time) {
        return propagatingCactus(time);
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
