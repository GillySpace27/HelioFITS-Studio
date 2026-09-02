package org.helioviewer.jhv.layers;

import java.util.List;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.GridType;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.WarpGeometry;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.display.ViewportMath;
import org.helioviewer.jhv.layers.grid.FlatGrid;
import org.helioviewer.jhv.layers.grid.GridLabel;
import org.helioviewer.jhv.layers.grid.ReferenceSurfaces;
import org.helioviewer.jhv.layers.grid.GridMath;
import org.helioviewer.jhv.layers.grid.HelioradialGrid;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.opengl.GLSLLine;
import org.helioviewer.jhv.opengl.GLSLShape;
import org.helioviewer.jhv.opengl.GLText;
import org.helioviewer.jhv.opengl.Transform;
import org.helioviewer.jhv.opengl.text.SdfTextRenderer;

import org.json.JSONObject;

public final class GridLayer extends AbstractLayer {

    private static final double RADIAL_UNIT = Sun.Radius;
    private static final double RADIAL_STEP = 15;
    private static final double RADIAL_UNIT_FAR = Sun.MeanEarthDistance / 10;
    private static final double RADIAL_STEP_FAR = 45;
    private static final float[] R_LABEL_POS = {(float) (2 * RADIAL_UNIT), (float) (8 * RADIAL_UNIT), (float) (24 * RADIAL_UNIT)};
    private static final float[] R_LABEL_POS_FAR = {(float) (2 * RADIAL_UNIT_FAR), (float) (8 * RADIAL_UNIT_FAR), (float) (24 * RADIAL_UNIT_FAR)};
    public static final double GRID_STEP_MIN = 5;
    public static final double GRID_STEP_MAX = 90;
    public static final double GRID_STEP = 0.1;
    public static final double GRID_LINE_SCALE_MIN = 0.5;
    public static final double GRID_LINE_SCALE_MAX = 5;
    public static final double GRID_LABEL_SIZE_MIN = 8;
    public static final double GRID_LABEL_SIZE_MAX = 48;
    public static final double GRID_LABEL_SIZE_REF = 22;

    // height of text in solar radii
    private static final float textScale = GridLabel.textScale;
    private static final double LINEWIDTH = GridMath.LINEWIDTH;
    private static final double LINEWIDTH_THICK = 2 * LINEWIDTH;
    private static final double LINEWIDTH_EARTH = LINEWIDTH;
    private static final double LINEWIDTH_AXES = 2 * LINEWIDTH;
    // private static final double PLANETEXT_Z = 0.01;

    private double lonStep = 30;
    private double latStep = 20;
    private boolean gridNeedsInit = true;

    private boolean showAxis = true;
    private boolean showLabels = true;
    private boolean showRadial = false;
    // Reference surfaces: where a line of sight is assumed to have originated, and the plane the
    // planets orbit in. Both off by default -- they are annotations on the geometry, not part of
    // the picture, and drawing them unasked would clutter every ordinary view.
    private boolean showThomson = false;
    private boolean showEcliptic = false;
    private boolean showCelestial = false;
    private Colors.NamedColor thomsonColor = Colors.NamedColor.Cyan;
    private Colors.NamedColor eclipticColor = Colors.NamedColor.Yellow;
    private Colors.NamedColor celestialColor = Colors.NamedColor.Magenta;
    // Same affordances the grid itself has, per surface: with two wireframes and a grid overlaid
    // on the imagery, colour alone does not separate them -- opacity is what stops a dense mesh
    // burying the data, and width is what keeps a sparse one visible over bright corona.
    private double thomsonAlpha = 0.7;
    private double eclipticAlpha = 0.7;
    private double celestialAlpha = 0.7;
    private double thomsonLineScale = 1;
    private double eclipticLineScale = 1;
    private double celestialLineScale = 1;
    // Planets, drawn here beside the Earth marker rather than by ViewpointLayer, which only
    // renders them in its Heliosphere camera mode and so charges a camera for the privilege.
    private boolean showPlanets = false;
    private boolean showPlanetOrbits = true;
    private boolean showPlanetNames = true;
    private double planetOrbitAlpha = 0.5;
    /**
     * On by default, because this is the frame everything else in the scene is in.
     *
     * <p>With it on, Earth lands exactly on the observer marker at the pole of the Thomson sphere,
     * and the planets sit where the imagery projects them, so a planet bright in a coronagraph
     * frame coincides with its marker. The whole scene, observer and imagery included, turns
     * together with the Sun's rotation, so relative motion between them is still each planet's
     * true motion relative to Earth.
     *
     * <p>Off, the placement is inertial: each planet moves at its own orbital rate against a fixed
     * background, which is the view for watching Mercury lap Earth. The cost is that Earth no
     * longer coincides with the observer marker, and nothing registers against the imagery.
     */
    private boolean planetsFollowRotation = true;

    private double thomsonDensity = 1;
    private double eclipticDensity = 1;
    private double celestialDensity = 1;

