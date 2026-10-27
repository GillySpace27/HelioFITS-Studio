package org.helioviewer.jhv.io;

import java.io.File;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.Map;

import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.ImageProcessingSettings;
import org.helioviewer.jhv.view.ClipSet;
import org.helioviewer.jhv.view.uri.FITSImage;

import org.json.JSONObject;

import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.ImageHDU;
import nom.tam.util.FitsFile;

/**
 * The Background section's four modes: NRL interpolated, NRL nearest, a local file and a local pair.
 *
 * <p>Gilly asked (2026-10-27) to be able to take his own background off LASCO frames, from one dated
 * FITS file or two. A local file must land in the same units as the frame it is subtracted from,
 * DN per second, so it goes through (BZERO + raw * BSCALE - OFFSET) / EXPTIME; what cannot be put
 * there is refused with a reason, and what may be a mistake (another filter, the other roll) is
 * said and allowed. All files are synthetic and written to a temp folder; nothing here reaches NRL.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.io.LascoLocalBackgroundCheck
 */
public final class LascoLocalBackgroundCheck {

    private static final int SIZE = 16;
    private static final double EPS = 1e-4;

    private static int failures;
    private static File dir;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** A 16 x 16 image, value base + index, with these cards; "-" as a value leaves the card out. */
    private static File write(String name, int size, int base, String... cards) throws Exception {
        short[][] pixels = new short[size][size];
        for (int j = 0; j < size; j++)
            for (int i = 0; i < size; i++)
                pixels[j][i] = (short) (base + j * size + i);
        ImageHDU hdu = (ImageHDU) Fits.makeHDU(pixels);
        Header h = hdu.getHeader();
        for (int k = 0; k < cards.length; k += 2) {
            String v = cards[k + 1];
            if ("-".equals(v))
                continue;
            if (v.matches("-?[0-9.]+"))
                h.addValue(cards[k], Double.parseDouble(v), "");
            else
                h.addValue(cards[k], v, "");
        }
        File file = new File(dir, name);
        try (Fits fits = new Fits(); FitsFile out = new FitsFile(file, "rw")) {
            fits.addHDU(hdu);
            fits.write(out);
        }
        return file;
    }

    private static File background(String name, int base, String date, double exptime, double offset, String filter, double roll) throws Exception {
        return write(name, SIZE, base, "DETECTOR", "C3", "FILTER", filter, "POLAR", "Clear", "DATE-OBS", date,
                "EXPTIME", exptime < 0 ? "-" : Double.toString(exptime), "OFFSET", Double.toString(offset), "CROTA1", Double.toString(roll));
    }

    private static String refusal(LascoBackground.Request frame, LascoBackground.Choice choice) {
        try {
            frame.resolve(choice);
            return null;
        } catch (LascoBackground.Refused e) {
            return e.getMessage();
        }
    }

    private static boolean all(float[] got, java.util.function.IntToDoubleFunction expected) {
        if (got == null || got.length != SIZE * SIZE)
            return false;
        for (int i = 0; i < got.length; i++)
            if (Math.abs(got[i] - expected.applyAsDouble(i)) > EPS * Math.max(1, Math.abs(expected.applyAsDouble(i))))
                return false;
        return true;
    }

