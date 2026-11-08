package org.helioviewer.jhv.movie;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.io.FitsCoreIngest;
import org.helioviewer.jhv.opengl.GL;

import org.json.JSONObject;

/**
 * The record's "environment" block (hfrecord 1.1): what the machine and build contributed to the
 * picture, so a replay report can say why two renders differ. Read from the GL strings Studio
 * already captures at start-up, the Java system properties and the display flags. Never the host
 * name or the user name. A part that cannot be read is left out, never guessed.
 */
final class RecordEnvironment {

    private RecordEnvironment() {}

    /** The block as of now; call it where the export starts, beside {@link ProcessingRecord#live()}. */
    static JSONObject capture() {
        JSONObject env = new JSONObject();
        env.put("os", new JSONObject().put("name", prop("os.name")).put("version", prop("os.version")).put("arch", prop("os.arch")));
        env.put("jvm", new JSONObject().put("version", prop("java.version")).put("vendor", prop("java.vendor")));
        JSONObject gpu = new JSONObject();
        put(gpu, "vendor", GL.vendor);
        put(gpu, "renderer", GL.renderer);
        put(gpu, "version", GL.version);
        put(gpu, "glsl", GL.shadingLanguageVersion);
        env.put("gpu", gpu);
        // The raw inputs; ProcessingRecord reduces them to display.dither and display.high_bit_depth_capture.
        env.put("display", new JSONObject().put("dither_enabled", Display.isDitherEnabled()).put("deep_canvas", Display.deepCanvas));
        String commit = FitsCoreIngest.bundledCommit();
        if (commit != null)
            env.put("core", new JSONObject().put("commit", commit));
        return env;
    }

    /** The first line of {@code ffmpeg -version}, or null when it cannot be run. Off the event thread (it starts a process). */
    @Nullable
    static String ffmpegVersion(String ffmpeg) {
        try {
            Process p = new ProcessBuilder(ffmpeg, "-version").redirectErrorStream(true).start();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = in.readLine();
                if (!p.waitFor(10, TimeUnit.SECONDS))
                    p.destroyForcibly();
                return line == null || line.isBlank() ? null : line.strip();
            }
        } catch (IOException e) {
            Log.warn("ffmpeg version not read", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** SHA-256 of the cached Hipparcos star file when a Stars layer is on, else null. Reads a few MB; the event thread is fine. */
    @Nullable
    static String starCatalogSha256() {
        boolean on = false;
        for (org.helioviewer.jhv.layers.Layer layer : org.helioviewer.jhv.layers.Layers.getLayers())
            on |= layer instanceof org.helioviewer.jhv.layers.StarLayer && layer.isEnabled();
        if (!on)
            return null;
        try {
            java.nio.file.Path file = org.helioviewer.jhv.layers.stars.StarCatalog.cacheFile();
            return java.nio.file.Files.isRegularFile(file) ? org.helioviewer.jhv.io.SourceIdentity.sha256(file) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String prop(String key) {
        return System.getProperty(key, "");
    }

    private static void put(JSONObject jo, String key, @Nullable String value) {
        if (value != null && !value.isEmpty())
            jo.put(key, value);
    }
}