    private Colors.NamedColor gridColor = Colors.NamedColor.ReducedGreen;
    private double gridAlpha = 0.47;
    private byte[] gridColorBytes = Colors.bytes(gridColor.awtColor(), gridAlpha); // honors a non-1 default alpha
    private double labelAlpha = 1;
    private double gridLineScale = 1;
    private double gridLabelSize = 16;
    private double gridLabelAngle = 148;

    private final GLSLShape earthPoint = new GLSLShape(false);

    /**
     * A dot at the observer's own position in space, as opposed to {@link #earthPoint}, which
     * marks where the observer's direction meets the photosphere.
     *
     * <p>Drawn only in 3D Helioradial. That is the one view where it says something: the scene
     * can be orbited, so the observer stops being the point you are looking from and becomes a
     * place in the picture. It is also the check that view most needs -- the Thomson sphere has
     * the Sun-observer line as its diameter, so the modelled surface must pass exactly through
     * this dot, and the surface reaching it (or visibly failing to, when the loaded field stops
     * short of 1 au) is a free verification of the whole placement model. Face-on and in the
     * flat projections it would only ever sit on disk centre, which is true but useless.
     *
     * <p>Rebuilt every frame rather than scaled: the vertex stage warps the raw vertex before
     * the MVP, so the buffer has to carry the true heliocentric distance or the warp would be
     * handed a radius of 1 and place the observer at the limb.
     */
    private final GLSLShape observerPoint = new GLSLShape(false);
    private final BufVertex observerBuf = new BufVertex(GLSLShape.stride); // one vertex
    private static final byte[] OBSERVER_COLOR = Colors.Blue;
    // As a fraction of the camera width, so the dot keeps a constant size on screen: the point
    // shader multiplies by pixels-per-scene-unit, and this view's camera spans hundreds of solar
    // radii, where earthPoint's fixed 0.02 scene units would be a small fraction of one pixel.
    private static final double OBSERVER_POINT_FRACTION = 0.012;
    // Rebuilt only when their inputs move, not every frame: the Thomson mesh depends on the
    // observer distance and the field size, the ecliptic on the time, and rebuilding a few
    // thousand vertices per frame for geometry that changes on a scrub is wasted work.
    private final GLSLShape planetPoints = new GLSLShape(false);
    private final BufVertex planetBuf = new BufVertex(32 * GLSLShape.stride);
    private final GLSLLine planetOrbitLine = new GLSLLine(false);
    private long planetOrbitsBuiltDay = Long.MIN_VALUE;
    private double planetOrbitsBuiltAlpha = -1;
    private boolean planetOrbitsBuiltFollow;
    private java.util.List<org.helioviewer.jhv.layers.grid.PlanetMarkers.Marker> planetMarkers = java.util.List.of();

    private final GLSLLine thomsonLine = new GLSLLine(false);
    private final GLSLLine eclipticLine = new GLSLLine(false);
    private final GLSLLine celestialLine = new GLSLLine(false);
    private double thomsonBuiltDistance = -1, thomsonBuiltOuter = -1;
    private byte[] thomsonBuiltColor;
    private double thomsonBuiltDensity = -1;
    private long eclipticBuiltTime = Long.MIN_VALUE;
    private double eclipticBuiltOuter = -1;
    private byte[] eclipticBuiltColor;
    private double eclipticBuiltDensity = -1;
    private double celestialBuiltDistance = -1, celestialBuiltOuter = -1;
    private byte[] celestialBuiltColor;
    private double celestialBuiltDensity = -1;

    private final GLSLLine axesLine = new GLSLLine(false);
    private final GLSLLine earthCircleLine = new GLSLLine(false);
    private final GLSLLine radialCircleLine = new GLSLLine(false);
    private final GLSLLine radialThickLine = new GLSLLine(false);
    private final GLSLLine radialCircleLineFar = new GLSLLine(false);
    private final GLSLLine radialThickLineFar = new GLSLLine(false);
    private final FlatGrid flatGrid = new FlatGrid();
    private final org.helioviewer.jhv.layers.grid.SkyGrid skyGrid = new org.helioviewer.jhv.layers.grid.SkyGrid();
    private final HelioradialGrid helioradialGrid = new HelioradialGrid();
    private final GLSLLine gridLine = new GLSLLine(false);

    private List<GridLabel> latLabels;
    private List<GridLabel.TransformedGridLabel> lonLabels;
    private final List<GridLabel> radialLabels;
    private final List<GridLabel> radialLabelsFar;