    public static void main(String[] args) throws Exception {
        // ImageProcessingSettings reads its default from Settings: never the real home's settings file.
        System.setProperty("user.home", Files.createTempDirectory("hfs-lasco-local").toString());
        dir = Files.createTempDirectory("hfs-lasco-local-files").toFile();
        dir.deleteOnExit();

        File frameFile = write("frame.fts", SIZE, 500, "TELESCOP", "SOHO", "INSTRUME", "LASCO", "DETECTOR", "C3", "FILTER", "Clear",
                "POLAR", "Clear", "DATE-OBS", "2025/09/20", "TIME-OBS", "03:24:29.123", "EXPTIME", "17.6", "OFFSET", "417.5", "CROTA1", "0.0");
        Header frameHeader;
        try (Fits f = new Fits(frameFile)) {
            frameHeader = f.readHDU().getHeader();
        }
        LascoBackground.Request frame = LascoBackground.Request.of(frameHeader, SIZE, SIZE);
        expect("the synthetic frame names a background request with its roll", frame != null && frame.roll() == 0);
        if (frame == null) {
            System.exit(1);
            return;
        }

        // ---- one local file: the frame's own arithmetic, OFFSET and EXPTIME included ----
        File a = background("a.fts", 1000, "2025-09-10T00:00:00", 10, 100, "Clear", 0);
        LascoBackground.Choice single = new LascoBackground.Choice(LascoBackground.Mode.LOCAL_FILE, a.getPath(), "");
        LascoBackground.Result r = frame.resolve(single);
        expect("a local file is (raw - OFFSET) / EXPTIME, in DN/s", r != null && all(r.perSecond(), i -> (1000 + i - 100) / 10.));
        expect("a matching local file has nothing to say", r != null && r.note() == null);

        // ---- a pair: blended by where the frame falls between the two dates ----
        File b = background("b.fts", 2000, "2025-09-30T00:00:00", 20, 0, "Clear", 0);
        long ta = LocalBackground.date(headerOf(a));
        long tb = LocalBackground.date(headerOf(b));
        double w = (frame.milli() - ta) / (double) (tb - ta);
        expect("the frame falls between the two files' dates", w > 0 && w < 1);
        LascoBackground.Result pair = frame.resolve(new LascoBackground.Choice(LascoBackground.Mode.LOCAL_PAIR, a.getPath(), b.getPath()));
        java.util.function.IntToDoubleFunction blend = i -> (1 - w) * (1000 + i - 100) / 10. + w * (2000 + i) / 20.;
        expect("a pair is the two files weighted by time", pair != null && all(pair.perSecond(), blend) && pair.note() == null);
        LascoBackground.Result swapped = frame.resolve(new LascoBackground.Choice(LascoBackground.Mode.LOCAL_PAIR, b.getPath(), a.getPath()));
        expect("the order the files were chosen in does not matter", swapped != null && all(swapped.perSecond(), blend));
        String caption = frame.describe(new LascoBackground.Choice(LascoBackground.Mode.LOCAL_PAIR, b.getPath(), a.getPath()));
        expect("the Preview caption names both files, earlier first, and the weight (got \"" + caption + "\")",
                caption.startsWith("a.fts and b.fts, weighted ") && caption.contains(Math.round(100 * w) + "%"));

        LascoBackground.Request before = new LascoBackground.Request("C3", "Clear", "Clear", ta - 86_400_000L, SIZE, SIZE, 0);
        LascoBackground.Result outside = before.resolve(new LascoBackground.Choice(LascoBackground.Mode.LOCAL_PAIR, a.getPath(), b.getPath()));
        expect("outside the pair's dates the nearer file is used", outside != null && all(outside.perSecond(), i -> (1000 + i - 100) / 10.));
        expect("and the status says so", outside != null && outside.note() != null && outside.note().startsWith("Outside the pair's dates"));

        // ---- refused, with a reason ----
        File noExposure = background("noexp.fts", 1000, "2025-09-10T00:00:00", -1, 0, "Clear", 0);
        String why = refusal(frame, new LascoBackground.Choice(LascoBackground.Mode.LOCAL_FILE, noExposure.getPath(), ""));
        expect("a file without EXPTIME is refused (got \"" + why + "\")", why != null && why.contains("EXPTIME"));
        File small = write("small.fts", 8, 0, "EXPTIME", "10", "DATE-OBS", "2025-09-10T00:00:00");
        why = refusal(frame, new LascoBackground.Choice(LascoBackground.Mode.LOCAL_FILE, small.getPath(), ""));
        expect("a file of another size is refused (got \"" + why + "\")", why != null && why.contains("8 x 8") && why.contains("16 x 16"));
        why = refusal(frame, new LascoBackground.Choice(LascoBackground.Mode.LOCAL_FILE, new File(dir, "gone.fts").getPath(), ""));
        expect("a missing file is refused (got \"" + why + "\")", why != null && why.startsWith("File not found"));
        why = refusal(frame, new LascoBackground.Choice(LascoBackground.Mode.LOCAL_FILE, "", ""));
        expect("a local mode with no file chosen is refused (got \"" + why + "\")", "No file chosen".equals(why));
        why = refusal(frame, new LascoBackground.Choice(LascoBackground.Mode.LOCAL_PAIR, a.getPath(), ""));
        expect("a pair with one file chosen asks for the other (got \"" + why + "\")", "Choose the pair's other file".equals(why));
        File sameDate = background("same.fts", 3000, "2025-09-10T00:00:00", 10, 0, "Clear", 0);
        why = refusal(frame, new LascoBackground.Choice(LascoBackground.Mode.LOCAL_PAIR, a.getPath(), sameDate.getPath()));
        expect("a pair with one date twice is refused (got \"" + why + "\")", why != null && why.contains("same date"));
        File undated = write("undated.fts", SIZE, 0, "EXPTIME", "10");
        why = refusal(frame, new LascoBackground.Choice(LascoBackground.Mode.LOCAL_PAIR, a.getPath(), undated.getPath()));
        expect("a pair with an undated file is refused (got \"" + why + "\")", why != null && why.contains("DATE-OBS"));
        expect("but one undated file alone is fine", refusal(frame, new LascoBackground.Choice(LascoBackground.Mode.LOCAL_FILE, undated.getPath(), "")) == null);

        // ---- said, and allowed ----
        File orange = background("orange.fts", 1000, "2025-09-10T00:00:00", 10, 0, "Orange", 0);
        LascoBackground.Result differs = frame.resolve(new LascoBackground.Choice(LascoBackground.Mode.LOCAL_FILE, orange.getPath(), ""));
        expect("another filter is used and said (got \"" + (differs == null ? null : differs.note()) + "\")",
                differs != null && differs.note() != null && differs.note().contains("filter Orange vs Clear"));
        File rolled = background("rolled.fts", 1000, "2025-09-10T00:00:00", 10, 0, "Clear", 180);
        differs = frame.resolve(new LascoBackground.Choice(LascoBackground.Mode.LOCAL_FILE, rolled.getPath(), ""));
        expect("the other roll is used and said (got \"" + (differs == null ? null : differs.note()) + "\")",
                differs != null && differs.note() != null && differs.note().contains("roll 180"));
        File nearlyLevel = background("level.fts", 1000, "2025-09-10T00:00:00", 10, 0, "Clear", 359.6);
        differs = frame.resolve(new LascoBackground.Choice(LascoBackground.Mode.LOCAL_FILE, nearlyLevel.getPath(), ""));
        expect("a roll within a degree, across 360, is not a difference", differs != null && differs.note() == null);

        // ---- an edited file is read again ----
        File edited = background("edited.fts", 1000, "2025-09-10T00:00:00", 10, 0, "Clear", 0);
        LascoBackground.Choice editedChoice = new LascoBackground.Choice(LascoBackground.Mode.LOCAL_FILE, edited.getPath(), "");
        frame.resolve(editedChoice);
        expect("(the old copy can be moved aside)", edited.delete());
        edited = background("edited.fts", 5000, "2025-09-10T00:00:00", 10, 0, "Clear", 0);
        expect("(and the new one dated later)", edited.setLastModified(System.currentTimeMillis() + 5000));
        LascoBackground.Result again = frame.resolve(editedChoice);
        expect("a file changed on disk is read again", again != null && all(again.perSecond(), i -> (5000 + i) / 10.));

        // ---- the NRL modes' helpers ----
        expect("the time weight is clamped to the two dates", LascoBackground.weight(10, 20, 15) == .5
                && LascoBackground.weight(10, 20, 0) == 0 && LascoBackground.weight(10, 20, 99) == 1);
        Map.Entry<Long, String> early = new AbstractMap.SimpleEntry<>(10L, "early");
        Map.Entry<Long, String> late = new AbstractMap.SimpleEntry<>(20L, "late");
        expect("nearest picks the closer monthly image", "early".equals(LascoBackground.nearest(early, late, 14).getValue())
                && "late".equals(LascoBackground.nearest(early, late, 16).getValue()));
        expect("and whichever exists at the end of the list", "early".equals(LascoBackground.nearest(early, null, 99).getValue())
                && "late".equals(LascoBackground.nearest(null, late, 0).getValue()));

        // ---- end to end: the decoded frame says what it got ----
        ImageProcessingSettings.FITSParameters local = params(single);
        ImageBuffer used = FITSImage.decode(frameFile, ImageFilter.NONE, local, new ClipSet.Range(-1000, 1000));
        expect("a frame decoded with a local file is marked subtracted, and local", used.isBackgroundSubtracted() && used.isBackgroundLocal());
        expect("and is not provisional (nothing was fetched)", !used.isProvisional());
        ImageBuffer refused = FITSImage.decode(frameFile, ImageFilter.NONE,
                params(new LascoBackground.Choice(LascoBackground.Mode.LOCAL_FILE, "", "")), new ClipSet.Range(-1000, 1000));
        expect("a refused file leaves the frame as served and says why", !refused.isBackgroundSubtracted()
                && "No file chosen".equals(refused.backgroundNote()) && !refused.isProvisional());

        // ---- Show as layer: one file per frame, the frame's cards, never a background of its own ----
        File out = new File(dir, "bkg");
        File notLasco = write("aia.fts", SIZE, 0, "TELESCOP", "SDO/AIA", "EXPTIME", "2");
        BackgroundFiles.Written written = BackgroundFiles.write(java.util.List.of(frameFile, notLasco), single, out);
        expect("the LASCO frame gets a background file", written.files().size() == 1);
        expect("the frame without one is listed with a reason (got " + written.skipped() + ")",
                written.skipped().size() == 1 && written.skipped().getFirst().startsWith("aia.fts: "));
        if (written.files().size() == 1) {
            File bg = written.files().getFirst();
            Header h = headerOf(bg);
            expect("it keeps the frame's time and pointing", "2025/09/20".equals(h.getStringValue("DATE-OBS"))
                    && h.getDoubleValue("CROTA1", -1) == 0 && "C3".equals(h.getStringValue("DETECTOR")));
            expect("its pixel cards are its own: float, no bias, no scaling", h.getIntValue("BITPIX") == -32
                    && !h.containsKey("OFFSET") && !h.containsKey("BZERO"));
            expect("it is marked, so it is never given a background of its own",
                    h.getBooleanValue(BackgroundFiles.MARK, false) && LascoBackground.Request.of(h, SIZE, SIZE) == null);
            float[] px;
            try (Fits f = new Fits(bg)) {
                float[][] rows = (float[][]) ((ImageHDU) f.readHDU()).getKernel();
                px = new float[SIZE * SIZE];
                for (int j = 0; j < SIZE; j++)
                    System.arraycopy(rows[j], 0, px, j * SIZE, SIZE);
            }
            expect("its pixels are the background that came off, row for row", all(px, i -> (1000 + i - 100) / 10.));
            expect("another source writes another file, never over one a layer uses",
                    !BackgroundFiles.name(frameFile, single).equals(BackgroundFiles.name(frameFile,
                            new LascoBackground.Choice(LascoBackground.Mode.LOCAL_PAIR, a.getPath(), b.getPath()))));
        }

        // ---- the session ----
        int[] changes = {0};
        ImageProcessingSettings settings = new ImageProcessingSettings(() -> changes[0]++);
        ImageProcessingSettings.FITSParameters start = settings.fitsParameters();
        expect("a new layer interpolates NRL's images", LascoBackground.Choice.DEFAULT.equals(start.background()));
        LascoBackground.Choice chosen = new LascoBackground.Choice(LascoBackground.Mode.LOCAL_PAIR, a.getPath(), b.getPath());
        settings.setBackground(chosen);
        expect("choosing a source changes the decode key and asks for a redraw",
                !start.equals(settings.fitsParameters()) && changes[0] == 1);
        JSONObject jo = new JSONObject();
        settings.serialize(jo);
        ImageProcessingSettings restored = new ImageProcessingSettings(() -> {});
        restored.fromJson(jo);
        expect("a saved session keeps the mode and both files", chosen.equals(restored.fitsParameters().background()));
        ImageProcessingSettings old = new ImageProcessingSettings(() -> {});
        old.fromJson(new JSONObject().put("plane", 0).put("subtractBackground", true));
        expect("a session saved before the modes existed interpolates, as it always did",
                LascoBackground.Choice.DEFAULT.equals(old.fitsParameters().background()));
        ImageProcessingSettings odd = new ImageProcessingSettings(() -> {});
        odd.fromJson(new JSONObject().put("backgroundMode", "SOMETHING_NEWER"));
        expect("an unknown mode from a newer session falls back to the default", odd.fitsParameters().background().mode() == LascoBackground.Mode.INTERPOLATED);

        System.out.println(failures == 0 ? "LascoLocalBackgroundCheck: all passed" : "LascoLocalBackgroundCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static ImageProcessingSettings.FITSParameters params(LascoBackground.Choice choice) {
        return new ImageProcessingSettings.FITSParameters(ImageProcessingSettings.ClippingMode.Range, -1000, 1000,
                ImageProcessingSettings.ScalingMode.Gamma, 1 / 2.2, 0, 0, 0, true, choice);
    }

    private static Header headerOf(File file) throws Exception {
        try (Fits f = new Fits(file)) {
            return f.readHDU().getHeader();
        }
    }

}
