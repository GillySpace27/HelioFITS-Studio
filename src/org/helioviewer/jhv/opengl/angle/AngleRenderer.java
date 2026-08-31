package org.helioviewer.jhv.opengl.angle;

import java.nio.IntBuffer;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.opengl.GLRenderer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL15;
import org.lwjgl.opengles.GLES;
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
    private final long display;
    private final long context;
    private final long surface;
    private final boolean swapBuffers;
    private final Backend backend;

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
     * <p>Ten bits per channel is worth asking for: the whole render path is 8-bit today, so a
     * smooth gradient is rounded to 256 levels on its way to a display that can show more, and
     * that rounding is the one kind of banding the dither is fighting. Four times the levels
     * removes most of the need for it.
     *
     * <p>Asked for rather than assumed, because whether a 10-bit window surface exists is a
     * property of the driver, the backend and the display, none of which this code can know. The
     * chosen format is logged either way, so what actually happened is on the record.
     */
    private static AngleRenderer create(SurfaceKind kind, long nativeWindowHandle, int width, int height) {
        try {
            return new AngleRenderer(kind, nativeWindowHandle, width, height);
        } catch (RuntimeException e) {
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

            int samples = GL.SAMPLES > 1 ? GL.SAMPLES : 0;
            long config = chooseConfig(stack, newDisplay, samples, surfaceKind.eglBit);
            if (config == 0L)
                throw eglError("eglChooseConfig");
            logChosenConfig(stack, config);

            IntBuffer contextAttrs = stack.ints(EGL15.EGL_CONTEXT_CLIENT_VERSION, 3, EGL15.EGL_NONE);
            newContext = EGL15.eglCreateContext(newDisplay, config, EGL15.EGL_NO_CONTEXT, contextAttrs);
            if (newContext == EGL15.EGL_NO_CONTEXT)
                throw eglError("eglCreateContext");

            if (surfaceKind == SurfaceKind.WINDOW) {
                newSurface = EGL15.eglCreateWindowSurface(newDisplay, config, nativeWindowHandle, stack.ints(EGL15.EGL_NONE));
                if (newSurface == EGL15.EGL_NO_SURFACE)
                    throw eglError("eglCreateWindowSurface");
            } else {
                newSurface = EGL15.eglCreatePbufferSurface(newDisplay, config, stack.ints(
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
            throw e;
        }

        context = newContext;
        surface = newSurface;
    }

    public void render(Position viewpoint) {
        if (!EGL15.eglMakeCurrent(display, surface, surface, context))
            throw eglError("eglMakeCurrent");
        GLRenderer.display(viewpoint);
        if (swapBuffers && !EGL15.eglSwapBuffers(display, surface))
            throw eglError("eglSwapBuffers");
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

    private void logChosenConfig(MemoryStack stack, long config) {
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
                // Worth stating outright rather than leaving to be read off the numbers: it is the
                // reason a smooth gradient is rounded to 256 levels on a display that can show
                // more, and the reason the dither exists at all.
                + (red >= 10 ? " (deep colour)" : deepColor ? " (deep colour asked for, not offered)" : ""));
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
