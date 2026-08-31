package org.helioviewer.jhv.opengl.angle;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.IntBuffer;

import org.helioviewer.jhv.app.Platform;

import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL12;
import org.lwjgl.egl.EGL15;
import org.lwjgl.opengles.GLES;
import org.lwjgl.opengles.GLES20;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Establish whether ANGLE's Metal backend will render into a deep-colour IOSurface wrapped as an
 * EGL pbuffer, which is the route around the 8-bit-only EGL config list.
 *
 * <p>For each candidate format (BGRA8 control, RGBA16F, RGB10A2) this creates an IOSurface, wraps
 * it with eglCreatePbufferFromClientBuffer(EGL_IOSURFACE_ANGLE), makes it CURRENT (not
 * bindTexImage: the app needs it as the draw surface so framebuffer 0 keeps meaning the canvas),
 * clears to a colour that 8 bits cannot represent, and reads the IOSurface memory back directly.
 * More than 8 bits surviving the round trip is the finding.
 *
 * <p>Run (dylib built from extra/test/native/probe_iosurface.m; build line in its header):
 * java --enable-native-access=ALL-UNNAMED -Djhv.probe.dylib=/path/to/libjhvprobe.dylib
 *      -cp "extra/test-classes:bin:resources:$(find lib -name '*.jar' | tr '\n' ':')"
 *      org.helioviewer.jhv.opengl.angle.IOSurfacePbufferProbe
 *
 * <p>Measured 2026-08-31 on an M4 Max (ANGLE 2.1.27045): all three formats create, make
 * current, and clear correctly; RGBA16F round-trips with max error 7.3e-5 and RGB10A2 with
 * 2.4e-4, versus 1.57e-3 for the 8-bit control. See docs/deep-colour-canvas-brief.md.
 */
public final class IOSurfacePbufferProbe {

    private static final int EGL_PLATFORM_ANGLE_ANGLE = 0x3202;
    private static final int EGL_PLATFORM_ANGLE_TYPE_ANGLE = 0x3203;
    private static final int EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE = 0x3489;
    private static final int EGL_OPENGL_ES3_BIT = 0x00000040;

    private static final int EGL_IOSURFACE_ANGLE = 0x3454;
    private static final int EGL_IOSURFACE_PLANE_ANGLE = 0x345A;
    private static final int EGL_TEXTURE_TYPE_ANGLE = 0x345C;
    private static final int EGL_TEXTURE_INTERNAL_FORMAT_ANGLE = 0x345D;
    private static final int EGL_IOSURFACE_USAGE_HINT_ANGLE = 0x348A;
    private static final int EGL_TEXTURE_FORMAT = 0x3080;
    private static final int EGL_TEXTURE_TARGET = 0x3081;
    private static final int EGL_TEXTURE_RGBA = 0x305E;
    private static final int EGL_TEXTURE_2D = 0x305F;

    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_HALF_FLOAT = 0x140B;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_BGRA_EXT = 0x80E1;
    private static final int GL_RGB10_A2 = 0x8059;
    private static final int GL_UNSIGNED_INT_2_10_10_10_REV = 0x8368;
    private static final int GL_DEPTH_BITS = 0x0D56;

    // Chosen so the nearest 8-bit level is ~1.6e-3 away while half (~2.4e-4) and 10-bit
    // (~4.9e-4) levels are closer: an error under this threshold proves more than 8 bits landed.
    private static final float[] CLEAR = {0.3937255f, 0.7480392f, 0.1240196f, 1f};
    private static final float DEEP_THRESHOLD = 8e-4f;

    private static MethodHandle surfCreate, surfRelease, surfRead;

