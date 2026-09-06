package org.helioviewer.jhv.display;

import org.helioviewer.jhv.layers.ImageLayers;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.app.Settings;

public final class Display {

    public static MapMode mode = MapMode.Orthographic;
    public static boolean multiview = false;
    public static boolean whiteBackground = false;

    public static void setMapMode(MapMode _mode) {
        setMapMode(_mode, false);
    }

    /**
     * Switch projection. With {@code preserveDiskSize}, the solar disk keeps its on-screen
     * diameter across the switch (solved per viewport through the zoom), so stepping
     * Orthographic -> Helioradial -> HPC jumps minimally; the unrolled band and the surface
     * map have no centered disk, so switches involving them fall back to the plain camera
     * reset. Session restore passes false: there is no previous view to be continuous with,
     * and the saved state should reproduce the canonical framing it was written under.
     */
    public static void setMapMode(MapMode _mode, boolean preserveDiskSize) {
        MapMode oldMode = mode;
        mode = _mode;
        // A failure under one projection says nothing about the next, so let it be reported again.
        org.helioviewer.jhv.opengl.RenderGuard.reset();
        double[] keep = preserveDiskSize ? captureLimbFractions(oldMode) : null;
        resetViewportZoom();
        DisplayController.resetCameras();
        restoreLimbFractions(_mode, keep);
    }

    // The limb's current screen fraction per viewport, or null when the old mode has no
    // centered disk to be continuous with. Captured before the camera reset, against the
    // zooms and camera the user is actually looking at.
    private static double[] captureLimbFractions(MapMode from) {
        Viewport[] vps = getViewports();
        double[] fractions = new double[vps.length];
        for (int i = 0; i < vps.length; i++) {
            double f1 = limbFractionAtUnitZoom(from, vps[i]);
            if (f1 <= 0 || vps[i].zoom <= 0)
                return null;
            fractions[i] = f1 / vps[i].zoom;
        }
        return fractions;
    }

    private static void restoreLimbFractions(MapMode to, double[] fractions) {
        if (fractions == null)
            return;
        Viewport[] vps = getViewports();
        for (int i = 0; i < vps.length && i < fractions.length; i++) {
            double f1 = limbFractionAtUnitZoom(to, vps[i]);
            if (f1 > 0)
                vps[i].zoom = f1 / fractions[i];
        }
    }

    /**
     * The solar-disk diameter as a fraction of the viewport height at zoom 1, or 0 when this
     * projection has no centered disk. Each branch mirrors the render path it stands for:
     * the camera contract in MapMode.baseCameraWidth, the Box-Cox limb anchor in MapScale,
     * and the HPC scale in GLRenderer.createHpcScales — if one of those changes shape, the
     * matching branch here must follow, or the switch stops being size-invariant.
     * Package-private for DiskSizeInvarianceCheck (which avoids the HPC branch: it reaches
     * the live viewpoint through GLRenderer and cannot run headless).
     */
    static double limbFractionAtUnitZoom(MapMode m, Viewport vp) {
        return switch (m) {
            case Orthographic -> 2 / m.baseCameraWidth(getCamera());
            case Helioradial -> {
                if (isHelioradial3D()) {
                    double full = fullWarpFieldRadius();
                    yield 2 * MapScale.boxCoxRadial(full).warpLimb() * full / m.baseCameraWidth(getCamera());
                }
                // Flat: the disk spans limb * (unit map height) inside the fixed normalized disk.
                yield MapScale.boxCoxRadial(effectiveWarpOuterRadius()).warpLimb() / m.baseCameraWidth(getCamera());
            }
            case HPC -> {
                double d = org.helioviewer.jhv.opengl.GLRenderer.getDisplayedViewpoint().distance;
                if (d <= 1)
                    yield 0;
                org.helioviewer.jhv.metadata.Region bounds = ImageLayers.computeHpcScaleBounds();
                double halfHeight = Math.max(0.5 * bounds.height, 0.5 * bounds.width / vp.aspect);
                yield Math.toDegrees(Math.asin(1 / d)) / (halfHeight * m.baseCameraWidth(getCamera()));
            }
            // The disk's angular radius put through the projection's own radial law, over the
            // page's half-height in the same units. Exact while the view is aimed at the Sun,
            // which is the only place the disk is centred; aimed elsewhere the projection stretches
            // it, and that stretch is the projection working rather than an error to correct.
            case ObserverSky -> {
                double d = org.helioviewer.jhv.opengl.GLRenderer.getDisplayedViewpoint().distance;
                if (d <= 1)
                    yield 0;
                double limbRadius = Math.toDegrees(getSkyProjection().radiusFromAngle(Math.asin(1 / d)));
                double halfHeight = Math.toDegrees(
                        getSkyProjection().radiusFromAngle(Math.toRadians(getSkyFieldDegrees())));
                yield limbRadius / (halfHeight * m.baseCameraWidth(getCamera()));
            }
            case HelioradialUnrolled, Latitudinal -> 0;
        };
    }

