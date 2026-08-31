package org.helioviewer.jhv.opengl.angle;

import java.nio.IntBuffer;

import org.helioviewer.jhv.app.Platform;

import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL15;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Enumerate every EGL config and extension ANGLE offers here, so "8 bits is all there is" is a
 * finding rather than an assumption.
 *
 * <p>Lives in the ANGLE package so it can reach the platform constants and the library setup.
 *
 * Run: java -cp bin:extra/test-classes:lib/* org.helioviewer.jhv.opengl.angle.EglConfigProbe
 */
public final class EglConfigProbe {

    private static final int EGL_PLATFORM_ANGLE_ANGLE = 0x3202;
    private static final int EGL_PLATFORM_ANGLE_TYPE_ANGLE = 0x3203;
    private static final int EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE = 0x3489;
    private static final int EGL_PLATFORM_ANGLE_TYPE_OPENGL_ANGLE = 0x320D;
    private static final int EGL_PLATFORM_ANGLE_TYPE_VULKAN_ANGLE = 0x3450;
    private static final int EGL_COLOR_COMPONENT_TYPE_EXT = 0x3339;
    private static final int EGL_COLOR_COMPONENT_TYPE_FIXED_EXT = 0x333A;
    private static final int EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT = 0x333B;

    public static void main(String[] args) {
        Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        AngleLibraries.configureLwjglAngleLibraries();
        EGL.getCapabilities();

        for (int[] backend : new int[][]{
                {EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE, 0},
                {EGL_PLATFORM_ANGLE_TYPE_OPENGL_ANGLE, 1},
                {EGL_PLATFORM_ANGLE_TYPE_VULKAN_ANGLE, 2}}) {
            String name = switch (backend[1]) {
                case 0 -> "Metal";
                case 1 -> "OpenGL";
                default -> "Vulkan";
            };
            try {
                probe(name, backend[0]);
            } catch (Throwable t) {
                System.out.println("\n=== " + name + ": unavailable (" + t + ")");
            }
        }
    }

    private static void probe(String name, int backendType) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer displayAttrs = stack.pointers(EGL_PLATFORM_ANGLE_TYPE_ANGLE, backendType, EGL15.EGL_NONE);
            long display = JNI.callPPP(EGL_PLATFORM_ANGLE_ANGLE, 0L, MemoryUtil.memAddressSafe(displayAttrs),
                    EGL.getCapabilities().eglGetPlatformDisplay);
            if (display == EGL15.EGL_NO_DISPLAY) {
                System.out.println("\n=== " + name + ": no display");
                return;
            }
            IntBuffer major = stack.mallocInt(1), minor = stack.mallocInt(1);
            if (!EGL15.eglInitialize(display, major, minor)) {
                System.out.println("\n=== " + name + ": eglInitialize failed");
                return;
            }
            EGL.createDisplayCapabilities(display, major.get(0), minor.get(0));

            System.out.println("\n=== " + name + " (EGL " + major.get(0) + "." + minor.get(0) + ") ===");
            String ext = EGL15.eglQueryString(display, EGL15.EGL_EXTENSIONS);
            System.out.println("  display extensions:");
            for (String have : ext.split(" "))
                if (!have.isBlank())
                    System.out.println("    " + have);

            IntBuffer numConfigs = stack.mallocInt(1);
            EGL15.eglGetConfigs(display, null, numConfigs);
            int total = numConfigs.get(0);
            PointerBuffer configs = stack.mallocPointer(total);
            EGL15.eglGetConfigs(display, configs, numConfigs);

            java.util.TreeSet<String> shapes = new java.util.TreeSet<>();
            for (int i = 0; i < numConfigs.get(0); i++) {
                long c = configs.get(i);
                int r = attrib(display, c, EGL15.EGL_RED_SIZE, stack);
                int g = attrib(display, c, EGL15.EGL_GREEN_SIZE, stack);
                int b = attrib(display, c, EGL15.EGL_BLUE_SIZE, stack);
                int a = attrib(display, c, EGL15.EGL_ALPHA_SIZE, stack);
                int type = attrib(display, c, EGL_COLOR_COMPONENT_TYPE_EXT, stack);
                int surf = attrib(display, c, EGL15.EGL_SURFACE_TYPE, stack);
                String kind = type == EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT ? "float"
                        : type == EGL_COLOR_COMPONENT_TYPE_FIXED_EXT ? "fixed" : "type?" + type;
                shapes.add(String.format("rgba=%2d/%2d/%2d/%2d %-6s window=%s pbuffer=%s",
                        r, g, b, a, kind,
                        (surf & EGL15.EGL_WINDOW_BIT) != 0, (surf & EGL15.EGL_PBUFFER_BIT) != 0));
            }
            System.out.println("  " + total + " configs, distinct shapes:");
            for (String s : shapes)
                System.out.println("    " + s);
            EGL15.eglTerminate(display);
        }
    }

    private static int attrib(long display, long config, int attribute, MemoryStack stack) {
        IntBuffer value = stack.mallocInt(1);
        return EGL15.eglGetConfigAttrib(display, config, attribute, value) ? value.get(0) : -1;
    }

}
