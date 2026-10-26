package org.helioviewer.jhv.io;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * Making a library from Studio puts HelioFITS Core's config.json in the folder Studio reads it from,
 * even when Studio's user.home is not the HOME the core sees (a dev build run with
 * -Duser.home=<scratch>, 2026-10-26: "A library was made at ..., but Studio cannot find it").
 *
 * <p>A stand-in core (a shell script) does what {@code hfcore init} does: it makes the index under
 * --library and writes config.json into HFCORE_HOME, or, when Studio gave it none, into a folder of
 * this check's that stands for the real home. Nothing outside a temp folder is written.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.io.FitsCoreHomeCheck
 */
public final class FitsCoreHomeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            System.out.println("SKIP: the stand-in core is a shell script");
            return;
        }
        if (FitsLibrary.env("HOME") != null) {
            System.out.println("SKIP: HFCORE_HOME or HELIAD_HOME is set, so Studio and the core already share a folder");
            return;
        }
        Path tmp = Files.createTempDirectory("hfs-core-home");
        System.setProperty("user.home", tmp.resolve("studio-home").toString());
        Path realHome = tmp.resolve("real-home"), root = tmp.resolve("studio-home").resolve("HelioFITSLibrary");

        Path core = tmp.resolve("fake-core");
        Files.writeString(core, String.join("\n",
                "#!/bin/sh",
                "# args: --library <root> init",
                "dir=\"${HFCORE_HOME:-" + realHome + "}\"",
                "mkdir -p \"$2\" \"$dir\" && : > \"$2/hfcore.sqlite\"",
                "printf '{\"library_root\": \"%s\", \"schema_version\": 1}\\n' \"$2\" > \"$dir/config.json\"",
                ""));
        Files.setPosixFilePermissions(core, PosixFilePermissions.fromString("rwx------"));

        FitsCoreIngest.makeLibrary(List.of(core.toString(), "--library", root.toString(), "serve"));

        Path configDir = FitsLibrary.configDir();
        expect("the config is written where Studio reads it (" + configDir + ")", Files.isRegularFile(configDir.resolve("config.json")));
        expect("nothing is written into the home the core would see on its own", !Files.exists(realHome.resolve("config.json")));
        FitsLibrary.Location found = FitsLibrary.find();
        expect("Studio finds the library it just made, got " + found, found != null && found.root().equals(root));

        if (failures > 0) {
            System.out.println(failures + " failed");
            System.exit(1);
        }
    }

}