    public static GridType gridType = GridType.Viewpoint;

    public static void setGridType(GridType _gridType) {
        gridType = _gridType;
    }

    // Where a coronagraph line of sight is taken to have originated. Defaults to the plane of
    // sky because that is what every projection assumed before SurfaceModel existed, hard-coded
    // as z = 0; changing the default would silently move everyone's imagery. See SurfaceModel
    // for why this is a placement model rather than a measurement.
    private static SurfaceModel surfaceModel = SurfaceModel.PlaneOfSky;

    public static SurfaceModel getSurfaceModel() {
        return surfaceModel;
    }

    public static void setSurfaceModel(SurfaceModel model) {
        surfaceModel = model == null ? SurfaceModel.PlaneOfSky : model;
        // Every route to the setting animates: the palette, a restored session, and the rule that
        // drops back to plane of sky when the viewpoint moves inside the field.
        SurfaceTransition.requestSurface(surfaceModel);
    }

    private static double warpLambda = 0.0;

    public static double getWarpLambda() {
        return warpLambda;
    }

    public static void setWarpLambda(double lambda) {
        warpLambda = Math.clamp(lambda, -1, 1);
    }

    /**
     * Whether Helioradial is drawn as rotatable 3D geometry, or as the flat face-on disk.
     *
     * <p>Off by default, deliberately. The flat rendering is the one on the poster, in the paper
     * figures and in everything anyone has seen, so a default install reproduces those. The 3D
     * mode is for exploring: it puts the imagery on a real surface (plane of sky or Thomson
     * sphere) that the camera can orbit, at the cost of the foreshortening that any surface
     * shows when you look at it edge-on.
     */
    private static boolean helioradial3D = false;

    public static boolean isHelioradial3D() {
        return helioradial3D;
    }

    public static void setHelioradial3D(boolean value) {
        if (helioradial3D == value)
            return;
        helioradial3D = value;
        // Flat and 3D are different scenes, not two looks at one scene: the shader, the render
        // path and the camera contract all change together, and the base camera width goes from
        // a fixed 1.1 to roughly twice the field in solar radii. Toggling without this reset
        // leaves the old zoom applied to the new contract, which is a view hundreds of times the
        // wrong size and reads as the toggle being broken. This is exactly what setMapMode does
        // for a projection change, and for the same reason.
        if (mode == MapMode.Helioradial) {
            org.helioviewer.jhv.opengl.RenderGuard.reset();
            resetViewportZoom();
            DisplayController.resetCameras();
        }
    }

    // Outer edge of the warp projections in solar radii. 0 = auto: the largest radial size
    // among the loaded layers. Lowering it is a radial crop — a linear zoom-in independent
    // of the lambda warp — and makes the projection edge itself mutable.
    private static double warpOuterRadius = 0.0;

