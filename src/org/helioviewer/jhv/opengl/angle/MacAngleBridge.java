package org.helioviewer.jhv.opengl.angle;

import java.awt.Canvas;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import org.helioviewer.jhv.app.Log;

@SuppressWarnings("restricted")
public final class MacAngleBridge {
    public record Host(long handle, long layer) {}

    private static final Arena ARENA = Arena.ofShared();
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.libraryLookup(
            AngleLibraries.libraryPath("libjhvmetalhost.dylib"), ARENA);

    private static final MethodHandle CREATE = downcall("jhv_metal_host_create",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));
    private static final MethodHandle GET_LAYER = downcall("jhv_metal_host_get_layer",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SET_FRAME = downcall("jhv_metal_host_set_frame",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
                    ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));
    private static final MethodHandle SET_FRAME_SYNC = downcall("jhv_metal_host_set_frame_sync",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
                    ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));
    private static final MethodHandle DESTROY = downcall("jhv_metal_host_destroy",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    private static final MethodHandle DEVICE_INFO = downcall("jhv_metal_device_info",
            FunctionDescriptor.of(ValueLayout.ADDRESS));
    private static final MethodHandle PREPARE_DEEP = downcall("jhv_metal_host_prepare_deep",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle RESET_DEEP = downcall("jhv_metal_host_reset_deep",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    private static final MethodHandle DEEP_CANVAS_CREATE = downcall("jhv_deep_canvas_create",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle DEEP_CANVAS_RELEASE = downcall("jhv_deep_canvas_release",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    private static final MethodHandle PRESENT_DEEP = downcall("jhv_metal_host_present_deep",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    public static void prewarm() {
        // Force class initialization and native symbol resolution before the first canvas attach.
        Log.info("Metal device: " + deviceInfo());
    }

    private static String deviceInfo() {
        try {
            MemorySegment info = (MemorySegment) DEVICE_INFO.invokeExact();
            if (info.address() == 0L)
                return "unavailable";
            return info.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to query Metal device info", t);
        }
    }

    public static Host create(Canvas canvas, double x, double y, double width, double height) {
        return AngleJAWT.withPlatformInfo(canvas, platformInfo -> {
            if (platformInfo == 0L)
                return null;

            long handle = 0L;
            try {
                MemorySegment surfaceLayers = MemorySegment.ofAddress(platformInfo);
                handle = ((MemorySegment) CREATE.invokeExact(surfaceLayers, x, y, width, height)).address();
                if (handle == 0L)
                    return null;

                MemorySegment metalHost = MemorySegment.ofAddress(handle);
                long layer = ((MemorySegment) GET_LAYER.invokeExact(metalHost)).address();
                if (layer == 0L) {
                    DESTROY.invokeExact(metalHost);
                    handle = 0L;
                    throw new IllegalStateException("Metal host did not expose a CAMetalLayer");
                }
                return new Host(handle, layer);
            } catch (Throwable t) {
                if (handle != 0L)
                    destroy(handle);
                throw new RuntimeException("Failed to create Metal host layer", t);
            }
        });
    }

    public static void setFrame(long handle, double x, double y, double width, double height) {
        try {
            MemorySegment metalHost = MemorySegment.ofAddress(handle);
            SET_FRAME.invokeExact(metalHost, x, y, width, height);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to resize Metal host layer", t);
        }
    }

    // Blocks until the layer frame + drawableSize are updated, so an immediate render is at-size.
    public static void setFrameSync(long handle, double x, double y, double width, double height) {
        try {
            MemorySegment metalHost = MemorySegment.ofAddress(handle);
            SET_FRAME_SYNC.invokeExact(metalHost, x, y, width, height);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to resize Metal host layer", t);
        }
    }

    public static void destroy(long handle) {
        if (handle == 0L)
            return;

        try {
            MemorySegment metalHost = MemorySegment.ofAddress(handle);
            DESTROY.invokeExact(metalHost);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to destroy Metal host layer", t);
        }
    }

    // Switch the CAMetalLayer to a half-float pixel format for the deep-colour canvas.
    public static boolean prepareDeepLayer(long layer) {
        try {
            return (int) PREPARE_DEEP.invokeExact(MemorySegment.ofAddress(layer)) != 0;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to prepare deep-colour layer", t);
        }
    }

    // Undo prepareDeepLayer's flip before ANGLE takes the layer back as a window surface.
    public static void resetDeepLayer(long layer) {
        try {
            RESET_DEEP.invokeExact(MemorySegment.ofAddress(layer));
        } catch (Throwable t) {
            throw new RuntimeException("Failed to reset deep-colour layer", t);
        }
    }

    // An RGB10_A2 IOSurface for the canvas; 0 on failure. Release with deepCanvasRelease.
    public static long deepCanvasCreate(int width, int height) {
        try {
            return ((MemorySegment) DEEP_CANVAS_CREATE.invokeExact(width, height)).address();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create deep-colour canvas IOSurface", t);
        }
    }

    public static void deepCanvasRelease(long ioSurface) {
        if (ioSurface == 0L)
            return;
        try {
            DEEP_CANVAS_RELEASE.invokeExact(MemorySegment.ofAddress(ioSurface));
        } catch (Throwable t) {
            throw new RuntimeException("Failed to release deep-colour canvas IOSurface", t);
        }
    }

    // Blit the rendered IOSurface into the layer's drawable and present it. Call after glFinish.
    public static boolean presentDeep(long layer, long ioSurface, int width, int height) {
        try {
            return (int) PRESENT_DEEP.invokeExact(MemorySegment.ofAddress(layer),
                    MemorySegment.ofAddress(ioSurface), width, height) != 0;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to present deep-colour canvas", t);
        }
    }

    private static MethodHandle downcall(String symbol, FunctionDescriptor descriptor) {
        MemorySegment function = LOOKUP.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError(symbol));
        return LINKER.downcallHandle(function, descriptor);
    }

    private MacAngleBridge() {}
}
