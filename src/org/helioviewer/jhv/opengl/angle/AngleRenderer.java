package org.helioviewer.jhv.opengl.angle;

import java.nio.IntBuffer;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.opengl.GLRenderer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL12;
import org.lwjgl.egl.EGL15;
import org.lwjgl.opengles.GLES;
import org.lwjgl.opengles.GLES20;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class AngleRenderer {
    private enum Backend {
        D3D11(EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE, "D3D11"),
        METAL(EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE, "Metal"),
        OPENGL(EGL_PLATFORM_ANGLE_TYPE_OPENGL_ANGLE, "OpenGL"),
        VULKAN(EGL_PLATFORM_ANGLE_TYPE_VULKAN_ANGLE, "Vulkan");

        private final int eglType;
        private final String label;

        Backend(int _eglType, String _label) {
            eglType = _eglType;
            label = _label;
        }

        private static Backend platform() {
            if (Platform.isMacOS())
                return METAL;
            if (Platform.isWindows())
                return D3D11;
            if (Platform.isLinux())
                return OPENGL;
            throw new IllegalStateException("Unsupported ANGLE platform");
        }
    }

    private enum SurfaceKind {
        WINDOW(EGL15.EGL_WINDOW_BIT, true),
        PBUFFER(EGL15.EGL_PBUFFER_BIT, false);

        private final int eglBit;
        private final boolean swapBuffers;

        SurfaceKind(int _eglBit, boolean _swapBuffers) {
            eglBit = _eglBit;
            swapBuffers = _swapBuffers;
        }
    }

    private static Backend selectBackend(SurfaceKind surfaceKind) {
        // Pbuffer Vulkan is currently used only for external SwiftShader; if an ICD is configured, assume it is SwiftShader.
        if (surfaceKind == SurfaceKind.PBUFFER && AngleLibraries.loadSwiftShader())
            return Backend.VULKAN;
        return Backend.platform();
    }

    private static boolean lwjglConfigured;
    private static boolean rendererInitialized;

    private static final int[] DEPTH_PREFERENCES = {32, 24};
    private static final int EGL_OPENGL_ES3_BIT = 0x00000040;
    private static final int EGL_PLATFORM_ANGLE_ANGLE = 0x3202;
    private static final int EGL_PLATFORM_ANGLE_TYPE_ANGLE = 0x3203;
    private static final int EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE = 0x3208;
    private static final int EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE = 0x3489;
    private static final int EGL_PLATFORM_ANGLE_TYPE_OPENGL_ANGLE = 0x320D;
    private static final int EGL_PLATFORM_ANGLE_TYPE_VULKAN_ANGLE = 0x3450;
    // EGL_ANGLE_iosurface_client_buffer, plus the EGL 1.1 render-to-texture attributes it reuses.
    private static final int EGL_IOSURFACE_ANGLE = 0x3454;
    private static final int EGL_IOSURFACE_PLANE_ANGLE = 0x345A;
    private static final int EGL_TEXTURE_TYPE_ANGLE = 0x345C;
    private static final int EGL_TEXTURE_INTERNAL_FORMAT_ANGLE = 0x345D;
    private static final int EGL_IOSURFACE_USAGE_HINT_ANGLE = 0x348A;
    private static final int EGL_TEXTURE_FORMAT = 0x3080;
    private static final int EGL_TEXTURE_TARGET = 0x3081;
    private static final int EGL_TEXTURE_RGBA = 0x305E;
    private static final int EGL_TEXTURE_2D = 0x305F;
    private static final int GL_RGB10_A2 = 0x8059;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_HALF_FLOAT = 0x140B;
    private static final int GL_UNSIGNED_INT_2_10_10_10_REV = 0x8368;

    private final long display;
    private final long context;
    private final long config;
    private final boolean swapBuffers;
    private final Backend backend;
    private long surface;
    // Deep-colour canvas state: when deepLayer is set, `surface` is an RGB10_A2 IOSurface-backed
    // pbuffer instead of an EGL window surface, and frames reach the screen through
    // MacAngleBridge.presentDeep rather than eglSwapBuffers.
    private long deepLayer;
    private long deepCanvas;
    private int deepWidth;
    private int deepHeight;

    // Front-load LWJGL/ANGLE library setup and EGL capability discovery before the first real renderer is created.
    public static void prewarm() {
        ensureLwjglAngleConfigured();
    }

    public static AngleRenderer window(long nativeWindowHandle) {
        return create(SurfaceKind.WINDOW, nativeWindowHandle, 0, 0);
    }

    public static AngleRenderer pbuffer(int width, int height) {
        return create(SurfaceKind.PBUFFER, 0L, width, height);
    }

    /**
     * Build a renderer, dropping back to an 8-bit canvas if a deeper one cannot be presented.
     *
     * <p>Deep colour is worth having: an 8-bit canvas rounds a smooth gradient to 256 levels on
     * its way to a display that can show more, and that rounding is the one kind of banding the
     * dither is fighting. On macOS ANGLE's Metal backend enumerates only 8-bit window configs, so
     * the deep path does not use a window surface at all: it renders into an RGB10_A2 IOSurface
     * wrapped as an EGL pbuffer (EGL_ANGLE_iosurface_client_buffer takes its format from the
     * pbuffer attributes, not from the config list) and presents it with a native Metal blit into
     * a 10-bit CAMetalLayer. On other platforms the config ladder still asks for RGB10_A2 first.
     * Either way the outcome is logged, so what actually happened is on the record.
     */
    private static AngleRenderer create(SurfaceKind kind, long nativeWindowHandle, int width, int height) {
        try {
            return new AngleRenderer(kind, nativeWindowHandle, width, height);
        } catch (RuntimeException e) {
            if (edrColor && deepColor) {
                Log.warn("EDR canvas failed, falling back to 10 bits per channel", e);
                edrColor = false;
                return create(kind, nativeWindowHandle, width, height);
            }
            if (!deepColor)
                throw e;
            // Deep colour is the only thing asked for here that a driver might refuse, so it is
            // the only thing worth giving up before failing outright.
            Log.warn("Deep-colour canvas failed, falling back to 8 bits per channel", e);
            deepColor = false;
            return new AngleRenderer(kind, nativeWindowHandle, width, height);
        }
    }

    // Off puts the canvas back to exactly the format it has always used, without a rebuild, for
    // anyone whose driver accepts a 10-bit surface and then presents it wrongly.
    private static boolean deepColor =
            !"false".equals(org.helioviewer.jhv.app.Settings.getProperty("display.deepColorCanvas"));

    // EDR is the rung above deep colour: same IOSurface route, half-float canvas, layer tagged
    // linear with EDR requested, so image layers can exceed the interface white. Off leaves the
    // 10-bit canvas exactly as it was.
    private static boolean edrColor =
            !"false".equals(org.helioviewer.jhv.app.Settings.getProperty("display.edrCanvas"));
    private boolean edr; // this renderer's canvas is the EDR rung

    private AngleRenderer(SurfaceKind surfaceKind, long nativeWindowHandle, int pbufferWidth, int pbufferHeight) {
        backend = selectBackend(surfaceKind);
        swapBuffers = surfaceKind.swapBuffers;
        ensureLwjglAngleConfigured();

        long newDisplay = EGL15.EGL_NO_DISPLAY;
        long newContext = EGL15.EGL_NO_CONTEXT;
        long newSurface = EGL15.EGL_NO_SURFACE;
        boolean glesInitialized = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer displayAttrs = stack.pointers(EGL_PLATFORM_ANGLE_TYPE_ANGLE, backend.eglType, EGL15.EGL_NONE);
            // LWJGL's checked wrappers reject native_display == 0 here, so call the function pointer directly.
            // display = org.lwjgl.egl.EGL15.eglGetPlatformDisplay(EGL_PLATFORM_ANGLE_ANGLE, 0L, displayAttrs);
            newDisplay = JNI.callPPP(EGL_PLATFORM_ANGLE_ANGLE, 0L, MemoryUtil.memAddressSafe(displayAttrs), EGL.getCapabilities().eglGetPlatformDisplay);
            if (newDisplay == EGL15.EGL_NO_DISPLAY)
                throw eglError("eglGetPlatformDisplay");
            display = newDisplay;

            IntBuffer major = stack.mallocInt(1);
            IntBuffer minor = stack.mallocInt(1);
            if (!EGL15.eglInitialize(newDisplay, major, minor))
                throw eglError("eglInitialize");
            EGL.createDisplayCapabilities(newDisplay, major.get(0), minor.get(0));
            if (!EGL15.eglBindAPI(EGL15.EGL_OPENGL_ES_API))
                throw eglError("eglBindAPI");

            // The window surface caps the canvas at the 8 bits the config list offers, so the deep
            // path does not use one: it renders into an RGBA16F IOSurface pbuffer (whose format
            // comes from the pbuffer attributes, not the config) and presents it natively.
            boolean deepSurfacePlanned = deepColor && surfaceKind == SurfaceKind.WINDOW && Platform.isMacOS();

            int samples = GL.SAMPLES > 1 ? GL.SAMPLES : 0;
            long newConfig = chooseConfig(stack, newDisplay, samples, surfaceKind.eglBit);
            if (newConfig == 0L)
                throw eglError("eglChooseConfig");
            config = newConfig;
            logChosenConfig(stack, newConfig, deepSurfacePlanned);

            IntBuffer contextAttrs = stack.ints(EGL15.EGL_CONTEXT_CLIENT_VERSION, 3, EGL15.EGL_NONE);
            newContext = EGL15.eglCreateContext(newDisplay, newConfig, EGL15.EGL_NO_CONTEXT, contextAttrs);
            if (newContext == EGL15.EGL_NO_CONTEXT)
                throw eglError("eglCreateContext");

            if (surfaceKind == SurfaceKind.WINDOW) {
                boolean edrPlanned = deepSurfacePlanned && edrColor;
                if (deepSurfacePlanned && MacAngleBridge.prepareDeepLayer(nativeWindowHandle, edrPlanned)) {
                    deepLayer = nativeWindowHandle;
                    edr = edrPlanned;
                    newSurface = createDeepSurface(stack, Math.max(1, Display.getCanvasWidth()), Math.max(1, Display.getCanvasHeight()));
                    Display.deepCanvas = true;
                    Display.edrCanvas = edr;
                    if (edr)
                        Log.info("EDR canvas: RGBA16F IOSurface pbuffer, CAMetalLayer RGBA16Float tagged extended linear sRGB,"
                                + " EDR content requested; headroom is logged after the first frame");
                    else
                        Log.info("Deep-colour canvas: RGB10_A2 IOSurface pbuffer presented by Metal blit;"
                                + " CAMetalLayer pixelFormat=BGR10A2Unorm, colorspace unmanaged as before");
                } else {
                    if (Platform.isMacOS()) {
                        Display.deepCanvas = false;
                        Display.edrCanvas = false;
                        // A failed deep attempt may have left the layer flipped for the blit path;
                        // ANGLE's window surface does its own flip, so undo ours.
                        MacAngleBridge.resetDeepLayer(nativeWindowHandle);
                    }
                    newSurface = EGL15.eglCreateWindowSurface(newDisplay, newConfig, nativeWindowHandle, stack.ints(EGL15.EGL_NONE));
                    if (newSurface == EGL15.EGL_NO_SURFACE)
                        throw eglError("eglCreateWindowSurface");
                }
            } else {
                newSurface = EGL15.eglCreatePbufferSurface(newDisplay, newConfig, stack.ints(
                        EGL15.EGL_WIDTH, pbufferWidth,
                        EGL15.EGL_HEIGHT, pbufferHeight,
                        EGL15.EGL_NONE));
                if (newSurface == EGL15.EGL_NO_SURFACE)
                    throw eglError("eglCreatePbufferSurface");
            }

            if (!EGL15.eglMakeCurrent(newDisplay, newSurface, newSurface, newContext))
                throw eglError("eglMakeCurrent");
            GLES.createCapabilities();
            glesInitialized = true;
            GL.initInfo();
            initRenderer();
        } catch (RuntimeException | Error e) {
            if (glesInitialized)
                GLES.setCapabilities(null);
            if (newDisplay != EGL15.EGL_NO_DISPLAY) {
                EGL15.eglMakeCurrent(newDisplay, EGL15.EGL_NO_SURFACE, EGL15.EGL_NO_SURFACE, EGL15.EGL_NO_CONTEXT);
                if (newSurface != EGL15.EGL_NO_SURFACE)
                    EGL15.eglDestroySurface(newDisplay, newSurface);
                if (newContext != EGL15.EGL_NO_CONTEXT)
                    EGL15.eglDestroyContext(newDisplay, newContext);
                EGL15.eglTerminate(newDisplay);
            }
            if (deepCanvas != 0L) {
                MacAngleBridge.deepCanvasRelease(deepCanvas);
                deepCanvas = 0L;
            }
            throw e;
        }

        context = newContext;
        surface = newSurface;
    }

    public void render(Position viewpoint) {
        if (deepLayer != 0L)
            ensureDeepSurface();
        if (!EGL15.eglMakeCurrent(display, surface, surface, context))
            throw eglError("eglMakeCurrent");
        GLRenderer.display(viewpoint);
        if (deepLayer != 0L) {
            GLES20.glFinish(); // the Metal pass below reads the IOSurface; the GL writes must be done
            if (!MacAngleBridge.presentDeep(deepLayer, deepCanvas, deepWidth, deepHeight, edr))
                Log.warn("Deep-colour present failed for a frame");
            else if (edr) {
                pollHeadroom();
                // The compositor ramps the headroom up over one to two seconds after a frame above
                // white is on screen, and the app renders only on demand; without this the first
                // EDR frame would sit at the bootstrap gain until something else caused a repaint.
                recheckTicks = 0;
                headroomRecheck.restart();
            }
        } else if (swapBuffers && !EGL15.eglSwapBuffers(display, surface))
            throw eglError("eglSwapBuffers");
    }

    // Polls every 250 ms for four seconds after the last present, then stops; a change repaints,
    // which presents, which starts the four seconds again.
    private int recheckTicks;
    private final javax.swing.Timer headroomRecheck = new javax.swing.Timer(250, e -> {
        pollHeadroom();
        if (++recheckTicks >= 16)
            ((javax.swing.Timer) e.getSource()).stop();
    });

    // Publish the screen's headroom readings and repaint when they change, so the gain follows
    // the display (brightness slider, window moved to another screen) within a frame or two.
    private void pollHeadroom() {
        double headroom = MacAngleBridge.edrHeadroom(deepLayer);
        double potential = MacAngleBridge.edrPotential(deepLayer);
        if (withdrawn(headroom, potential))
            return; // not a statement about the display; see below
        if (headroom == Display.edrHeadroom && potential == Display.edrPotential)
            return;
        if (potential > 1)
            grantedOn = deviceId(); // this screen really does have headroom to give
        Display.edrHeadroom = headroom;
        Display.edrPotential = potential;
        // The compositor ramps the headroom up over about a second in forty small steps; every
        // step repaints, but only a real change is worth a line in the log.
        if (Math.abs(headroom - loggedHeadroom) > 0.25 * loggedHeadroom) {
            loggedHeadroom = headroom;
            Log.info("EDR headroom now " + headroom + " of a potential " + potential + " SDR whites; gain setting "
                    + org.helioviewer.jhv.display.HdrGain.setting() + " -> " + org.helioviewer.jhv.display.HdrGain.current(false));
        }
        DisplayController.display();
    }
    private double loggedHeadroom = 1;
    @javax.annotation.Nullable
    private String grantedOn; // the screen a headroom above 1 was last seen on

    /**
     * Whether a reading of 1.0 is the compositor withdrawing the grant rather than the display
     * losing the capability.
     *
     * <p>macOS gives the extended range to the key window, and takes it back from one that is not:
     * both the headroom AND the potential then read 1.0, on the same screen that was reporting 16
     * a moment earlier. Following that down collapses the whole HDR mapping, which is visible as
     * the highlights flattening every time a palette takes the keyboard, and the palettes are
     * separate windows, so it happens constantly. A screen that has offered headroom has not
     * stopped being able to; hold the last real reading until the window is somewhere else.
     *
     * <p>The cost of being wrong is bounded and self-correcting: values past what the compositor
     * will actually show are clipped by it, which is what they would have been anyway, and moving
     * to a genuinely SDR screen changes the device and the readings are believed again.
     */
    private boolean withdrawn(double headroom, double potential) {
        return headroom <= 1 && potential <= 1 && Display.edrPotential > 1
                && java.util.Objects.equals(grantedOn, deviceId());
    }

    @javax.annotation.Nullable
    private static String deviceId() {
        javax.swing.JFrame frame = org.helioviewer.jhv.gui.MainFrame.get();
        java.awt.GraphicsConfiguration gc = frame == null ? null : frame.getGraphicsConfiguration();
        return gc == null ? null : gc.getDevice().getIDstring();
    }

    // Wrap a freshly created RGB10_A2 IOSurface of the given size as the EGL draw surface.
    // On success, deepCanvas/deepWidth/deepHeight describe the new canvas.
    private long createDeepSurface(MemoryStack stack, int width, int height) {
        long ioSurface = MacAngleBridge.deepCanvasCreate(width, height, edr);
        if (ioSurface == 0L)
            throw new RuntimeException("IOSurface creation failed for deep-colour canvas " + width + "x" + height);

        IntBuffer attrs = stack.ints(
                EGL15.EGL_WIDTH, width,
                EGL15.EGL_HEIGHT, height,
                EGL_IOSURFACE_PLANE_ANGLE, 0,
                EGL_TEXTURE_TARGET, EGL_TEXTURE_2D,
                EGL_TEXTURE_INTERNAL_FORMAT_ANGLE, edr ? GL_RGBA : GL_RGB10_A2,
                EGL_TEXTURE_FORMAT, EGL_TEXTURE_RGBA,
                EGL_TEXTURE_TYPE_ANGLE, edr ? GL_HALF_FLOAT : GL_UNSIGNED_INT_2_10_10_10_REV,
                EGL_IOSURFACE_USAGE_HINT_ANGLE, 3, // read | write
                EGL15.EGL_NONE);
        long newSurface = EGL12.eglCreatePbufferFromClientBuffer(display, EGL_IOSURFACE_ANGLE, ioSurface, config, attrs);
        if (newSurface == EGL15.EGL_NO_SURFACE) {
            MacAngleBridge.deepCanvasRelease(ioSurface);
            throw eglError("eglCreatePbufferFromClientBuffer");
        }
        deepCanvas = ioSurface;
        deepWidth = width;
        deepHeight = height;
        return newSurface;
    }

    // The window surface tracked the layer's size by itself; the IOSurface canvas has to be
    // recreated when the canvas size changes. Cheap (one texture allocation) and rare.
    private void ensureDeepSurface() {
        int width = Math.max(1, Display.getCanvasWidth());
        int height = Math.max(1, Display.getCanvasHeight());
        if (width == deepWidth && height == deepHeight)
            return;

        long oldSurface = surface;
        long oldCanvas = deepCanvas;
        int oldWidth = deepWidth;
        int oldHeight = deepHeight;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long newSurface = createDeepSurface(stack, width, height);
            if (!EGL15.eglMakeCurrent(display, newSurface, newSurface, context)) {
                EGL15.eglDestroySurface(display, newSurface);
                MacAngleBridge.deepCanvasRelease(deepCanvas);
                deepCanvas = oldCanvas;
                deepWidth = oldWidth;
                deepHeight = oldHeight;
                throw eglError("eglMakeCurrent");
            }
            surface = newSurface;
            EGL15.eglDestroySurface(display, oldSurface);
            MacAngleBridge.deepCanvasRelease(oldCanvas);
        }
    }

    public void destroy() {
        if (!EGL15.eglMakeCurrent(display, surface, surface, context))
            throw eglError("eglMakeCurrent");

        try {
            if (rendererInitialized) {
                try {
                    GLRenderer.dispose();
                } finally {
                    rendererInitialized = false;
                }
            }
        } finally {
            GLES.setCapabilities(null);
            EGL15.eglMakeCurrent(display, EGL15.EGL_NO_SURFACE, EGL15.EGL_NO_SURFACE, EGL15.EGL_NO_CONTEXT);
            EGL15.eglDestroySurface(display, surface);
            EGL15.eglDestroyContext(display, context);
            EGL15.eglTerminate(display);
            if (deepCanvas != 0L) {
                MacAngleBridge.deepCanvasRelease(deepCanvas);
                deepCanvas = 0L;
            }
        }
    }

    private static void initRenderer() {
        if (rendererInitialized)
            return;
        GLRenderer.init();
        rendererInitialized = true;
    }

    private static synchronized void ensureLwjglAngleConfigured() {
        if (lwjglConfigured)
            return;
        AngleLibraries.configureLwjglAngleLibraries();
        EGL.getCapabilities();
        lwjglConfigured = true;
    }

    // Ten bits first, then the eight this has always used. Ordered, not preferred-with-fallback:
    // eglChooseConfig returns nothing at all when the requested sizes cannot be met, so each is a
    // separate question rather than a hint.
    private static final int[] COLOR_PREFERENCES = {10, 8};

    private static long chooseConfig(MemoryStack stack, long display, int samples, int surfaceType) {
        for (int colorBits : deepColor ? COLOR_PREFERENCES : new int[]{8}) {
            for (int depthBits : DEPTH_PREFERENCES) {
                if (samples > 0) {
                    long config = chooseConfig(stack, display, colorBits, depthBits, samples, surfaceType);
                    if (config != 0L)
                        return config;
                }
            }
            for (int depthBits : DEPTH_PREFERENCES) {
                long config = chooseConfig(stack, display, colorBits, depthBits, 0, surfaceType);
                if (config != 0L)
                    return config;
            }
        }
        return 0L;
    }

    private static long chooseConfig(MemoryStack stack, long display, int colorBits, int depthBits, int samples, int surfaceType) {
        PointerBuffer configOut = stack.mallocPointer(1);
        IntBuffer numConfigs = stack.mallocInt(1);
        int attributeCount = samples > 0 ? 19 : 15;
        IntBuffer configAttrs = stack.mallocInt(attributeCount);
        configAttrs.put(EGL15.EGL_SURFACE_TYPE).put(surfaceType);
        configAttrs.put(EGL15.EGL_RENDERABLE_TYPE).put(EGL_OPENGL_ES3_BIT);
        configAttrs.put(EGL15.EGL_RED_SIZE).put(colorBits);
        configAttrs.put(EGL15.EGL_GREEN_SIZE).put(colorBits);
        configAttrs.put(EGL15.EGL_BLUE_SIZE).put(colorBits);
        // A 10-bit surface is RGB10_A2: there is no 10/10/10/8, and asking for one finds nothing.
        configAttrs.put(EGL15.EGL_ALPHA_SIZE).put(colorBits == 10 ? 2 : 8);
        configAttrs.put(EGL15.EGL_DEPTH_SIZE).put(depthBits);
        if (samples > 0) {
            configAttrs.put(EGL15.EGL_SAMPLE_BUFFERS).put(1);
            configAttrs.put(EGL15.EGL_SAMPLES).put(samples);
        }
        configAttrs.put(EGL15.EGL_NONE);
        configAttrs.flip();

        if (!EGL15.eglChooseConfig(display, configAttrs, configOut, numConfigs) || numConfigs.get(0) <= 0)
            return 0L;
        return configOut.get(0);
    }

    private void logChosenConfig(MemoryStack stack, long config, boolean deepSurfacePlanned) {
        IntBuffer attribValue = stack.mallocInt(1);
        int red = configAttrib(attribValue, config, EGL15.EGL_RED_SIZE);
        int green = configAttrib(attribValue, config, EGL15.EGL_GREEN_SIZE);
        int blue = configAttrib(attribValue, config, EGL15.EGL_BLUE_SIZE);
        int alpha = configAttrib(attribValue, config, EGL15.EGL_ALPHA_SIZE);
        int depth = configAttrib(attribValue, config, EGL15.EGL_DEPTH_SIZE);
        int stencil = configAttrib(attribValue, config, EGL15.EGL_STENCIL_SIZE);
        int sampleBuffers = configAttrib(attribValue, config, EGL15.EGL_SAMPLE_BUFFERS);
        int samples = configAttrib(attribValue, config, EGL15.EGL_SAMPLES);

        Log.info("ANGLE EGL config: backend=" + backend.label
                + " rgba=" + red + "/" + green + "/" + blue + "/" + alpha
                + " depth=" + depth
                + " stencil=" + stencil
                + " sampleBuffers=" + sampleBuffers
                + " samples=" + samples
                // Worth stating outright rather than leaving to be read off the numbers: an 8-bit
                // canvas is the reason a smooth gradient is rounded to 256 levels on a display
                // that can show more, and the reason the dither exists at all. When the deep
                // IOSurface canvas is about to be attempted, the config only supplies the context
                // and depth buffer, so its channel sizes do not describe the canvas.
                + (deepSurfacePlanned ? " (config for context only; deep canvas attempt follows)"
                        : red >= 10 ? " (deep colour)"
                        : deepColor ? " (deep colour asked for, not offered)" : ""));
    }

    private int configAttrib(IntBuffer value, long config, int attribute) {
        if (!EGL15.eglGetConfigAttrib(display, config, attribute, value))
            throw eglError("eglGetConfigAttrib");
        return value.get(0);
    }

    private RuntimeException eglError(String step) {
        int code = EGL15.eglGetError();
        if (code == EGL15.EGL_SUCCESS)
            return new RuntimeException(step + " failed without EGL error; backend=" + backend.label);
        return new RuntimeException(step + " failed with EGL error 0x" + Integer.toHexString(code) + "; backend=" + backend.label);
    }

}