    public static double getWarpOuterRadius() {
        return warpOuterRadius;
    }

    public static void setWarpOuterRadius(double radius) {
        warpOuterRadius = radius <= 0 ? 0 : Math.max(radius, 1.1);
    }

    /**
     * The warp projections' outer edge: the user's radial crop when set, else the full field.
     *
     * <p>Lives here rather than in the renderer because it is a display setting, and because
     * MapMode needs it to size the helioradial camera. Routing that through GLRenderer forced
     * the renderer's class initialization, which reaches SPICE and cannot run headless, so the
     * framing could not be tested without a graphics context.
     *
     * <p>ImageLayers is touched only on the auto path, and only inside this method body, so
     * naming it here does not drag the layer stack into Display's own initialization.
     */
    public static double effectiveWarpOuterRadius() {
        double user = warpOuterRadius;
        return user > 0 ? user : fullWarpFieldRadius();
    }

    /**
     * The radial extent the warp itself is normalized over: always the full loaded field,
     * never the edge crop.
     *
     * <p>Keeping these two apart is what makes the edge behave as its own comment promises, "a
     * linear zoom-in independent of the lambda warp". Feeding the crop into the warp instead
     * renormalizes the projection, so lowering the edge redistributes structure inside a rim
     * that never moves, which reads as the picture rearranging itself rather than as a zoom.
     * With them separated, the warp mapping is fixed by the data and the edge only decides how
     * much of it the camera shows, so cropping magnifies everything uniformly.
     */
    public static double fullWarpFieldRadius() {
        return Math.max(ImageLayers.getLargestRadialSize(), 1.1);
    }

    // The render area: the part of the drawable actually drawn into. Equal to the canvas unless
    // a fixed output aspect insets it, in which case the margin is left showing the clear colour.
    static int glWidth = 1;
    static int glHeight = 1;
    private static int originX;
    private static int originY;
    // The drawable itself. GL viewport y is measured against this, not against the render area.
    private static int canvasWidth = 1;
    private static int canvasHeight = 1;
    public static final double[] pixelScale = {1, 1};

    public static int getCanvasWidth() {
        return canvasWidth;
    }

    public static int getCanvasHeight() {
        return canvasHeight;
    }

    /**
     * Set the drawable size and derive the render area inside it.
     *
     * <p>{@code x}/{@code y} exist for callers that draw into part of a larger drawable; the
     * inset for the output aspect is computed on top of them, so a fixed aspect always lands
     * centred in whatever region was handed in.
     */
    public static void setGLSize(int x, int y, int w, int h) {
        canvasWidth = Math.max(1, w);
        canvasHeight = Math.max(1, h);

        int rw = canvasWidth, rh = canvasHeight;
        if (outputAspect > 0 && !outputFitSuppressed) {
            // Inscribe the output's shape: whichever axis binds keeps its full length, and the
            // other gives up the difference to the bars.
            if (outputAspect >= canvasWidth / (double) canvasHeight)
                rh = Math.max(1, (int) Math.round(canvasWidth / outputAspect));
            else
                rw = Math.max(1, (int) Math.round(canvasHeight * outputAspect));
        }
        glWidth = rw;
        glHeight = rh;
        originX = x + (canvasWidth - rw) / 2;
        originY = y + (canvasHeight - rh) / 2;
        fullViewport = DisplayLayout.fullViewport(originX, originY, rw, rh, canvasHeight);
    }

    private static final Camera camera = new Camera();
    private static final Camera miniCamera = new Camera();

    public static Camera getCamera() {
        return camera;
    }

    public static Camera getMiniCamera() {
        return miniCamera;
    }

    private static Viewport[] viewports = {DisplayLayout.viewport(0, 0, 0, 100, 100, glHeight)};
    private static int activeViewport = 0;

    public static Viewport fullViewport = DisplayLayout.fullViewport(0, 0, 100, 100, glHeight);

