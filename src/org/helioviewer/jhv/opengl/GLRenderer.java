package org.helioviewer.jhv.opengl;

import org.helioviewer.jhv.annotation.Annotations;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.display.Camera;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.GridType;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.layers.ImageLayers;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.MiniviewLayer;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.movie.ExportMovie;

public final class GLRenderer {

    private static MapView mapView = initialMapView();

    private static MapView initialMapView() {
        return createMapView(Display.getCamera(), Sun.StartEarth);
    }

    private static MapView createMapView(Camera camera, Position viewpoint) {
        MapMode mode = Display.mode;
        return mode.createMapView(camera, viewpoint, Display.gridType, createScales(mode, Display.getViewports()));
    }

    private static MapScale[] createScales(MapMode mode, Viewport[] viewports) {
        return switch (mode) {
            case Orthographic -> createConstantScales(viewports, MapScale.ortho);
            case HPC -> createHpcScales(viewports);
            case Latitudinal -> createConstantScales(viewports, MapScale.lati);
            // In 3D, Helioradial normalizes the warp over the whole loaded field and lets the
            // camera do the cropping, so the edge is a zoom. Flat, there is no camera to crop
            // with (the map fills a fixed disk), so the edge has to act through the scale, which
            // is what it has always done and what the published figures were made with. The
            // unrolled layout is flat for the same reason.
            case Helioradial -> createConstantScales(viewports, MapScale.boxCoxRadial(
                    Display.isHelioradial3D() ? Display.fullWarpFieldRadius() : effectiveOuterRadius()));
            case HelioradialUnrolled -> createConstantScales(viewports, MapScale.boxCoxRadial(effectiveOuterRadius()));
        };
    }

    // Kept as the renderer-side name for what is really a display setting. See
    // Display.effectiveWarpOuterRadius for why the logic lives there.
    public static double effectiveOuterRadius() {
        return Display.effectiveWarpOuterRadius();
    }

    private static MapScale[] createHpcScales(Viewport[] viewports) {
        Region bounds = ImageLayers.computeHpcScaleBounds();
        MapScale[] scales = new MapScale[viewports.length];
        for (Viewport vp : viewports) {
            double halfWidth = 0.5 * bounds.width;
            double halfHeight = Math.max(0.5 * bounds.height, halfWidth / vp.aspect);
            scales[vp.idx] = MapScale.hpc(halfHeight * vp.aspect, halfHeight);
        }
        return scales;
    }

    private static MapScale[] createConstantScales(Viewport[] viewports, MapScale scale) {
        MapScale[] scales = new MapScale[viewports.length];
        for (Viewport vp : viewports)
            scales[vp.idx] = scale;
        return scales;
    }

    public static Position getDisplayedViewpoint() {
        return mapView.viewpoint();
    }

    public static MapView getMapView() {
        return mapView;
    }

    public static void init() {
        GL.glEnable(GL.BLEND);
        GL.glBlendFunc(GL.ONE, GL.ONE_MINUS_SRC_ALPHA);
        GL.glBlendEquation(GL.FUNC_ADD);

        GL.glEnable(GL.DEPTH_TEST);
        GL.glDepthFunc(GL.LEQUAL);

        GL.glEnable(GL.CULL_FACE);
        GL.glCullFace(GL.BACK);

        GL.glClearColor(0, 0, 0, 0);
        GL.glClear(GL.COLOR_BUFFER_BIT | GL.DEPTH_BUFFER_BIT);

        GLSLSolar.quad.init();
        GLSLWarp.init(); // must precede the shaders that bind its block
        GLSLSolarShader.init();
        GLSLLineShader.init();
        GLSLShapeShader.init();
        GLSLTextureShader.init();
        // Never initialised until now: renderPrintableArea() returns early unless
        // Display.showPrintableArea is on, so the null VBO stayed hidden until someone
        // ticked the box, and then it threw on every frame and blanked the canvas.
        printableLine.init();

        Annotations.init();
    }

    public static void reshape(int glWidth, int glHeight) {
        Display.setGLSize(0, 0, glWidth, glHeight);
        Display.reshapeAll();
        MiniviewLayer miniview = Layers.getMiniviewLayer();
        if (miniview != null)
            miniview.reshapeViewport();
    }

    public static void display(Position viewpoint) {
        if (Display.whiteBackground)
            GL.glClearColor(1, 1, 1, 0);
        else
            GL.glClearColor(0, 0, 0, 0);
        GL.glClear(GL.COLOR_BUFFER_BIT | GL.DEPTH_BUFFER_BIT);

        Layers.prerender();

        mapView = createMapView(Display.getCamera(), viewpoint);
        if (mapView.rendersIn3D()) {
            renderScene();
            RenderGuard.run("miniview", GLRenderer::renderMiniview);
        } else
            renderSceneScale();
        renderFullFloatScene();

        if (ExportMovie.isRecording())
            ExportMovie.handleMovieExport();
    }