    @Override
    public void serialize(JSONObject jo) {
        jo.put("lonStep", lonStep);
        jo.put("latStep", latStep);
        jo.put("showAxis", showAxis);
        jo.put("showLabels", showLabels);
        jo.put("showRadial", showRadial);
        jo.put("type", Display.gridType);
        jo.put("color", gridColor.name());
        jo.put("alpha", gridAlpha);
        jo.put("labelAlpha", labelAlpha);
        jo.put("lineScale", gridLineScale);
        jo.put("labelSize", gridLabelSize);
        jo.put("labelAngle", gridLabelAngle);
        jo.put("showThomson", showThomson);
        jo.put("showEcliptic", showEcliptic);
        jo.put("showCelestial", showCelestial);
        jo.put("thomsonColor", thomsonColor.name());
        jo.put("eclipticColor", eclipticColor.name());
        jo.put("celestialColor", celestialColor.name());
        jo.put("thomsonAlpha", thomsonAlpha);
        jo.put("eclipticAlpha", eclipticAlpha);
        jo.put("celestialAlpha", celestialAlpha);
        jo.put("thomsonLineScale", thomsonLineScale);
        jo.put("eclipticLineScale", eclipticLineScale);
        jo.put("celestialLineScale", celestialLineScale);
        jo.put("showPlanets", showPlanets);
        jo.put("showPlanetOrbits", showPlanetOrbits);
        jo.put("showPlanetNames", showPlanetNames);
        jo.put("planetOrbitAlpha", planetOrbitAlpha);
        jo.put("planetsFollowRotation", planetsFollowRotation);
        jo.put("thomsonDensity", thomsonDensity);
        jo.put("eclipticDensity", eclipticDensity);
        jo.put("celestialDensity", celestialDensity);
    }

    private void deserialize(JSONObject jo) {
        lonStep = Math.clamp(jo.optDouble("lonStep", lonStep), GRID_STEP_MIN, GRID_STEP_MAX);
        latStep = Math.clamp(jo.optDouble("latStep", latStep), GRID_STEP_MIN, GRID_STEP_MAX);

        showAxis = jo.optBoolean("showAxis", showAxis);
        showLabels = jo.optBoolean("showLabels", showLabels);
        showRadial = jo.optBoolean("showRadial", showRadial);
        gridColor = Colors.NamedColor.parse(jo.optString("color", gridColor.name()), gridColor);
        gridAlpha = Math.clamp(jo.optDouble("alpha", gridAlpha), 0, 1);
        updateGridColorBytes();
        labelAlpha = Math.clamp(jo.optDouble("labelAlpha", labelAlpha), 0, 1);
        gridLineScale = Math.clamp(jo.optDouble("lineScale", gridLineScale), GRID_LINE_SCALE_MIN, GRID_LINE_SCALE_MAX);
        gridLabelSize = Math.clamp(jo.optDouble("labelSize", gridLabelSize), GRID_LABEL_SIZE_MIN, GRID_LABEL_SIZE_MAX);
        gridLabelAngle = jo.optDouble("labelAngle", gridLabelAngle);

        String strGridType = jo.optString("type", Display.gridType.toString());
        try {
            Display.setGridType(GridType.valueOf(strGridType));
        } catch (Exception ignore) {}
        showThomson = jo.optBoolean("showThomson", showThomson);
        showEcliptic = jo.optBoolean("showEcliptic", showEcliptic);
        showCelestial = jo.optBoolean("showCelestial", showCelestial);
        thomsonColor = Colors.NamedColor.parse(jo.optString("thomsonColor", thomsonColor.name()), thomsonColor);
        eclipticColor = Colors.NamedColor.parse(jo.optString("eclipticColor", eclipticColor.name()), eclipticColor);
        celestialColor = Colors.NamedColor.parse(jo.optString("celestialColor", celestialColor.name()), celestialColor);
        thomsonAlpha = Math.clamp(jo.optDouble("thomsonAlpha", thomsonAlpha), 0, 1);
        eclipticAlpha = Math.clamp(jo.optDouble("eclipticAlpha", eclipticAlpha), 0, 1);
        celestialAlpha = Math.clamp(jo.optDouble("celestialAlpha", celestialAlpha), 0, 1);
        thomsonLineScale = Math.clamp(jo.optDouble("thomsonLineScale", thomsonLineScale), 0.25, 4);
        eclipticLineScale = Math.clamp(jo.optDouble("eclipticLineScale", eclipticLineScale), 0.25, 4);
        celestialLineScale = Math.clamp(jo.optDouble("celestialLineScale", celestialLineScale), 0.25, 4);
        showPlanets = jo.optBoolean("showPlanets", showPlanets);
        showPlanetOrbits = jo.optBoolean("showPlanetOrbits", showPlanetOrbits);
        showPlanetNames = jo.optBoolean("showPlanetNames", showPlanetNames);
        planetOrbitAlpha = Math.clamp(jo.optDouble("planetOrbitAlpha", planetOrbitAlpha), 0, 1);
        planetsFollowRotation = jo.optBoolean("planetsFollowRotation", planetsFollowRotation);
        thomsonDensity = Math.clamp(jo.optDouble("thomsonDensity", thomsonDensity), 0.25, 4);
        eclipticDensity = Math.clamp(jo.optDouble("eclipticDensity", eclipticDensity), 0.25, 4);
        celestialDensity = Math.clamp(jo.optDouble("celestialDensity", celestialDensity), 0.25, 4);
    }

