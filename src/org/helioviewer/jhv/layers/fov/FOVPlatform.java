package org.helioviewer.jhv.layers.fov;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.display.ViewportMath;
import org.helioviewer.jhv.layers.ModelEphemeris;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.opengl.GLHelper;
import org.helioviewer.jhv.opengl.GLSLLine;
import org.helioviewer.jhv.opengl.GLSLShape;
import org.helioviewer.jhv.opengl.GLText;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.opengl.text.SdfTextRenderer;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONObject;

class FOVPlatform {

    static final double MIN_CENTER_ARCMIN = -60;
    static final double MAX_CENTER_ARCMIN = 60;
    private static final double LINEWIDTH_FOV = GLSLLine.LINEWIDTH_BASIC;
    private static final int SUBDIVISIONS = 180; // divisible by 4 so quarter-turn arcs align to exact step ranges
    private static final double HEMI_RADIUS = Sun.Radius + LINEWIDTH_FOV; // avoid intersecting solar surface

    private final GLSLLine hemiLine = new GLSLLine(false);
    private final GLSLLine instrumentLines = new GLSLLine(true);
    private final GLSLShape instrumentCenters = new GLSLShape(true);
    private final GLSLShape coneFill = new GLSLShape(true);
    private final GLSLLine coneLines = new GLSLLine(true);
    private final BufVertex fillBuf = new BufVertex();
    private final BufVertex coneLineBuf = new BufVertex();
    /** Where a platform with no SPICE observer is (SOHO, Parker Solar Probe): the position services. Also gives the RAM direction. */
    private final ModelEphemeris ephemeris = new ModelEphemeris();
    private final BufVertex lineBuf = new BufVertex();
    private final BufVertex centerBuf = new BufVertex();

    private final List<FOVInstrument> instruments = new ArrayList<>();

    private final String name;
    @Nullable
    private final String observer;
    private final byte[] color;
    private final boolean isSOLO;

    private double centerX;
    private double centerY;

    /**
     * {@code _observer} is its SPICE name in the bundled kernels, or null; {@code target} is the
     * object it is placed at (a SpaceObject name, as ModelEphemeris takes), used when there is no
     * SPICE observer and for the direction of travel.
     */
    FOVPlatform(String _name, @Nullable String _observer, String target, byte[] _color, JSONObject jo) {
        name = _name;
        observer = _observer;
        color = _color;
        isSOLO = "SOLO".equals(observer);
        ephemeris.setTarget(target);

        centerX = Math.clamp(jo.optDouble("centerX", 0), MIN_CENTER_ARCMIN, MAX_CENTER_ARCMIN);
        centerY = Math.clamp(jo.optDouble("centerY", 0), MIN_CENTER_ARCMIN, MAX_CENTER_ARCMIN);
    }

    void add(FOVInstrument instrument) {
        instruments.add(instrument);
        instrument.setCenterX(control2Center(centerX));
        instrument.setCenterY(control2Center(centerY));
    }

    List<FOVInstrument> instruments() {
        return instruments;
    }

    private void putHemiLine() {
        int fullCircleVertices = SUBDIVISIONS + 3;
        int halfCircleVertices = SUBDIVISIONS / 2 + 3;
        BufVertex buf = new BufVertex(fullCircleVertices + 2 * halfCircleVertices);
        GLHelper.emitCircle(HEMI_RADIUS, SUBDIVISIONS, 0, SUBDIVISIONS, null, color, Colors.White.bytes(), buf);
        GLHelper.emitCircle(HEMI_RADIUS, SUBDIVISIONS, 0, SUBDIVISIONS / 2, Quat.X90, color, Colors.White.bytes(), buf);
        GLHelper.emitCircle(HEMI_RADIUS, SUBDIVISIONS, SUBDIVISIONS / 4, 3 * SUBDIVISIONS / 4, Quat.Y90, color, Colors.White.bytes(), buf);
        hemiLine.uploadAndClear(buf);
    }

    void init() {
        hemiLine.init();
        putHemiLine();
        instrumentLines.init();
        instrumentCenters.init();
        coneFill.init();
        coneLines.init();
    }

