package org.helioviewer.jhv.view.uri;

import java.io.File;
import java.nio.file.Files;

import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.ImageProcessingSettings;
import org.helioviewer.jhv.io.LascoBackground;
import org.helioviewer.jhv.view.ClipSet;

import org.json.JSONObject;

import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.ImageHDU;
import nom.tam.util.FitsFile;

/**
 * The per-layer switch for the LASCO monthly background, and what a decoded frame says about it.
 *
 * <p>Before this, every LASCO frame had its background taken off with no way to turn it off per
 * layer, and the readout's label said "includes the F corona" over the subtracted pixels because
 * nothing on the frame said the subtraction had run (Gilly, 2026-10-23). The frame is synthetic: no
 * LASCO file is committed. Turned off, readData must not reach NRL at all, which the unchanged
 * pixels and the absence of the provisional flag both show; turned on, the subtraction itself is
 * LascoBackgroundSubtractionCheck's, so the "subtracted" frame here is built directly.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.view.uri.LascoBackgroundToggleCheck
 */
public final class LascoBackgroundToggleCheck {

    private static final int SIZE = 16;

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static Header lasco(String telescope, String polar) throws Exception {
        Header h = new Header();
        h.addValue("TELESCOP", telescope, "");
        h.addValue("INSTRUME", "LASCO", "");
        h.addValue("DETECTOR", "C3", "");
        h.addValue("FILTER", "Clear", "");
        h.addValue("POLAR", polar, "");
        h.addValue("DATE-OBS", "2025/09/20", "");
        h.addValue("TIME-OBS", "03:24:29.123", "");
        h.addValue("EXPTIME", 17.6, "");
        h.addValue("OFFSET", 417.5, "");
        return h;
    }

    private static File writeFrame(short[][] pixels) throws Exception {
        File file = Files.createTempFile("hfs-lasco-toggle", ".fts").toFile();
        file.deleteOnExit();
        ImageHDU hdu = (ImageHDU) Fits.makeHDU(pixels);
        Header src = lasco("SOHO", "Clear");
        for (String key : new String[]{"TELESCOP", "INSTRUME", "DETECTOR", "FILTER", "POLAR", "DATE-OBS", "TIME-OBS"})
            hdu.getHeader().addValue(key, src.getStringValue(key), "");
        hdu.getHeader().addValue("EXPTIME", 17.6, "");
        hdu.getHeader().addValue("OFFSET", 417.5, "");
        try (Fits fits = new Fits(); FitsFile out = new FitsFile(file, "rw")) {
            fits.addHDU(hdu);
            fits.write(out);
        }
        return file;
    }

    public static void main(String[] args) throws Exception {
        // ImageProcessingSettings reads its default from Settings: never the real home's settings file.
        System.setProperty("user.home", Files.createTempDirectory("hfs-lasco-toggle").toString());

        LascoBackground.Request request = LascoBackground.Request.of(lasco("SOHO", "Clear"), SIZE, SIZE);
        expect("a C3 clear frame names its background", request != null && "C3".equals(request.detector())
                && "Clear".equals(request.filter()) && request.width() == SIZE && request.height() == SIZE);
        expect("a non-SOHO header names none", LascoBackground.Request.of(lasco("SDO", "Clear"), SIZE, SIZE) == null);
        expect("a polarizer frame names none (NRL publishes no monthly image for one)",
                LascoBackground.Request.of(lasco("SOHO", "+60 Deg"), SIZE, SIZE) == null);

        short[][] pixels = new short[SIZE][SIZE];
        for (int j = 0; j < SIZE; j++)
            for (int i = 0; i < SIZE; i++)
                pixels[j][i] = (short) (500 + j * SIZE + i);
        File file = writeFrame(pixels);

        FITSData off = FITSImage.readData(file, 0, false);
        expect("switched off, the frame is not marked subtracted", !off.backgroundSubtracted());
        expect("switched off, NRL is never asked (not provisional)", !off.provisional());
        expect("switched off, the pixels are the file's own", off.pixels() instanceof short[] p && p[SIZE + 3] == 500 + SIZE + 3);
        expect("switched off, the frame still says which background it has", off.background() != null);

        ImageProcessingSettings.FITSParameters params = new ImageProcessingSettings.FITSParameters(
                ImageProcessingSettings.ClippingMode.Range, 0, 1000, ImageProcessingSettings.ScalingMode.Gamma, 1 / 2.2, 0, 0, 0, false);
        ImageBuffer shown = off.decode(ImageFilter.NONE, params, new ClipSet.Range(0, 1000));
        expect("the decoded buffer says it was not subtracted", !shown.isBackgroundSubtracted());
        expect("and carries the request for the preview", shown.background() != null);

        FITSData on = new FITSData(off.header(), new float[SIZE * SIZE], SIZE, SIZE, false, 0, 0, 1, null, false, true, request);
        ImageBuffer subtracted = on.decode(ImageFilter.NONE, params, new ClipSet.Range(0, 1000));
        expect("a subtracted frame's buffer says so (the readout label reads this)", subtracted.isBackgroundSubtracted());

        int[] changes = {0};
        ImageProcessingSettings settings = new ImageProcessingSettings(() -> changes[0]++);
        ImageProcessingSettings.FITSParameters before = settings.fitsParameters();
        expect("a new layer subtracts by default", before.subtractBackground());
        settings.setSubtractBackground(false);
        expect("switching it off changes the decode parameters, so no cached frame is reused",
                !before.equals(settings.fitsParameters()) && !settings.fitsParameters().subtractBackground());
        expect("and asks for a redraw", changes[0] == 1);
        JSONObject jo = new JSONObject();
        settings.serialize(jo);
        ImageProcessingSettings restored = new ImageProcessingSettings(() -> {});
        restored.fromJson(jo);
        expect("a saved session keeps the switch off", !restored.fitsParameters().subtractBackground());
        ImageProcessingSettings old = new ImageProcessingSettings(() -> {});
        old.fromJson(new JSONObject().put("plane", 0));
        expect("a session saved before the switch existed subtracts, as it always did", old.fitsParameters().subtractBackground());

        System.out.println(failures == 0 ? "LascoBackgroundToggleCheck: all passed" : "LascoBackgroundToggleCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

}