    private static Viewport findViewport(int x, int y) {
        if (!multiview)
            return viewports[0];

        for (Viewport viewport : viewports) {
            if (viewport.contains(x, y)) {
                return viewport;
            }
        }
        return viewports[activeViewport];
    }

    public static Viewport setActiveViewport(int x, int y) {
        Viewport vp = findViewport(x, y);
        activeViewport = vp.idx;
        return vp;
    }

    public static Viewport getActiveViewport() {
        return viewports[activeViewport];
    }

    public static Viewport getViewport(int idx) {
        return viewports[idx];
    }

    public static Viewport[] getViewports() {
        return viewports;
    }

    public static void resetViewportZoom() {
        for (Viewport viewport : viewports)
            viewport.zoom = 1;
    }

    private static int countEnabledLayers() {
        int ct = 0;
        if (multiview) {
            for (ImageLayer layer : Layers.getImageLayers()) {
                if (layer.isEnabled()) {
                    ct++;
                    if (ct == 6)
                        break;
                }
            }
        }
        return ct;
    }

    public static void reshapeAll() {
        Viewport[] oldViewports = viewports;
        activeViewport = 0;
        viewports = DisplayLayout.viewports(originX, originY, glWidth, glHeight, canvasHeight, countEnabledLayers());
        if (separateViewportZoom) {
            int count = Math.min(oldViewports.length, viewports.length);
            for (int i = 0; i < count; i++)
                viewports[i].zoom = oldViewports[i].zoom;
        } else {
            double zoom = oldViewports[0].zoom;
            for (Viewport viewport : viewports)
                viewport.zoom = zoom;
        }
    }

    public static boolean separateViewportZoom = false;

    public static void setSeparateViewportZoom(boolean separate) {
        separateViewportZoom = separate;
        if (!separateViewportZoom)
            resetViewportZoom();
    }

    // Overlay a dashed frame showing the region of the canvas that the recorded video will capture
    // (the output resolution's aspect ratio, which can differ from the on-screen canvas aspect).
    public static boolean showPrintableArea = false;

    /**
     * The video's aspect ratio, or 0 when the output simply follows the window ("On screen").
     *
     * <p>When it is set, the render area is inset to match it and the margin is left as
     * letterbox bars, so what is on screen is the recorded frame at a smaller size rather than
     * a differently-shaped view of the same scene. Recording keeps the camera's vertical extent
     * and takes its horizontal extent from this ratio, so without the inset a wider output
     * captured past both edges of the window and there was no composing against it.
     *
     * <p>The window therefore decides how large the preview is and nothing else: resolution
     * comes from this ratio plus the long side, and framing from the camera plus this ratio.
     */
    private static double outputAspect;

    public static void setOutputAspect(double ratio) {
        double newAspect = ratio > 0 ? ratio : 0;
        if (outputAspect == newAspect)
            return;
        outputAspect = newAspect;
        setGLSize(0, 0, canvasWidth, canvasHeight); // re-inset the render area
        reshapeAll();
    }

    public static double getOutputAspect() {
        return outputAspect;
    }

    /**
     * Set while GLGrab captures a frame. The capture renders straight into a target that IS the
     * output size, so insetting there would letterbox the written video itself -- the bars are
     * a preview device for reconciling a differently-shaped window, and belong on screen only.
     */
    public static boolean outputFitSuppressed;

    /**
     * Set while a capture is rendering into a high-bit-depth target.
     *
     * <p>Read by the fragment shader (DisplayBlock.highBitDepth) to skip the dither it adds
     * before the colour-table lookup. That dither exists solely to break up 8-bit banding, so
     * writing it into a 16-bit file would be recording noise that the destination did not need.
     */
    public static boolean highBitDepthCapture;

