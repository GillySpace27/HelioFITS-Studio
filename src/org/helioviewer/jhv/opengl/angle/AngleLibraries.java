package org.helioviewer.jhv.opengl.angle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.FileUtils;

import org.lwjgl.system.Configuration;

final class AngleLibraries {
    private static final String METAL_HOST_LIBRARY = "libjhvmetalhost.dylib";
    private static final Path MACOS_NATIVE_DIR = Path.of("lib", "natives-macos");
    private static final String SWIFTSHADER_ICD_ENV = "VK_ICD_FILENAMES";
    private static boolean swiftShaderLoaded;

    static void configureLwjglAngleLibraries() {
        Configuration.EGL_LIBRARY_NAME.set(libraryPath(eglLibrary()).toString());
        Configuration.OPENGLES_LIBRARY_NAME.set(libraryPath(openGlesLibrary()).toString());
    }

    @SuppressWarnings("restricted")
    static synchronized boolean loadSwiftShader() {
        String icd = System.getenv(SWIFTSHADER_ICD_ENV);
        if (icd == null || icd.isBlank())
            return false;
        if (swiftShaderLoaded)
            return true;

        Path icdPath = Path.of(icd.split(File.pathSeparator, 2)[0]);
        if (!Files.isRegularFile(icdPath))
            throw new RuntimeException(SWIFTSHADER_ICD_ENV + " does not point to a file: " + icdPath);

        Path libraryPath = icdPath.resolveSibling(swiftShaderLibrary());
        if (!Files.isRegularFile(libraryPath))
            throw new RuntimeException("SwiftShader library not found next to " + icdPath + ": " + libraryPath);
        System.load(libraryPath.toString());
        swiftShaderLoaded = true;
        return true;
    }

    static Path libraryPath(String fileName) {
        if (Platform.isMacOS() && METAL_HOST_LIBRARY.equals(fileName)) {
            Path path = MACOS_NATIVE_DIR.resolve(fileName).toAbsolutePath();
            if (Files.exists(path))
                return path;
            // The path above is cwd-relative and a launch from elsewhere (java -jar with an
            // absolute path, the zip's launchers) never finds it: ANGLE warmup then dies with
            // nothing to say. The dylib sits beside the jar in the repo and in the zip, so ask
            // where this class was loaded from. The notarized .app needs neither branch: its
            // dylib is injected into the jar and the resource fallback below serves it.
            try {
                Path code = Path.of(AngleLibraries.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Path byJar = code.getParent().resolve(MACOS_NATIVE_DIR).resolve(fileName);
                if (Files.isRegularFile(byJar))
                    return byJar;
            } catch (Exception ignore) {
            }
        }

        String resourcePath = Platform.getResourceDir() + fileName;
        try (InputStream in = FileUtils.getResource(resourcePath)) {
            Path extractedPath = Path.of(Directories.libCacheDir, fileName);
            Files.copy(in, extractedPath, StandardCopyOption.REPLACE_EXISTING);
            return extractedPath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to resolve native library " + fileName + " from " + resourcePath, e);
        }
    }

    private static String eglLibrary() {
        if (Platform.isMacOS())
            return "libEGL.dylib";
        if (Platform.isWindows())
            return "libEGL.dll";
        if (Platform.isLinux())
            return "libEGL.so";
        throw new IllegalStateException("Unsupported ANGLE platform");
    }

    private static String openGlesLibrary() {
        if (Platform.isMacOS())
            return "libGLESv2.dylib";
        if (Platform.isWindows())
            return "libGLESv2.dll";
        if (Platform.isLinux())
            return "libGLESv2.so";
        throw new IllegalStateException("Unsupported ANGLE platform");
    }

    private static String swiftShaderLibrary() {
        if (Platform.isMacOS())
            return "libvk_swiftshader.dylib";
        if (Platform.isWindows())
            return "vk_swiftshader.dll";
        if (Platform.isLinux())
            return "libvk_swiftshader.so";
        throw new IllegalStateException("Unsupported ANGLE platform");
    }

    private AngleLibraries() {}
}