    public static void dispose() {
        Layers.dispose();
        Annotations.dispose();
        ExportMovie.dispose();
        GLText.dispose();

        GLSLSolar.quad.dispose();
        GLSLSolarShader.dispose();
        GLSLWarp.dispose();
        printableLine.dispose();
        GLSLLineShader.dispose();
        GLSLShapeShader.dispose();
        GLSLTextureShader.dispose();

        GLException.checkErrors("GLRenderer.dispose()");
    }

    static void renderScene() {
        MapView mv = mapView;
        for (Viewport vp : Display.getViewports()) {
            MapScale scale = mv.scale(vp);
            GL.glViewport(vp.x, vp.yGL, vp.width, vp.height);
            Transform.ortho(vp.aspect, mv.cameraWidth(vp), mv.cameraTranslationX(), mv.cameraTranslationY(), mv.viewRotation());
            GLSLSolarShader.bindScreen(vp, scale);
            // World-space overlays share the imagery's radial compression, so a point cloud sits
            // where the imagery would put the same direction and distance. Only Helioradial warps;
            // orthographic passes geometry through untouched.
            if (mv.isHelioradial())
                GLSLWarp.enable(scale);
            else
                GLSLWarp.disable();

            // Only in true orthographic: solarSphere.frag discards outside radius 1 in view
            // units, which stops being the limb once the radial warp moves it.
            if (mv.isOrthographic()) {
                RenderGuard.run("solar disk", () -> {
                    GLSLSolarShader.sphere.use();
                    GLSLSolar.quad.render();
                });
            }

            Layers.render(mv, vp);
            RenderGuard.run("annotations", () -> Annotations.render(mv, vp));
            Layers.renderFloat(mv, vp);
        }
    }

    private static final MapScale[] miniScales = new MapScale[]{MapScale.ortho};

    private static MapView createMiniMapView(Position viewpoint) {
        return MapMode.Orthographic.createMapView(
                Display.getMiniCamera(),
                viewpoint,
                GridType.Viewpoint,
                miniScales
        );
    }

    private static void renderMiniview() {
        GLSLWarp.disable(); // an undistorted context view, even while the main scene is warped

        MiniviewLayer miniview = Layers.getMiniviewLayer();
        if (miniview != null && miniview.isEnabled()) {
            Viewport vp = miniview.getViewport();
            MapView mv = createMiniMapView(mapView.viewpoint());

            GL.glViewport(vp.x, vp.yGL, vp.width, vp.height);
            Transform.ortho2D(vp.aspect, mv.cameraWidth(vp), mv.cameraTranslationX(), mv.cameraTranslationY());
            MapScale scale = mv.scale(vp);
            GLSLSolarShader.bindScreen(vp, scale);

            GL.glDisable(GL.DEPTH_TEST);
            miniview.renderBackground();
            Layers.renderMiniview(mv, vp);
            GL.glEnable(GL.DEPTH_TEST);
        }
    }

    static void renderSceneScale() {
        GLSLWarp.disable(); // flat projections never warp overlays

        MapView mv = mapView;
        for (Viewport vp : Display.getViewports()) {
            MapScale scale = mv.scale(vp);
            GL.glViewport(vp.x, vp.yGL, vp.width, vp.height);
            Transform.ortho2D(vp.aspect, mv.cameraWidth(vp), mv.cameraTranslationX(), mv.cameraTranslationY());
            GLSLSolarShader.bindScreen(vp, scale);

            Layers.renderScale(mv, vp);
            RenderGuard.run("annotations", () -> Annotations.render(mv, vp));
            Layers.renderFloat(mv, vp);
        }
    }

    private static void renderFullFloatScene() {
        GLSLWarp.disable(); // screen-space HUD; never warped

        Viewport vp = Display.fullViewport;
        GL.glViewport(vp.x, vp.yGL, vp.width, vp.height);
        Layers.renderFullFloat(vp);
        RenderGuard.run("recording-area outline", () -> renderPrintableArea(vp));
    }

    private static final GLSLLine printableLine = new GLSLLine(true);
    private static final BufVertex printableBuf = new BufVertex(1024 * GLSLLine.stride);