    /**
     * Set once at startup when the canvas comes up as a deep-colour (RGB10_A2 IOSurface) target
     * rather than the 8-bit window surface; see AngleRenderer.
     *
     * <p>Read below for the same reason as {@link #highBitDepthCapture}: the dither exists solely
     * to break up the 8-bit screen's banding, so on a deep canvas it would be adding noise the
     * target can actually store.
     */
    public static boolean deepCanvas;

    /**
     * True when the canvas is the EDR rung: RGBA16F IOSurface, RGBA16Float layer tagged linear.
     * Image layers may then be scaled past 1.0 by {@link HdrGain}.
     */
    public static boolean edrCanvas;

    /** The screen's EDR headroom in SDR whites, refreshed after each presented frame; 1 otherwise. */
    public static volatile double edrHeadroom = 1;

    /**
     * What the screen could offer once EDR content is on it; 1 on a display without EDR. The
     * compositor engages EDR only for content above roughly 1.25, so with this above 1 and
     * {@link #edrHeadroom} still at 1 the gain bootstraps a frame slightly past white.
     */
    public static volatile double edrPotential = 1;

    /**
     * Add a dither before the colour-table lookup, to break up banding.
     *
     * <p>On by default, because it has always been on and turning it off changes every layer.
     *
     * <p>Worth being precise about what it protects against, since the obvious guess is wrong. The
     * amplitude is 1/255, which is one step of the SCREEN, not of the source: the LUT is sampled
     * LINEAR for continuous tables, so a 16-bit FITS layer already arrives at the framebuffer as a
     * continuous ramp and the banding that remains is the 8-bit display quantizing it. That is why
     * this is not gated on the layer's own bit depth. An 8-bit browse product gains the most from
     * it, having both sources of banding, but it is not the only layer that gains.
     *
     * <p>Categorical layers are never dithered, here or anywhere: their value selects a legend
     * entry rather than sitting on a ramp, so a nudge of 1/255 is a different category.
     */
    private static boolean ditherEnabled = !"false".equals(Settings.getProperty("display.dither"));

    public static boolean isDitherEnabled() {
        return ditherEnabled;
    }

    public static void setDitherEnabled(boolean enabled) {
        ditherEnabled = enabled;
        Settings.setProperty("display.dither", Boolean.toString(enabled));
    }

    /**
     * Whether the shader should skip the dither. Deliberately takes no layer: see above for why
     * the source's bit depth is not what this protects against.
     */
    public static boolean skipDither() {
        // A capture into a 16-bit target would be recording the noise into a file that did not
        // need it, which is the case this flag was originally added for. A deep-colour canvas is
        // the same situation on screen: the target stores the ramp the dither was faking.
        return highBitDepthCapture || deepCanvas || !ditherEnabled;
    }

    /**
     * Paint clipped pixels in flag colours: magenta at or above the top of the display range,
     * green at or below the bottom.
     *
     * <p>A diagnostic, because clipping here is otherwise silent: fetch() applies Levels and the
     * response factor with no clamp, and the colour table is CLAMP_TO_EDGE, so a pixel driven
     * past either end just renders as that end's colour with nothing to distinguish it from a
     * pixel legitimately at the extreme.
     *
     * <p>It answers one specific question and not its neighbour. A flat region in the DISPLAY
     * range lights up; a flat region of tied values in the SOURCE does not, because those are
     * not clipped here at all -- RHEF gives an equal-valued block one shared average rank
     * (FilterRHEF: "Equal values get their average rank"), which lands wherever that value falls
     * in the annulus and is usually a mid-tone. So flat-and-flagged means the Levels are doing
     * it; flat-and-unflagged means it arrived that way.
     */
    public static boolean showClipping = "true".equals(Settings.getProperty("display.showClipping"));

    public static void setShowClipping(boolean show) {
        showClipping = show;
        Settings.setProperty("display.showClipping", Boolean.toString(show));
    }