    public GridLayer(JSONObject jo) {
        if (jo != null)
            deserialize(jo);
        else
            setEnabled(true);

        latLabels = GridLabel.makeLatLabels(latStep);
        lonLabels = GridLabel.makeLonLabels(Display.gridType, lonStep);
        radialLabels = GridLabel.makeRadialLabels(0, RADIAL_STEP);
        radialLabelsFar = GridLabel.makeRadialLabels(Math.PI / 2, RADIAL_STEP_FAR);
    }

    @Override
    public void render(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx])
            return;
        if (gridNeedsInit) {
            GridMath.initGrid(gridLine, lonStep, latStep, gridColorBytes);
            gridNeedsInit = false;
        }

        Position viewpoint = mv.viewpoint();
        float ztext = 0;
        double pixFactor = ViewportMath.getPixelFactor(vp, mv.cameraWidth(vp));

        // correct order: grid lines -> Earth indicators -> axis -> grid labels -> radial grid
        Quat gridQuat = mv.gridType().toCarrington(viewpoint);

        Transform.pushView();
        Transform.rotateViewInverse(gridQuat);
        gridLine.renderLine(vp, LINEWIDTH * gridLineScale);
        Transform.popView();

        drawEarthCircles(vp, pixFactor, Sun.getEarth(viewpoint.time));
        if (mv.isHelioradial() && mv.rendersIn3D())
            drawObserverPoint(vp, pixFactor, viewpoint, mv.cameraWidth(vp));

        if (showPlanets)
            drawPlanets(vp, pixFactor, viewpoint, mv.cameraWidth(vp));
        if (showThomson)
            drawThomsonSphere(mv, vp, viewpoint);
        if (showCelestial)
            drawCelestialSphere(mv, vp, viewpoint);
        if (showEcliptic)
            drawEcliptic(mv, vp, viewpoint);

        if (showAxis)
            axesLine.renderLine(vp, LINEWIDTH_AXES);

        if (showLabels) {
            Transform.pushView();
            Transform.rotateViewInverse(gridQuat);
            // The lat/lon grid sits on the r = 1 sphere and its LINES are warped by the vertex
            // stage; the labels are SDF text, which has no warp splice, so they would be drawn
            // at raw radius 1 while their lines sit at the warped limb. At a 180 Rsun field that
            // is a 29x displacement: the labels collapse into an unreadable knot at the centre.
            // One uniform scale fixes it, because every label on a sphere shares a radius.
            double labelScale = mv.isHelioradial() ? warpFactorAtLimb(mv, vp) : 1;
            if (labelScale != 1)
                Transform.scaleView(labelScale);
            drawGridText(ztext);
            Transform.popView();
        }

        if (showRadial) {
            Transform.pushView();
            Transform.rotateViewInverse(viewpoint.toQuat());
            {
                if (viewpoint.distance > 100 * Sun.MeanEarthDistance) {
                    radialCircleLineFar.renderLine(vp, LINEWIDTH);
                    radialThickLineFar.renderLine(vp, LINEWIDTH_THICK);
                    if (showLabels)
                        drawRadialGridText(radialLabelsFar, ztext, R_LABEL_POS_FAR, Colors.fade(Colors.MiddleGrayFloat, labelAlpha));
                } else {
                    radialCircleLine.renderLine(vp, LINEWIDTH);
                    radialThickLine.renderLine(vp, LINEWIDTH_THICK);
                    if (showLabels)
                        drawRadialGridText(radialLabels, ztext, R_LABEL_POS, Colors.fade(Colors.MiddleGrayFloat, labelAlpha));
                }
            }
            Transform.popView();
        }

        // Helioradial now renders here rather than through renderScale, and the rings and spokes
        // that used to come from the flat path went with it. Emitted as world-space geometry in
        // the plane of sky, so the vertex-stage warp compresses them along with the imagery.
        if (mv.isHelioradial()) {
            Transform.pushView();
            Transform.rotateViewInverse(viewpoint.toQuat());
            helioradialGrid.renderWorld(mv, vp, showLabels, lonStep, gridColorBytes, gridLineScale, Colors.fade(Colors.WhiteFloat, labelAlpha), gridLabelSize, gridLabelAngle);
            Transform.popView();
        }
    }

    /**
     * How far the warp moves the solar limb, as a multiplier on radius 1.
     *
     * <p>Everything on the r = 1 sphere is displaced by this same factor, so it doubles as the
     * scale for label geometry that bypasses the warp shader.
     */
    private static double warpFactorAtLimb(MapView mv, Viewport vp) {
        MapScale scale = mv.scale(vp);
        double outer = scale.warpOuterRadius();
        return outer > 0 ? WarpGeometry.warpRadius(scale, 1, outer) : 1;
    }

    @Override
    public void renderScale(MapView mv, Viewport vp) {
        if (!isVisible[vp.idx])
            return;
        if (mv.isHelioradial())
            helioradialGrid.render(mv, vp, showLabels, lonStep, gridColorBytes, gridLineScale, Colors.fade(Colors.WhiteFloat, labelAlpha), gridLabelSize, gridLabelAngle);
        // The observer sky is a zenithal projection, so its grid is rings and spokes about the aim
        // rather than a ruling of the page. See SkyGrid for why a cartesian grid there is drawable
        // but meaningless.
        else if (mv.isObserverSky())
            skyGrid.render(mv, vp, showLabels, lonStep, gridColorBytes, gridLineScale, Colors.fade(Colors.WhiteFloat, labelAlpha), gridLabelSize, gridLabelAngle);
        else
            flatGrid.render(mv, vp, showLabels, gridColorBytes, gridLineScale, Colors.fade(Colors.WhiteFloat, labelAlpha), gridLabelSize);
    }

    /**
     * The field the reference surfaces should span: the projection's outer radius where there is
     * one, and the visible camera width otherwise, so they do not stop short of the picture.
     */
    private static double referenceOuterRadius(MapView mv, Viewport vp) {
        double outer = mv.scale(vp).warpOuterRadius();
        return outer > 0 ? outer : Math.max(mv.cameraWidth(vp), 2);
    }

    /**
     * Planet markers, their names and their orbits, in the display frame.
     *
     * <p>Positions are rebuilt every frame because they move with the playhead and cost one
     * ephemeris query each. Orbits are not: measuring a period walks the ephemeris dozens of times
     * per planet, and an orbit does not visibly change within a day, so they are cached by day.
     */
    private void drawPlanets(Viewport vp, double factor, Position viewpoint, double cameraWidth) {
        if (cameraWidth <= 0)
            return;
        planetMarkers = org.helioviewer.jhv.layers.grid.PlanetMarkers.positions(viewpoint.time, planetsFollowRotation);
        if (planetMarkers.isEmpty())
            return;

        long day = viewpoint.time.milli / 86400_000L;
        if (showPlanetOrbits && (day != planetOrbitsBuiltDay || planetOrbitAlpha != planetOrbitsBuiltAlpha
                || planetsFollowRotation != planetOrbitsBuiltFollow)) {
            org.helioviewer.jhv.layers.grid.PlanetMarkers.buildOrbits(planetOrbitLine, viewpoint.time, planetOrbitAlpha, planetsFollowRotation);
            planetOrbitsBuiltFollow = planetsFollowRotation;
            planetOrbitsBuiltDay = day;
            planetOrbitsBuiltAlpha = planetOrbitAlpha;
        }
        if (showPlanetOrbits)
            planetOrbitLine.renderLine(vp, LINEWIDTH);

        planetBuf.clear();
        float size = (float) (OBSERVER_POINT_FRACTION * cameraWidth);
        for (org.helioviewer.jhv.layers.grid.PlanetMarkers.Marker m : planetMarkers)
            planetBuf.putVertex((float) m.position().x, (float) m.position().y, (float) m.position().z, size, m.color());
        planetPoints.setVertex(planetBuf);

        // Markers are positions, not surfaces: depth-testing them against the modelled surface
        // would hide a planet exactly when it passes behind it, which is the case worth seeing.
        GL.glDisable(GL.DEPTH_TEST);
        planetPoints.renderPoints(factor);
        if (showPlanetNames)
            drawPlanetNames();
        GL.glEnable(GL.DEPTH_TEST);
    }

    /** Names offset outward along the radius, so the inner planets do not stack their labels. */
    private void drawPlanetNames() {
        SdfTextRenderer renderer = GLText.renderer();
        renderer.setColor(Colors.LightGrayFloat);
        float scale = (float) (textScale / renderer.getFontSize());

        GL.glDisable(GL.CULL_FACE);
        renderer.begin3DRendering();
        for (org.helioviewer.jhv.layers.grid.PlanetMarkers.Marker m : planetMarkers) {
            Vec3 p = m.position();
            double r = Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z);
            if (r <= 0)
                continue;
            double off = 1.03;
            renderer.draw(m.label(), (float) (p.x * off), (float) (p.y * off), (float) (p.z * off),
                    (float) (r * scale));
        }
        renderer.end3DRendering();
        GL.glEnable(GL.CULL_FACE);
    }

    private void drawThomsonSphere(MapView mv, Viewport vp, Position viewpoint) {
        double outer = referenceOuterRadius(mv, vp);
        byte[] color = Colors.bytes(thomsonColor.awtColor(), thomsonAlpha);
        if (viewpoint.distance != thomsonBuiltDistance || outer != thomsonBuiltOuter
                || !java.util.Arrays.equals(color, thomsonBuiltColor) || thomsonDensity != thomsonBuiltDensity) {
            ReferenceSurfaces.buildThomsonSphere(thomsonLine, viewpoint.distance, outer, color, thomsonDensity);
            thomsonBuiltDistance = viewpoint.distance;
            thomsonBuiltOuter = outer;
            thomsonBuiltColor = color;
            thomsonBuiltDensity = thomsonDensity;
        }
        // The observer's frame, like the observer dot and the radial grid: the surface is defined
        // by where the telescope is, so it has to be swung with it rather than left in Carrington.
        Transform.pushView();
        Transform.rotateViewInverse(viewpoint.toQuat());
        thomsonLine.renderLine(vp, LINEWIDTH * thomsonLineScale);
        Transform.popView();
    }

    private void drawCelestialSphere(MapView mv, Viewport vp, Position viewpoint) {
        double outer = referenceOuterRadius(mv, vp);
        byte[] color = Colors.bytes(celestialColor.awtColor(), celestialAlpha);
        if (viewpoint.distance != celestialBuiltDistance || outer != celestialBuiltOuter
                || !java.util.Arrays.equals(color, celestialBuiltColor) || celestialDensity != celestialBuiltDensity) {
            ReferenceSurfaces.buildCelestialSphere(celestialLine, viewpoint.distance, outer, color, celestialDensity);
            celestialBuiltDistance = viewpoint.distance;
            celestialBuiltOuter = outer;
            celestialBuiltColor = color;
            celestialBuiltDensity = celestialDensity;
        }
        // Same frame as the Thomson sphere: both surfaces are defined relative to the observer, so
        // both swing with it rather than sitting fixed in Carrington.
        Transform.pushView();
        Transform.rotateViewInverse(viewpoint.toQuat());
        celestialLine.renderLine(vp, LINEWIDTH * celestialLineScale);
        Transform.popView();
    }

    private void drawEcliptic(MapView mv, Viewport vp, Position viewpoint) {
        double outer = referenceOuterRadius(mv, vp);
        byte[] color = Colors.bytes(eclipticColor.awtColor(), eclipticAlpha);
        long t = viewpoint.time.milli;
        if (t != eclipticBuiltTime || outer != eclipticBuiltOuter
                || !java.util.Arrays.equals(color, eclipticBuiltColor) || eclipticDensity != eclipticBuiltDensity) {
            ReferenceSurfaces.buildEcliptic(eclipticLine, viewpoint.time, outer, color, eclipticDensity);
            eclipticBuiltTime = t;
            eclipticBuiltOuter = outer;
            eclipticBuiltColor = color;
            eclipticBuiltDensity = eclipticDensity;
        }
        // No rotation: buildEcliptic already emits display-frame geometry, having carried the
        // inertial Earth directions across with the same angle the planet markers use. So the plane
        // passes through the observer marker by construction rather than by coincidence.
        //
        // One consequence worth naming: with "planets follow solar rotation" turned off, the planet
        // markers move to a frozen layout that is deliberately not registered to solar longitude,
        // and the Earth marker then sits off this plane. The plane is the physical one and stays
        // registered; it is the layout that has been detached.
        eclipticLine.renderLine(vp, LINEWIDTH * eclipticLineScale);
    }

    // The observer sits at (0, 0, distance) in its own frame, so rotating into that frame is all
    // it takes to place it -- the same trick drawEarthCircles uses for the sub-observer point.
    private void drawObserverPoint(Viewport vp, double factor, Position viewpoint, double cameraWidth) {
        if (viewpoint.distance <= 1 || cameraWidth <= 0)
            return;
        observerBuf.putVertex(0, 0, (float) viewpoint.distance,
                (float) (OBSERVER_POINT_FRACTION * cameraWidth), OBSERVER_COLOR);
        observerPoint.setVertex(observerBuf);

        Transform.pushView();
        Transform.rotateViewInverse(viewpoint.toQuat());
        // The dot is a position, not a surface: depth-testing it against the modelled surface
        // would hide it exactly when the surface curves past it, which is the case worth seeing.
        GL.glDisable(GL.DEPTH_TEST);
        observerPoint.renderPoints(factor);
        GL.glEnable(GL.DEPTH_TEST);
        Transform.popView();
    }

    private void drawEarthCircles(Viewport vp, double factor, Position p) {
        Transform.pushView();
        Transform.rotateViewInverse(p.toQuat());

        earthCircleLine.renderLine(vp, LINEWIDTH_EARTH);
        earthPoint.renderPoints(factor);

        Transform.popView();
    }

    private void drawRadialGridText(List<GridLabel> labels, float z, float[] labelPos, float[] color) {
        SdfTextRenderer renderer = GLText.renderer();
        renderer.setColor(color);
        float textScaleFactor = (float) (textScale * gridLabelSize / GRID_LABEL_SIZE_REF / renderer.getFontSize());
        float fuzz = 0.75f;

        GL.glDisable(GL.CULL_FACE);
        renderer.begin3DRendering();
        for (float rsize : labelPos) {
            labels.forEach(label -> renderer.draw(label.txt, rsize * label.x, rsize * label.y, z, fuzz * rsize * textScaleFactor));
        }
        renderer.end3DRendering();
        GL.glEnable(GL.CULL_FACE);
    }

    private void drawGridText(float z) {
        SdfTextRenderer renderer = GLText.renderer();
        renderer.setColor(Colors.fade(Colors.WhiteFloat, labelAlpha));
        // the scale factor has to be divided by the current font size
        float textScaleFactor = (float) (textScale * gridLabelSize / GRID_LABEL_SIZE_REF / renderer.getFontSize());

        renderer.begin3DRendering();

        // need flushes for state toggle
        lonLabels.forEach(lonLabel -> renderer.draw(lonLabel.txt, lonLabel.origin, lonLabel.basisX, lonLabel.basisY, textScaleFactor));
        renderer.flush();

        GL.glDisable(GL.CULL_FACE);
        latLabels.forEach(label -> renderer.draw(label.txt, label.x, label.y, z, textScaleFactor));
        renderer.flush();
        GL.glEnable(GL.CULL_FACE);

        renderer.end3DRendering();
    }

    @Override
    public void init() {
        gridLine.init();
        GridMath.initGrid(gridLine, lonStep, latStep, gridColorBytes);
        gridNeedsInit = false;

        axesLine.init();
        GridMath.initAxes(axesLine);

        earthCircleLine.init();
        GridMath.initEarthCircles(earthCircleLine);
        earthPoint.init();
        observerPoint.init();
        planetPoints.init();
        planetOrbitLine.init();
        thomsonLine.init();
        eclipticLine.init();
        celestialLine.init();
        GridMath.initEarthPoint(earthPoint);

        radialCircleLine.init();
        radialThickLine.init();
        GridMath.initRadialCircles(radialCircleLine, radialThickLine, RADIAL_UNIT, RADIAL_STEP);
        radialCircleLineFar.init();
        radialThickLineFar.init();
        GridMath.initRadialCircles(radialCircleLineFar, radialThickLineFar, RADIAL_UNIT_FAR, RADIAL_STEP_FAR);

        flatGrid.init();
        skyGrid.init();
        helioradialGrid.init();
    }

    @Override
    public void dispose() {
        gridLine.dispose();
        axesLine.dispose();
        earthCircleLine.dispose();
        earthPoint.dispose();
        observerPoint.dispose();
        planetPoints.dispose();
        planetOrbitLine.dispose();
        thomsonLine.dispose();
        eclipticLine.dispose();
        celestialLine.dispose();
        radialCircleLine.dispose();
        radialThickLine.dispose();
        radialCircleLineFar.dispose();
        radialThickLineFar.dispose();
        flatGrid.dispose();
        skyGrid.dispose();
        helioradialGrid.dispose();
    }

    @Override
    public void remove() {
        dispose();
    }

    @Override
    public String getName() {
        return "Grid";
    }

    public boolean isShowAxis() {
        return showAxis;
    }

    public void setShowAxis(boolean _showAxis) {
        showAxis = _showAxis;
        DisplayController.display();
    }

    public boolean isShowLabels() {
        return showLabels;
    }

    public void setShowLabels(boolean _showLabels) {
        showLabels = _showLabels;
        DisplayController.display();
    }

    public boolean isShowRadial() {
        return showRadial;
    }

    public void setShowRadial(boolean _showRadial) {
        showRadial = _showRadial;
        DisplayController.display();
    }

    public double getLonStep() {
        return lonStep;
    }

    public void setLonStep(double _lonStep) {
        lonStep = _lonStep;
        lonLabels = GridLabel.makeLonLabels(Display.gridType, lonStep);
        gridNeedsInit = true;
        DisplayController.display();
    }

    public double getLatStep() {
        return latStep;
    }

    public void setLatStep(double _latStep) {
        latStep = _latStep;
        latLabels = GridLabel.makeLatLabels(latStep);
        gridNeedsInit = true;
        DisplayController.display();
    }

    public void setGridType(GridType gridType) {
        Display.setGridType(gridType);
        lonLabels = GridLabel.makeLonLabels(gridType, lonStep);
        DisplayController.display();
    }

    public Colors.NamedColor getGridColor() {
        return gridColor;
    }

    public void setGridColor(Colors.NamedColor _gridColor) {
        gridColor = _gridColor;
        updateGridColorBytes();
        gridNeedsInit = true;
        DisplayController.display();
    }

    public double getGridAlpha() {
        return gridAlpha;
    }

    public void setGridAlpha(double _gridAlpha) {
        gridAlpha = Math.clamp(_gridAlpha, 0, 1);
        updateGridColorBytes();
        gridNeedsInit = true;
        DisplayController.display();
    }

    public double getLabelAlpha() {
        return labelAlpha;
    }

    public void setLabelAlpha(double _labelAlpha) {
        labelAlpha = Math.clamp(_labelAlpha, 0, 1);
        DisplayController.display();
    }

    public double getGridLineScale() {
        return gridLineScale;
    }

    public void setGridLineScale(double _gridLineScale) {
        gridLineScale = Math.clamp(_gridLineScale, GRID_LINE_SCALE_MIN, GRID_LINE_SCALE_MAX);
        DisplayController.display();
    }

    public double getGridLabelSize() {
        return gridLabelSize;
    }

    public void setGridLabelSize(double _gridLabelSize) {
        gridLabelSize = Math.clamp(_gridLabelSize, GRID_LABEL_SIZE_MIN, GRID_LABEL_SIZE_MAX);
        DisplayController.display();
    }

    public double getGridLabelAngle() {
        return gridLabelAngle;
    }

    public void setGridLabelAngle(double _gridLabelAngle) {
        gridLabelAngle = _gridLabelAngle;
        DisplayController.display();
    }

    public boolean isShowThomson() {
        return showThomson;
    }

    public void setShowThomson(boolean v) {
        showThomson = v;
        DisplayController.display();
    }

    public boolean isShowEcliptic() {
        return showEcliptic;
    }

    public void setShowEcliptic(boolean v) {
        showEcliptic = v;
        DisplayController.display();
    }

    public boolean isShowCelestial() {
        return showCelestial;
    }

    public void setShowCelestial(boolean v) {
        showCelestial = v;
        DisplayController.display();
    }

    public Colors.NamedColor getThomsonColor() {
        return thomsonColor;
    }

    public void setThomsonColor(Colors.NamedColor c) {
        thomsonColor = c;
        DisplayController.display();
    }

    public Colors.NamedColor getEclipticColor() {
        return eclipticColor;
    }

    public void setEclipticColor(Colors.NamedColor c) {
        eclipticColor = c;
        DisplayController.display();
    }

    public Colors.NamedColor getCelestialColor() {
        return celestialColor;
    }

    public void setCelestialColor(Colors.NamedColor c) {
        celestialColor = c;
        DisplayController.display();
    }

    public boolean isShowPlanets() {
        return showPlanets;
    }

    public void setShowPlanets(boolean v) {
        showPlanets = v;
        DisplayController.display();
    }

    public boolean isShowPlanetOrbits() {
        return showPlanetOrbits;
    }

    public void setShowPlanetOrbits(boolean v) {
        showPlanetOrbits = v;
        DisplayController.display();
    }

    public boolean isShowPlanetNames() {
        return showPlanetNames;
    }

    public void setShowPlanetNames(boolean v) {
        showPlanetNames = v;
        DisplayController.display();
    }

    public boolean isPlanetsFollowRotation() {
        return planetsFollowRotation;
    }

    public void setPlanetsFollowRotation(boolean v) {
        planetsFollowRotation = v;
        DisplayController.display();
    }

    public double getPlanetOrbitAlpha() {
        return planetOrbitAlpha;
    }

    public void setPlanetOrbitAlpha(double v) {
        planetOrbitAlpha = v;
        DisplayController.display();
    }

    public double getThomsonAlpha() {
        return thomsonAlpha;
    }

    public void setThomsonAlpha(double v) {
        thomsonAlpha = v;
        DisplayController.display();
    }

    public double getEclipticAlpha() {
        return eclipticAlpha;
    }

    public void setEclipticAlpha(double v) {
        eclipticAlpha = v;
        DisplayController.display();
    }

    public double getCelestialAlpha() {
        return celestialAlpha;
    }

    public void setCelestialAlpha(double v) {
        celestialAlpha = v;
        DisplayController.display();
    }

    public double getThomsonLineScale() {
        return thomsonLineScale;
    }

    public void setThomsonLineScale(double v) {
        thomsonLineScale = v;
        DisplayController.display();
    }

    public double getEclipticLineScale() {
        return eclipticLineScale;
    }

    public void setEclipticLineScale(double v) {
        eclipticLineScale = v;
        DisplayController.display();
    }

    public double getCelestialLineScale() {
        return celestialLineScale;
    }

    public void setCelestialLineScale(double v) {
        celestialLineScale = v;
        DisplayController.display();
    }

    public double getThomsonDensity() {
        return thomsonDensity;
    }

    public void setThomsonDensity(double v) {
        thomsonDensity = v;
        DisplayController.display();
    }

    public double getEclipticDensity() {
        return eclipticDensity;
    }

    public void setEclipticDensity(double v) {
        eclipticDensity = v;
        DisplayController.display();
    }

    public double getCelestialDensity() {
        return celestialDensity;
    }

    public void setCelestialDensity(double v) {
        celestialDensity = v;
        DisplayController.display();
    }

    private void updateGridColorBytes() {
        gridColorBytes = gridAlpha == 1 ? gridColor.bytes() : Colors.bytes(gridColor.awtColor(), gridAlpha);
    }

}
