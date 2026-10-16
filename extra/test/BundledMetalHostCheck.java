package org.helioviewer.jhv.opengl.angle;

import java.nio.file.Files;
import java.nio.file.Path;

import org.helioviewer.jhv.app.Platform;

/**
 * The packaged app loads its own Metal host dylib, never one found under the directory it was started in.
 *
 * <p>libraryPath looked for lib/natives-macos/libjhvmetalhost.dylib relative to the working directory
 * first. Started from a checkout whose tracked dylib was older, the published 0.8.6 app loaded that copy
 * and died with UnsatisfiedLinkError: jhv_mirror_prepare (2026-10-11). jpackage sets jpackage.app-path in
 * the app it builds; with it set, the working-directory and beside-the-jar copies are not used.
 *
 * <p>Run from the repository root (its lib/natives-macos holds the tracked dylib):
 * java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.opengl.angle.BundledMetalHostCheck
 */
public final class BundledMetalHostCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-metal-host").toString());
        Platform.init();
        Path cwdCopy = Path.of("lib", "natives-macos", "libjhvmetalhost.dylib").toAbsolutePath();
        if (!Platform.isMacOS() || !Files.isRegularFile(cwdCopy)) {
            System.out.println("SKIP: needs macOS and the repository root as the working directory");
            return;
        }
        expect("a development run uses the checkout's dylib",
                AngleLibraries.libraryPath("libjhvmetalhost.dylib").equals(cwdCopy));

        System.setProperty("jpackage.app-path", "/Applications/HelioFITS Studio.app/Contents/MacOS/HelioFITS Studio");
        Path packaged;
        try {
            packaged = AngleLibraries.libraryPath("libjhvmetalhost.dylib");
        } catch (RuntimeException e) {   // no dylib resource on this classpath: still not the working directory's
            packaged = null;
        }
        expect("the packaged app does not use the working directory's dylib (got " + packaged + ")",
                !cwdCopy.equals(packaged));
        System.exit(failures == 0 ? 0 : 1);
    }
}
