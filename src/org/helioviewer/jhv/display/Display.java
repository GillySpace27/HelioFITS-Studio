package org.helioviewer.jhv.display;

import org.helioviewer.jhv.layers.ImageLayers;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;

public final class Display {

    public static MapMode mode = MapMode.Orthographic;
    public static boolean multiview = false;
    public static boolean whiteBackground = false;

    public static void setMapMode(MapMode _mode) {
        mode = _mode;
        // A failure under one projection says nothing about the next, so let it be reported again.
        org.helioviewer.jhv.opengl.RenderGuard.reset();
        resetViewportZoom();
        DisplayController.resetCameras();
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
    }

    private static double warpLambda = 0.0;

    public static double getWarpLambda() {
        return warpLambda;
    }

    public static void setWarpLambda(double lambda) {
        warpLambda = Math.clamp(lambda, -1, 1);
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

    static int glWidth = 1;
    static int glHeight = 1;
    public static final double[] pixelScale = {1, 1};

    public static void setGLSize(int x, int y, int w, int h) {
        glWidth = w;
        glHeight = h;
        fullViewport = DisplayLayout.fullViewport(x, y, w, h, glHeight);
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
        viewports = DisplayLayout.viewports(glWidth, glHeight, countEnabledLayers());
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

    private static boolean showCorona = true;

    public static void setShowCorona(boolean _showCorona) {
        showCorona = _showCorona;
    }

    public static boolean getShowCorona() {
        return showCorona;
    }

    private Display() {}
}