    void dispose() {
        hemiLine.dispose();
        instrumentLines.dispose();
        instrumentCenters.dispose();
        coneFill.dispose();
        coneLines.dispose();
        ephemeris.cancel();
    }

    void render(MapView mv, Viewport vp, boolean cones, List<FOVLabels.Label> labels) {
        if (!hasEnabled())
            return;

        JHVTime time = mv.viewpoint().time;
        Position obsPosition = observer != null ? Spice.getCarrington(observer, time) : ephemeris.at(time);
        if (obsPosition == null || !(obsPosition.distance > 0))
            return;
        if (cones) {
            renderCones(vp, obsPosition, labels);
            return;
        }

        Transform.pushView();
        Transform.rotateViewInverse(obsPosition.toQuat());

        hemiLine.renderLine(vp, LINEWIDTH_FOV);
        double[] rot;
        if (isSOLO && null != (rot = Spice.getRotationEuler("SOLO_SRF", "SOLO_IAU_SUN_2009", time))) {
            // Default SOLO pointing is normal to orbit
            // rot = Spice.getRotationEuler("SOLO_EQUAT_NORM", "SOLO_ORBIT_NORM", time)
            Transform.rotateView(Quat.createAxisZ(rot[2]));
        }

        SdfTextRenderer renderer = GLText.renderer();
        renderer.setColor(Colors.WhiteFloat);
        renderer.begin3DRendering();
        renderer.setSurfacePut();

        instruments.forEach(instrument -> {
            if (instrument.flyingAt(time))
                instrument.putGeometry(obsPosition.distance, LINEWIDTH_FOV, color, renderer, lineBuf, centerBuf);
        });

        instrumentCenters.uploadAndClear(centerBuf);
        instrumentCenters.renderPoints(ViewportMath.getPixelFactor(vp, mv.cameraWidth(vp)));
        instrumentLines.uploadAndClear(lineBuf);
        instrumentLines.renderLine(vp, LINEWIDTH_FOV);

        renderer.setDirectPut();
        renderer.end3DRendering();

        Transform.popView();
    }

    /**
     * Each enabled field as a translucent solid from the spacecraft (FOVCone), outlined, with its
     * label's anchor handed to {@code labels} for the screen-space pass. World coordinates: no
     * view rotation, the frame model layers are drawn in.
     */
    private void renderCones(Viewport vp, Position obsPosition, List<FOVLabels.Label> labels) {
        JHVTime time = obsPosition.time;
        double[] o = world(obsPosition);
        double[] north = world(new Position(time, 1, 0, Math.PI / 2));
        byte[] fill = fade(color, FILL_ALPHA), side = fade(color, SIDE_ALPHA), line = fade(color, LINE_ALPHA);
        for (FOVInstrument instrument : instruments) {
            if (!instrument.isEnabled() || !instrument.flyingAt(time))
                continue;
            FOVCone cone = instrument.cone(o, north, sideVector(instrument.side(), obsPosition, o, north));
            if (cone == null)
                continue;
            putCone(cone, fill, side, line);
            labels.add(new FOVLabels.Label(name + ' ' + instrument, cone.labelAnchor(), color));
        }
        coneFill.uploadAndClear(fillBuf);
        coneLines.uploadAndClear(coneLineBuf);
        // Translucent: drawn without writing depth, so fields behind one another all show.
        GL.glDepthMask(false);
        coneFill.renderShape(GL.TRIANGLES);
        GL.glDepthMask(true);
        coneLines.renderLine(vp, LINEWIDTH_FOV);
    }

    /** The direction an off-Sun field points, or null for a centred one or when it is unknown. */
    @Nullable
    private double[] sideVector(FOVInstrument.Side side, Position obsPosition, double[] o, double[] north) {
        return switch (side) {
            case CENTRED -> null;
            case EAST -> FOVCone.cross(o, north); // (toward the observer) x north
            case EARTH -> FOVCone.sub(world(Sun.getEarth(obsPosition.time)), o);
            case RAM -> {
                long dt = 1800_000L;
                Position a = position(new JHVTime(obsPosition.time.milli - dt)), b = position(new JHVTime(obsPosition.time.milli + dt));
                yield a == null || b == null ? null : FOVCone.sub(world(b), world(a));
            }
        };
    }