    /**
     * A multiplier on the nominal Box-Cox limb anchor, deciding how much of the radial axis the
     * solar disk takes. 1.0 is the nominal warp exactly.
     *
     * <p>The anchor is {@code max(1/R, 1/(1 + boxcox(R, lambda)))}, so left alone the disk's share
     * is a side effect of the warp exponent: on a 245 solar-radii field it is under 1% at
     * lambda = 1 and over 40% at lambda = -1, and the low corona takes whatever is left. Scaling
     * that anchor separates the two questions -- lambda decides how the corona's radial axis is
     * compressed, this decides how much of the axis the photosphere is allowed to take.
     *
     * <p>A multiplier rather than an absolute screen fraction, so the setting keeps its meaning
     * when lambda or the field changes: "twice the nominal disk" stays twice the nominal disk,
     * where a pinned 8% would mean something different at every warp. And a plain continuous
     * value rather than a sentinel-plus-range, because a sentinel is a discontinuity by
     * construction: stepping off it would jump the disk in one pixel of travel.
     */
    /** Below this the disk is a speck; above it there is not much corona left to look at. */
    public static final double DISK_SCALE_MIN = 0.05;
    public static final double DISK_SCALE_MAX = 2;
    /** The multiplier that returns the Box-Cox anchor untouched. */
    public static final double DISK_SCALE_NOMINAL = 1;
    // 0.5 rather than nominal: half the anchor reads better at every field size tried, which is
    // unsurprising -- the nominal value was never chosen, it fell out of the Box-Cox algebra.
    public static final double DEFAULT_DISK_SCALE = 0.5;

    private static double diskScale = DEFAULT_DISK_SCALE;

    public static double getDiskScale() {
        return diskScale;
    }

    public static void setDiskScale(double scale) {
        applyDiskScale(scale);
        Settings.setProperty("display.diskScale", String.valueOf(diskScale));
        DisplayController.render(1);
    }

    /**
     * The state change on its own, without the repaint or the write to Settings. Split out so the
     * geometry can be exercised headlessly: touching DisplayController drags in the viewpoint,
     * which drags in SPICE's native library, which no check can load.
     */
    static void applyDiskScale(double scale) {
        diskScale = Math.clamp(scale, DISK_SCALE_MIN, DISK_SCALE_MAX);
    }

    static {
        try {
            diskScale = Math.clamp(Double.parseDouble(Settings.getProperty("display.diskScale")),
                    DISK_SCALE_MIN, DISK_SCALE_MAX);
        } catch (Exception ignore) {
            diskScale = DEFAULT_DISK_SCALE;
        }
    }

    /**
     * The observer-sky projection, and where it is aimed.
     *
     * <p>The look direction is helioprojective: (0, 0) is the Sun, so a fresh Observer Sky view is
     * the ordinary Sun-centred picture and turning away from it is the new thing. Kept in radians
     * because that is what {@link SkyMap} and the shader both want; only the UI speaks degrees.
     *
     * <p>Persisted like the disk scale rather than carried in the session's ModeData, deliberately:
     * these are how you have the viewer set up, not what a saved session is OF, and a shared
     * session file should not reach in and turn someone's head.
     */
    private static SkyProjection skyProjection = SkyProjection.DEFAULT;
    private static double skyLookLon;
    private static double skyLookLat;

    /** Angular radius of the sky view, measured from the centre of the picture to its top edge. */
    public static final double SKY_FIELD_MIN = 1;
    public static final double SKY_FIELD_MAX = 180;
    // Wide enough to hold any coronagraph field whole (LASCO C3 reaches about 8 degrees, PUNCH
    // about 45), so the mode opens on a picture rather than on a crop of one.
    public static final double DEFAULT_SKY_FIELD = 60;

    private static double skyFieldDegrees = DEFAULT_SKY_FIELD;

    public static SkyProjection getSkyProjection() {
        return skyProjection;
    }