    // Dashed frame of the region the recorded video will capture. The export preserves the camera's
    // vertical extent and changes the horizontal with the output aspect, so the printable width is
    // the canvas width scaled by (outputAspect / canvasAspect), full height, centred.
    /**
     * Draw the region that recording will actually capture.
     *
     * <p>Three things this has to get right, and the old version got the first wrong.
     *
     * <p><b>Inscribe, do not scale one axis.</b> The previous code always kept full height and
     * scaled the width, which only lands inside the canvas when the output is narrower than the
     * window. A 2:1 output in a 16:10 window came out wider than the canvas and ran off both
     * sides. The output rectangle is now fitted inside the viewport on whichever axis binds.
     *
     * <p><b>Hide during capture.</b> This runs from renderFullFloat, which GLGrab also drives,
     * so without the guard the guide lines are burned into the recording. That is the one bug
     * this feature would otherwise ship with, and it is invisible unless the written file is
     * inspected rather than the screen.
     *
     * <p><b>Say what it is.</b> The pixel dimensions are drawn on the overlay, because "2:1 at
     * 8K" is the thing being decided and a rectangle alone does not tell you the resolution.
     */
    private static void renderPrintableArea(Viewport vp) {
        if (!Display.showPrintableArea || ExportMovie.isRecording())
            return;
        ViewState.Size out = ViewState.recordingData().size();
        if (out.width() <= 0 || out.height() <= 0 || vp.width <= 0 || vp.height <= 0)
            return;

        double aspectOut = out.width() / (double) out.height();
        double aspectCanvas = vp.width / (double) vp.height;
        double pw, ph;
        if (aspectOut >= aspectCanvas) { // output is wider: width binds
            pw = vp.width;
            ph = pw / aspectOut;
        } else {                          // output is taller: height binds
            ph = vp.height;
            pw = ph * aspectOut;
        }
        double x0 = (vp.width - pw) / 2, x1 = x0 + pw;
        double y0 = (vp.height - ph) / 2, y1 = y0 + ph;

        byte[] col = Colors.bytes(255, 230, 60, 235);
        byte[] nul = Colors.Null;
        byte[] dim = Colors.bytes(0, 0, 0, 110);

        // Dim what will be cropped away, so the captured region reads as the subject rather than
        // as one rectangle among several. Drawn as four bands around the kept area.
        solidQuad(0, 0, vp.width, y0, dim, nul);
        solidQuad(0, y1, vp.width, vp.height, dim, nul);
        solidQuad(0, y0, x0, y1, dim, nul);
        solidQuad(x1, y0, vp.width, y1, dim, nul);

        dashedEdge(x0, y0, x1, y0, col, nul);
        dashedEdge(x1, y0, x1, y1, col, nul);
        dashedEdge(x1, y1, x0, y1, col, nul);
        dashedEdge(x0, y1, x0, y0, col, nul);

        // Corner ticks: solid, so the corners stay findable when the dashes fall in a gap.
        double tick = Math.min(pw, ph) * 0.06;
        cornerTick(x0, y0, tick, tick, col, nul);
        cornerTick(x1, y0, -tick, tick, col, nul);
        cornerTick(x1, y1, -tick, -tick, col, nul);
        cornerTick(x0, y1, tick, -tick, col, nul);

        Transform.pushProjection();
        Transform.setOrtho2DProjection(0, vp.width, 0, vp.height);
        Transform.pushView();
        Transform.setIdentityView();
        printableLine.setVertexRepeatable(printableBuf);
        printableLine.renderLine(vp, 2);
        Transform.popView();
        Transform.popProjection();

        GLText.drawTextFloat(vp, java.util.List.of(out.width() + " \u00d7 " + out.height()),
                             (int) x0 + 6, vp.height - (int) y1 + 6);
    }

    // Two triangles as a degenerate-joined line strip would be wrong here; the shade is drawn as
    // a thick line down the middle of the band, which is enough for a dimming wash and needs no
    // second shader.
    private static void solidQuad(double ax, double ay, double bx, double by, byte[] col, byte[] nul) {
        if (bx - ax <= 0 || by - ay <= 0)
            return;
        double midY = (ay + by) / 2;
        printableBuf.putVertex((float) ax, (float) midY, 0, 1, nul);
        printableBuf.putVertex((float) ax, (float) midY, 0, 1, col);
        printableBuf.putVertex((float) bx, (float) midY, 0, 1, col);
        printableBuf.putVertex((float) bx, (float) midY, 0, 1, nul);
    }

    private static void cornerTick(double x, double y, double dx, double dy, byte[] col, byte[] nul) {
        printableBuf.putVertex((float) (x + dx), (float) y, 0, 1, nul);
        printableBuf.putVertex((float) (x + dx), (float) y, 0, 1, col);
        printableBuf.putVertex((float) x, (float) y, 0, 1, col);
        printableBuf.putVertex((float) x, (float) (y + dy), 0, 1, col);
        printableBuf.putVertex((float) x, (float) (y + dy), 0, 1, nul);
    }

    private static void dashedEdge(double ax, double ay, double bx, double by, byte[] col, byte[] nul) {
        double dash = 12, gap = 8;
        double dx = bx - ax, dy = by - ay;
        double len = Math.hypot(dx, dy);
        if (len < 1)
            return;
        double ux = dx / len, uy = dy / len;
        for (double s = 0; s < len; s += dash + gap) {
            double e = Math.min(s + dash, len);
            printableBuf.putVertex((float) (ax + ux * s), (float) (ay + uy * s), 0, 1, nul);
            printableBuf.putVertex((float) (ax + ux * s), (float) (ay + uy * s), 0, 1, col);
            printableBuf.putVertex((float) (ax + ux * e), (float) (ay + uy * e), 0, 1, col);
            printableBuf.repeatVertex(nul);
        }
    }

    private GLRenderer() {}
}