    @Nullable
    private Position position(JHVTime t) {
        return observer != null ? Spice.getCarrington(observer, t) : ephemeris.at(t);
    }

    private static double[] world(Position p) {
        Vec3 v = ModelPlacement.toWorld(p);
        return new double[]{v.x, v.y, v.z};
    }

    private void putCone(FOVCone cone, byte[] fill, byte[] side, byte[] line) {
        double[][] out = cone.outer;
        int n = out.length;
        // the sides, from the spacecraft
        for (int i = 0; i < n; i++) {
            tri(cone.apex, out[i], out[(i + 1) % n], side);
        }
        // the far end: a disk, or a ring with the inner hole
        if (cone.inner == null) {
            double[] c = cone.labelAnchor();
            for (int i = 0; i < n; i++)
                tri(c, out[i], out[(i + 1) % n], fill);
        } else {
            double[][] in = cone.inner;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                tri(in[i], out[i], out[j], fill);
                tri(in[i], out[j], in[j], fill);
            }
            loop(in, line);
        }
        loop(out, line);
        int step = Math.max(1, n / 4);
        for (int i = 0; i < n; i += step) { // the edges from the spacecraft
            coneLineBuf.startLine((float) cone.apex[0], (float) cone.apex[1], (float) cone.apex[2], 1, line);
            coneLineBuf.putVertex((float) out[i][0], (float) out[i][1], (float) out[i][2], 1, line);
            coneLineBuf.endLine();
        }
    }

    private void tri(double[] a, double[] b, double[] c, byte[] col) {
        fillBuf.putVertex((float) a[0], (float) a[1], (float) a[2], 1, col);
        fillBuf.putVertex((float) b[0], (float) b[1], (float) b[2], 1, col);
        fillBuf.putVertex((float) c[0], (float) c[1], (float) c[2], 1, col);
    }

    private void loop(double[][] pts, byte[] col) {
        for (int i = 0; i <= pts.length; i++) {
            double[] p = pts[i % pts.length];
            if (i == 0)
                coneLineBuf.startLine((float) p[0], (float) p[1], (float) p[2], 1, col);
            else
                coneLineBuf.putVertex((float) p[0], (float) p[1], (float) p[2], 1, col);
        }
        coneLineBuf.endLine();
    }

    /** Premultiplied, as every colour here is: each channel scaled with the alpha. */
    static byte[] fade(byte[] c, double alpha) {
        byte[] f = new byte[4];
        for (int i = 0; i < 4; i++)
            f[i] = (byte) Math.round((c[i] & 0xff) * alpha);
        return f;
    }

    private static final double FILL_ALPHA = 0.22;
    private static final double SIDE_ALPHA = 0.10;
    private static final double LINE_ALPHA = 0.9;

    private boolean hasEnabled() {
        for (FOVInstrument instrument : instruments) {
            if (instrument.isEnabled())
                return true;
        }
        return false;
    }

    private static double control2Center(double v) { // v in arcmin
        return Math.tan(v * (Math.PI / 180. / 60.));
    }

    double getCenterX() {
        return centerX;
    }

    double getCenterY() {
        return centerY;
    }

    void setCenterX(double centerX) {
        this.centerX = centerX;
        instruments.forEach(instrument -> instrument.setCenterX(control2Center(centerX)));
        DisplayController.display();
    }

    void setCenterY(double centerY) {
        this.centerY = centerY;
        instruments.forEach(instrument -> instrument.setCenterY(control2Center(centerY)));
        DisplayController.display();
    }

    JSONObject toJson() {
        JSONObject jo = new JSONObject();
        jo.put("centerX", centerX);
        jo.put("centerY", centerY);

        for (FOVInstrument instrument : instruments)
            jo.put(instrument.toString(), instrument.isEnabled());
        return jo;
    }

    @Override
    public String toString() {
        return name;
    }

}