    /**
     * Switch the sky projection, keeping the solar disk the size it was.
     *
     * <p>Without this the three styles jump against each other, because the page is normalized so
     * that the field radius lands on the top edge: at a 60 degree field the whole picture is
     * divided by R(60), which is 1.05 for azimuthal equidistant and 1.73 for gnomonic, so
     * everything near the centre changes size by a factor of 1.65 on a switch. The edge is the
     * wrong thing to hold still anyway. What is worth comparing between these projections is how
     * each of them treats the corona, and that comparison is only readable if the Sun does not
     * move: r = 1 is the one radius every frame has in common.
     *
     * <p>Solved through the viewport zoom, exactly as {@link #setMapMode} does it for a projection
     * change, and by the same pair of helpers, so the two kinds of switch cannot drift apart.
     */
    public static void setSkyProjection(SkyProjection projection) {
        SkyProjection wanted = projection == null ? SkyProjection.DEFAULT : projection;
        if (wanted == skyProjection)
            return;
        double[] keep = mode == MapMode.ObserverSky ? captureLimbFractions(mode) : null;
        skyProjection = wanted;
        Settings.setProperty("display.skyProjection", skyProjection.name());
        restoreLimbFractions(mode, keep);
    }

    public static double getSkyFieldDegrees() {
        return skyFieldDegrees;
    }

    public static void setSkyFieldDegrees(double degrees) {
        skyFieldDegrees = Math.clamp(degrees, SKY_FIELD_MIN, SKY_FIELD_MAX);
        Settings.setProperty("display.skyField", String.valueOf(skyFieldDegrees));
    }

    public static double getSkyLookLon() {
        return skyLookLon;
    }

    public static double getSkyLookLat() {
        return skyLookLat;
    }

    public static void setSkyLook(double lon, double lat) {
        aimSkyLook(lon, lat);
        commitSkyLook();
    }

    /**
     * Turn the aim by an angle east and an angle north, in radians, WITHOUT writing it to disk.
     *
     * <p>The split is not fussiness: Settings.setProperty rewrites the whole properties file on
     * every change, and a look-around is a drag, so persisting per motion event would put a file
     * write between each pair of frames. The drag calls this and then {@link #commitSkyLook} once,
     * on release.
     */
    public static void steerSkyLook(double east, double north) {
        double[] aim = SkyMap.steer(skyLookLon, skyLookLat, east, north);
        aimSkyLook(aim[0], aim[1]);
    }

    private static void aimSkyLook(double lon, double lat) {
        skyLookLon = lon;
        skyLookLat = Math.clamp(lat, -Math.PI / 2, Math.PI / 2);
    }

    public static void commitSkyLook() {
        Settings.setProperty("display.skyLookLon", String.valueOf(skyLookLon));
        Settings.setProperty("display.skyLookLat", String.valueOf(skyLookLat));
    }

    /** Aim back at the Sun. The one direction that is always worth one click. */
    public static void resetSkyLook() {
        setSkyLook(0, 0);
    }

    static {
        SkyProjection saved = SkyProjection.fromName(String.valueOf(Settings.getProperty("display.skyProjection")));
        if (saved != null)
            skyProjection = saved;
        try {
            skyFieldDegrees = Math.clamp(Double.parseDouble(Settings.getProperty("display.skyField")),
                    SKY_FIELD_MIN, SKY_FIELD_MAX);
        } catch (Exception ignore) {
            skyFieldDegrees = DEFAULT_SKY_FIELD;
        }
        try {
            skyLookLon = Double.parseDouble(Settings.getProperty("display.skyLookLon"));
            skyLookLat = Math.clamp(Double.parseDouble(Settings.getProperty("display.skyLookLat")),
                    -Math.PI / 2, Math.PI / 2);
        } catch (Exception ignore) {
            skyLookLon = skyLookLat = 0;
        }
    }

    private static boolean showCorona = true;

    public static void setShowCorona(boolean _showCorona) {
        showCorona = _showCorona;
    }

    public static boolean getShowCorona() {
        return showCorona;
    }

    private Display() {}
}