    public static void main(String[] args) {
        Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        AngleLibraries.configureLwjglAngleLibraries();
        EGL.getCapabilities();
        bindProbeLibrary();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer displayAttrs = stack.pointers(EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE, EGL15.EGL_NONE);
            long display = JNI.callPPP(EGL_PLATFORM_ANGLE_ANGLE, 0L, MemoryUtil.memAddressSafe(displayAttrs),
                    EGL.getCapabilities().eglGetPlatformDisplay);
            if (display == EGL15.EGL_NO_DISPLAY)
                throw new RuntimeException("no Metal display");
            IntBuffer major = stack.mallocInt(1), minor = stack.mallocInt(1);
            if (!EGL15.eglInitialize(display, major, minor))
                throw new RuntimeException("eglInitialize failed");
            EGL.createDisplayCapabilities(display, major.get(0), minor.get(0));
            if (!EGL15.eglBindAPI(EGL15.EGL_OPENGL_ES_API))
                throw new RuntimeException("eglBindAPI failed");

            long config = chooseConfig(stack, display);
            if (config == 0L)
                throw new RuntimeException("no pbuffer-capable config");

            IntBuffer contextAttrs = stack.ints(EGL15.EGL_CONTEXT_CLIENT_VERSION, 3, EGL15.EGL_NONE);
            long context = EGL15.eglCreateContext(display, config, EGL15.EGL_NO_CONTEXT, contextAttrs);
            if (context == EGL15.EGL_NO_CONTEXT)
                throw new RuntimeException("eglCreateContext failed: 0x" + Integer.toHexString(EGL15.eglGetError()));

            probeFormat(stack, display, config, context, "BGRA8 (control)", 0x42475241, 4, GL_BGRA_EXT, GL_UNSIGNED_BYTE);
            probeFormat(stack, display, config, context, "RGBA16F", 0x52476841, 8, GL_RGBA, GL_HALF_FLOAT);
            probeFormat(stack, display, config, context, "RGB10A2", 0x6C313072, 4, GL_RGB10_A2, GL_UNSIGNED_INT_2_10_10_10_REV);

            EGL15.eglMakeCurrent(display, EGL15.EGL_NO_SURFACE, EGL15.EGL_NO_SURFACE, EGL15.EGL_NO_CONTEXT);
            EGL15.eglDestroyContext(display, context);
            EGL15.eglTerminate(display);
        }
    }

    private static boolean glesReady;

    private static void probeFormat(MemoryStack stack, long display, long config, long context,
                                    String name, int fourcc, int bytesPerElement, int internalFormat, int type) {
        int w = 64, h = 64;
        long ioSurface = createIOSurface(w, h, fourcc, bytesPerElement);
        if (ioSurface == 0L) {
            System.out.println(name + ": IOSurfaceCreate FAILED");
            return;
        }
        try {
            IntBuffer attrs = stack.ints(
                    EGL15.EGL_WIDTH, w,
                    EGL15.EGL_HEIGHT, h,
                    EGL_IOSURFACE_PLANE_ANGLE, 0,
                    EGL_TEXTURE_TARGET, EGL_TEXTURE_2D,
                    EGL_TEXTURE_INTERNAL_FORMAT_ANGLE, internalFormat,
                    EGL_TEXTURE_FORMAT, EGL_TEXTURE_RGBA,
                    EGL_TEXTURE_TYPE_ANGLE, type,
                    EGL_IOSURFACE_USAGE_HINT_ANGLE, 3, // read | write
                    EGL15.EGL_NONE);
            long surface = EGL12.eglCreatePbufferFromClientBuffer(display, EGL_IOSURFACE_ANGLE, ioSurface, config, attrs);
            if (surface == EGL15.EGL_NO_SURFACE) {
                System.out.println(name + ": eglCreatePbufferFromClientBuffer FAILED, error 0x"
                        + Integer.toHexString(EGL15.eglGetError()));
                return;
            }
            if (!EGL15.eglMakeCurrent(display, surface, surface, context)) {
                System.out.println(name + ": eglMakeCurrent FAILED, error 0x" + Integer.toHexString(EGL15.eglGetError()));
                EGL15.eglDestroySurface(display, surface);
                return;
            }
            if (!glesReady) {
                GLES.createCapabilities();
                glesReady = true;
            }

            GLES20.glClearColor(CLEAR[0], CLEAR[1], CLEAR[2], CLEAR[3]);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glFinish();
            int glError = GLES20.glGetError();
            int depthBits = GLES20.glGetInteger(GL_DEPTH_BITS);

            float[] rgba = readPixel(stack, ioSurface, w / 2, h / 2, bytesPerElement, type);
            float err = Math.max(Math.abs(rgba[0] - CLEAR[0]),
                    Math.max(Math.abs(rgba[1] - CLEAR[1]), Math.abs(rgba[2] - CLEAR[2])));
            System.out.printf("%s: current=OK depthBits=%d glError=0x%x rgba=(%.7f, %.7f, %.7f, %.4f) maxErr=%.2e -> %s%n",
                    name, depthBits, glError, rgba[0], rgba[1], rgba[2], rgba[3], err,
                    err < DEEP_THRESHOLD ? "DEEPER THAN 8 BITS" : "8-bit (or failed)");

            EGL15.eglMakeCurrent(display, EGL15.EGL_NO_SURFACE, EGL15.EGL_NO_SURFACE, EGL15.EGL_NO_CONTEXT);
            EGL15.eglDestroySurface(display, surface);
        } finally {
            releaseIOSurface(ioSurface);
        }
    }

    private static float[] readPixel(MemoryStack stack, long ioSurface, int x, int y, int bytesPerElement, int type) {
        MemorySegment out = MemorySegment.ofBuffer(stack.malloc(bytesPerElement)).reinterpret(bytesPerElement);
        try {
            int rc = (int) surfRead.invokeExact(MemorySegment.ofAddress(ioSurface).reinterpret(Long.MAX_VALUE),
                    x, y, bytesPerElement, out, bytesPerElement);
            if (rc != 0)
                throw new RuntimeException("IOSurface read failed: " + rc);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return switch (type) {
            case GL_HALF_FLOAT -> new float[]{
                    Float.float16ToFloat(out.get(ValueLayout.JAVA_SHORT, 0)),
                    Float.float16ToFloat(out.get(ValueLayout.JAVA_SHORT, 2)),
                    Float.float16ToFloat(out.get(ValueLayout.JAVA_SHORT, 4)),
                    Float.float16ToFloat(out.get(ValueLayout.JAVA_SHORT, 6))};
            case GL_UNSIGNED_INT_2_10_10_10_REV -> {
                int v = out.get(ValueLayout.JAVA_INT, 0);
                yield new float[]{((v >> 20) & 0x3FF) / 1023f, ((v >> 10) & 0x3FF) / 1023f, (v & 0x3FF) / 1023f, ((v >>> 30) & 3) / 3f};
            }
            default -> new float[]{ // BGRA byte order
                    (out.get(ValueLayout.JAVA_BYTE, 2) & 0xFF) / 255f,
                    (out.get(ValueLayout.JAVA_BYTE, 1) & 0xFF) / 255f,
                    (out.get(ValueLayout.JAVA_BYTE, 0) & 0xFF) / 255f,
                    (out.get(ValueLayout.JAVA_BYTE, 3) & 0xFF) / 255f};
        };
    }

    private static long chooseConfig(MemoryStack stack, long display) {
        IntBuffer attrs = stack.ints(
                EGL15.EGL_SURFACE_TYPE, EGL15.EGL_PBUFFER_BIT,
                EGL15.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL15.EGL_RED_SIZE, 8, EGL15.EGL_GREEN_SIZE, 8, EGL15.EGL_BLUE_SIZE, 8, EGL15.EGL_ALPHA_SIZE, 8,
                EGL15.EGL_DEPTH_SIZE, 24,
                EGL15.EGL_NONE);
        PointerBuffer configOut = stack.mallocPointer(1);
        IntBuffer numConfigs = stack.mallocInt(1);
        if (!EGL15.eglChooseConfig(display, attrs, configOut, numConfigs) || numConfigs.get(0) <= 0)
            return 0L;
        return configOut.get(0);
    }

    private static long createIOSurface(int w, int h, int fourcc, int bytesPerElement) {
        try {
            return ((MemorySegment) surfCreate.invokeExact(w, h, fourcc, bytesPerElement)).address();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static void releaseIOSurface(long surf) {
        try {
            surfRelease.invokeExact(MemorySegment.ofAddress(surf).reinterpret(Long.MAX_VALUE));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static void bindProbeLibrary() {
        String path = System.getProperty("jhv.probe.dylib");
        if (path == null)
            throw new RuntimeException("set -Djhv.probe.dylib=/path/to/libjhvprobe.dylib");
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.libraryLookup(path, Arena.global());
        surfCreate = linker.downcallHandle(lookup.find("jhv_probe_iosurface_create").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        surfRelease = linker.downcallHandle(lookup.find("jhv_probe_iosurface_release").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        surfRead = linker.downcallHandle(lookup.find("jhv_probe_iosurface_read").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    }

}
