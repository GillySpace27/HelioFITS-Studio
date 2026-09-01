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
import org.helioviewer.jhv.display.ProjectionTransition;
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
            case ObserverSky -> createSkyScales(viewports);
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

    /**
     * The sky page is sized by the user's field of view, not by the loaded data.
     *
     * <p>Deliberately unlike the HPC scale beside it, which fits itself to whatever is loaded. A
     * look-around whose field jumped every time a layer arrived would be unusable, and the point of
     * the mode is to be able to aim away from the data and still know where you are pointing.
     */
    private static MapScale[] createSkyScales(Viewport[] viewports) {
        // The field is an angle; the page coordinate is that angle put through the projection's
        // radial law. They are the same number only for azimuthal equidistant.
        double halfHeight = Math.toDegrees(Display.getSkyProjection()
                .radiusFromAngle(Math.toRadians(Display.getSkyFieldDegrees())));
        MapScale[] scales = new MapScale[viewports.length];
        for (Viewport vp : viewports)
            scales[vp.idx] = MapScale.sky(halfHeight * vp.aspect, halfHeight);
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
        GLSLTransitionShader.init();
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

        // Wedged between prerender and the mapView rebuild, and it has to be exactly here.
        // After prerender, because that is where each layer's GL init runs: capturing ahead of
        // it drew the outgoing scene through vertex buffers that did not exist yet, which cost
        // the grid its first frame. Before the rebuild, because the snapshot is of the scene
        // being left behind, and `mapView` and `Display.mode` only still describe it until the
        // next line.
        if (ProjectionTransition.hasPendingSwitch())
            ProjectionTransition.applyPendingSwitch(GLRenderer::captureTransitionSnapshot);

        mapView = createMapView(Display.getCamera(), viewpoint);
        if (mapView.rendersIn3D()) {
            renderScene();
            RenderGuard.run("miniview", GLRenderer::renderMiniview);
        } else
            renderSceneScale();
        renderFullFloatScene();
        if (ProjectionTransition.isActive())
            RenderGuard.run("projection transition", GLRenderer::renderTransitionOverlay);

        if (ExportMovie.isRecording())
            ExportMovie.handleMovieExport();
    }

    // Snapshot of the scene a projection switch is fading out of, captured once when the
    // switch happens and re-drawn (with a shrinking alpha) on every frame of the fade. Sized to
    // the window and never resized down; window-resize during a 280ms fade is rare enough not
    // to be worth reclaiming the texture for.
    private static int transitionFbo, transitionTexture, transitionDepthRbo;
    private static int transitionW = -1, transitionH = -1;

    // Renders the CURRENT (about to become outgoing) mapView into an offscreen texture, for
    // ProjectionTransition to fade out over the new one. Mirrors GLGrab.renderFrame's pattern
    // (bind an FBO, call the same renderScene()/renderSceneScale() the visible frame uses) --
    // the difference is this keeps the render as a GPU texture instead of reading it back to
    // the CPU, since it only ever needs to be sampled by another shader, never encoded.
    private static void captureTransitionSnapshot() {
        // Canvas-sized, NOT viewport-sized. renderScene draws each viewport at (vp.x, vp.yGL),
        // which are offsets within the drawable, and with a letterboxed render area those are
        // no longer zero. A viewport-sized target would receive the scene displaced by the
        // inset and then have it stretched back over the render area on playback, so the
        // fading image sat off-register until the fade ended and the real scene snapped into
        // place. Matching the drawable makes those offsets mean the same thing in both.
        int w = Display.getCanvasWidth(), h = Display.getCanvasHeight();
        if (w <= 0 || h <= 0)
            return;
        ensureTransitionCapture(w, h);

        GL.glBindFramebuffer(GL.FRAMEBUFFER, transitionFbo);
        GL.glViewport(0, 0, w, h);
        GL.glClearColor(0, 0, 0, 0);
        GL.glClear(GL.COLOR_BUFFER_BIT | GL.DEPTH_BUFFER_BIT);
        if (mapView.rendersIn3D())
            renderScene();
        else
            renderSceneScale();
        GL.glBindFramebuffer(GL.FRAMEBUFFER, 0);
    }

    private static void ensureTransitionCapture(int w, int h) {
        if (w == transitionW && h == transitionH)
            return;
        disposeTransitionCapture();

        transitionFbo = GL.glGenFramebuffer();
        GL.glBindFramebuffer(GL.FRAMEBUFFER, transitionFbo);

        transitionTexture = GL.glGenTexture();
        GL.glBindTexture(GL.TEXTURE_2D, transitionTexture);
        GL.glTexParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, GL.LINEAR);
        GL.glTexParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, GL.LINEAR);
        GL.glTexParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_S, GL.CLAMP_TO_EDGE);
        GL.glTexParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE);
        GL.glTexImage2D(GL.TEXTURE_2D, 0, GL.RGBA, w, h, 0, GL.RGBA, GL.UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL.glFramebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_2D, transitionTexture, 0);

        transitionDepthRbo = GL.glGenRenderbuffer();
        GL.glBindRenderbuffer(GL.RENDERBUFFER, transitionDepthRbo);
        GL.glRenderbufferStorage(GL.RENDERBUFFER, GL.DEPTH_COMPONENT24, w, h);
        GL.glFramebufferRenderbuffer(GL.FRAMEBUFFER, GL.DEPTH_ATTACHMENT, GL.RENDERBUFFER, transitionDepthRbo);

        GL.glBindTexture(GL.TEXTURE_2D, 0);
        GL.glBindRenderbuffer(GL.RENDERBUFFER, 0);
        GL.glBindFramebuffer(GL.FRAMEBUFFER, 0);

        transitionW = w;
        transitionH = h;
    }

    private static void disposeTransitionCapture() {
        if (transitionFbo != 0)
            GL.glDeleteFramebuffer(transitionFbo);
        if (transitionTexture != 0)
            GL.glDeleteTexture(transitionTexture);
        if (transitionDepthRbo != 0)
            GL.glDeleteRenderbuffer(transitionDepthRbo);
        transitionFbo = transitionTexture = transitionDepthRbo = 0;
        transitionW = transitionH = -1;
    }

    // Un-depth-tested: the fade must cover everything drawn so far this frame, including
    // world-space layers the 3D path just wrote depth for.
    private static void renderTransitionOverlay() {
        // The whole drawable, to match what was captured: the snapshot is canvas-sized, so the
        // quad has to cover the canvas for the texture to land back on the pixels it came from.
        // The letterbox margins in it were cleared transparent and blend as nothing.
        GL.glViewport(0, 0, Display.getCanvasWidth(), Display.getCanvasHeight());
        GL.glDisable(GL.DEPTH_TEST);
        GLSLTransitionShader.render(transitionTexture, ProjectionTransition.fadeAlpha());
        GL.glEnable(GL.DEPTH_TEST);
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
        GLSLTransitionShader.dispose();
        disposeTransitionCapture();

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
                    // An isolated export pass keeps the disk's occlusion (the far side of the
                    // grid must still hide) but not its black: a layer written on its own must
                    // not carry an opaque disk into its alpha.
                    boolean depthOnly = Layers.captureOnly != null;
                    if (depthOnly)
                        GL.glColorMask(false, false, false, false);
                    GLSLSolarShader.sphere.use();
                    GLSLSolar.quad.render();
                    if (depthOnly)
                        GL.glColorMask(true, true, true, true);
                });
            }

            Layers.render(mv, vp);
            if (Layers.captureOnly == null) // hand-drawn annotations are part of the composite only
                RenderGuard.run("annotations", () -> Annotations.render(mv, vp));
            // Screen-space HUD (the colour-table legend), drawn in PIXEL coordinates. The warp
            // is a vertex-stage transform on raw vertex values (shape.vert -> warpWorld), so
            // leaving it enabled fed pixel positions to a mapping that expects solar radii and
            // dragged the legend toward the origin by a lambda-dependent factor -- with its SDF
            // labels, which take an unwarped shader, staying put beside it. Same reason
            // renderMiniview and renderFullFloatScene disable it; this one was missed because it
            // is the only screen-space drawing that happens inside renderScene's viewport loop.
            GLSLWarp.disable();
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
            if (Layers.captureOnly == null)
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
     * <p><b>It traces the viewport, and that is the point.</b> With a fixed output aspect the
     * render area is already inset to the output's shape, so the captured region and the
     * viewport are the same rectangle and this outline sits on its border, confirming the
     * framing rather than correcting for it. It still earns its place with "On screen", where
     * the output follows the window and the rectangle is the whole canvas, and as the label
     * that states the pixel size being recorded.
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
        // Recording keeps the camera's vertical extent and takes horizontal from the output
        // aspect. The render area already has that aspect whenever one is fixed, so this
        // resolves to the viewport's own border there, and to a true full-height rectangle
        // under "On screen".
        double ph = vp.height;
        double pw = ph * aspectOut;
        double x0 = (vp.width - pw) / 2, x1 = x0 + pw;
        double y0 = (vp.height - ph) / 2, y1 = y0 + ph;

        byte[] col = Colors.bytes(255, 230, 60, 235);
        byte[] nul = Colors.Null;

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
        // setVertex, NOT setVertexRepeatable: "repeatable" means the buffer is kept so it can be
        // re-uploaded unchanged next frame, and this one is refilled from scratch every frame.
        // Using it here appended a whole new outline per frame forever, so every previous frame's
        // border stayed in the buffer and got drawn again on top of the current one.
        printableLine.setVertex(printableBuf);
        // Thickness is a FRACTION OF VIEWPORT HEIGHT, not pixels (line.vert: halfWidthPixels =
        // thickness * viewportSize.y * 0.5). This read 2, i.e. two viewport heights, so the
        // "frame" painted the entire canvas yellow instead of outlining anything.
        printableLine.renderLine(vp, GLSLLine.LINEWIDTH_BASIC);
        Transform.popView();
        Transform.popProjection();

        GLText.drawTextFloat(vp, java.util.List.of(out.width() + " \u00d7 " + out.height()),
                             (int) x0 + 6, vp.height - (int) y1 + 6);
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
