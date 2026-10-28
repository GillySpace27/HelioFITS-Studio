package org.helioviewer.jhv.io;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import org.helioviewer.jhv.time.TimeUtils;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.ImageHDU;
import nom.tam.image.compression.hdu.CompressedImageHDU;

/**
 * A background the user supplies: one FITS file, or two dated ones blended in time.
 *
 * <p>The file goes through the same arithmetic as the frame it is taken off,
 * (BZERO + raw * BSCALE - OFFSET) / EXPTIME, so both are DN per second whatever the file is: a raw
 * level-0.5 frame (which carries its CCD bias as OFFSET) or a minimum image someone built (which
 * usually carries none, read as 0). What cannot be put on that footing is refused with a reason
 * rather than guessed at: no EXPTIME, a different size, no date for a pair. A different detector,
 * filter, polarizer or roll is allowed and said, since the user may mean it.
 */
public final class LocalBackground {

    // SOHO rolls by 180 degrees twice a year (vault instruments/LASCO.md §8b); a background read at the
    // other roll subtracts a pattern turned half way round. One degree leaves room for the small
    // pointing differences between frames of the same period.
    private static final double ROLL_TOLERANCE_DEG = 1;

    /** One local file read and converted, with what its header says about it. */
    record Loaded(float[] perSecond, int width, int height, long milli,
                  String detector, String filter, String polar, double roll) {}

    private static final int MAX_CACHED = 8;
    // By path and modification time, so editing the file on disk is picked up on the next read.
    private static final Map<String, Loaded> cache = new LinkedHashMap<>(8, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Loaded> eldest) {
            return size() > MAX_CACHED;
        }
    };

    /** The one file, for every frame. */
    static LascoBackground.Result single(LascoBackground.Request frame, String path) {
        Loaded a = load(path, frame);
        return new LascoBackground.Result(a.perSecond(), warnings(frame, List.of(a)));
    }

    /** The two files blended by where the frame's time falls between their dates; the nearer one outside them. */
    static LascoBackground.Result pair(LascoBackground.Request frame, String pathA, String pathB) {
        if (pathA.isEmpty() != pathB.isEmpty())
            throw new LascoBackground.Refused("Choose the pair's other file");
        Loaded a = load(pathA, frame);
        Loaded b = load(pathB, frame);
        if (a.milli() == 0 || b.milli() == 0)
            throw new LascoBackground.Refused("A local pair needs a date in each file (DATE-OBS)");
        if (a.milli() == b.milli())
            throw new LascoBackground.Refused("Both files of the pair have the same date");
        Loaded first = a.milli() < b.milli() ? a : b;
        Loaded second = first == a ? b : a;
        double w = LascoBackground.weight(first.milli(), second.milli(), frame.milli());
        String outside = frame.milli() < first.milli() || frame.milli() > second.milli()
                ? "Outside the pair's dates; the nearer file is used" : null;
        String note = join(outside, warnings(frame, List.of(first, second)));
        return new LascoBackground.Result(LascoBackground.mix(first.perSecond(), second.perSecond(), w), note);
    }

    /** "name" or "earlier and later, weighted n% toward the later", for the Preview caption. */
    static String describe(LascoBackground.Request frame, LascoBackground.Choice choice) {
        if (choice.mode() == LascoBackground.Mode.LOCAL_FILE)
            return new File(choice.fileA()).getName();
        Loaded a = load(choice.fileA(), frame);
        Loaded b = load(choice.fileB(), frame);
        Loaded first = a.milli() <= b.milli() ? a : b;
        String firstName = new File(first == a ? choice.fileA() : choice.fileB()).getName();
        String secondName = new File(first == a ? choice.fileB() : choice.fileA()).getName();
        Loaded second = first == a ? b : a;
        return String.format(Locale.US, "%s and %s, weighted %.0f%% toward the later", firstName, secondName,
                100 * LascoBackground.weight(first.milli(), second.milli(), frame.milli()));
    }

    @Nullable
    private static String warnings(LascoBackground.Request frame, List<Loaded> files) {
        List<String> said = new ArrayList<>();
        for (Loaded f : files) {
            differs(said, "detector", f.detector(), frame.detector());
            differs(said, "filter", f.filter(), frame.filter());
            differs(said, "polarizer", f.polar(), frame.polar());
            if (!Double.isNaN(f.roll()) && !Double.isNaN(frame.roll())) {
                double d = Math.abs(Math.IEEEremainder(f.roll() - frame.roll(), 360));
                if (d > ROLL_TOLERANCE_DEG && !said.contains("roll"))
                    said.add(String.format(Locale.US, "roll %.0f° from the frame's", d));
            }
        }
        return said.isEmpty() ? null : "Differs: " + String.join(", ", said);
    }

    private static void differs(List<String> said, String what, String file, String frame) {
        if (!file.isEmpty() && !file.equalsIgnoreCase(frame) && said.stream().noneMatch(s -> s.startsWith(what)))
            said.add(what + " " + file + " vs " + frame);
    }

    @Nullable
    private static String join(@Nullable String a, @Nullable String b) {
        if (a == null)
            return b;
        return b == null ? a : a + ". " + b;
    }

    /** Read, check against the frame's size and convert one file; refused with a reason if it cannot be used. */
    static Loaded load(String path, LascoBackground.Request frame) {
        if (path.isEmpty())
            throw new LascoBackground.Refused("No file chosen");
        File file = new File(path);
        if (!file.isFile())
            throw new LascoBackground.Refused("File not found: " + file.getName());
        String key = path + '@' + file.lastModified();
        Loaded loaded;
        synchronized (cache) {
            loaded = cache.get(key);
        }
        if (loaded == null) {
            loaded = read(file);
            synchronized (cache) {
                cache.put(key, loaded);
            }
        }
        if (loaded.width() != frame.width() || loaded.height() != frame.height())
            throw new LascoBackground.Refused(String.format(Locale.US, "%s is %d x %d, the frame %d x %d",
                    file.getName(), loaded.width(), loaded.height(), frame.width(), frame.height()));
        return loaded;
    }

    private static Loaded read(File file) {
        try (Fits fits = new Fits(file)) {
            for (BasicHDU<?> hdu : fits.read()) {
                ImageHDU image = hdu instanceof CompressedImageHDU c ? c.asImageHDU()
                        : hdu instanceof ImageHDU i && i.getAxes() != null ? i : null;
                if (image == null)
                    continue;
                Header h = image.getHeader();
                int[] axes = image.getAxes();
                if (axes.length != 2)
                    throw new LascoBackground.Refused(file.getName() + " is not a single 2-D image");
                double exposure = h.getDoubleValue("EXPTIME", 0);
                if (!(exposure > 0))
                    throw new LascoBackground.Refused(file.getName() + " has no EXPTIME, so it cannot be put in DN/s");
                int width = axes[1];
                int height = axes[0];
                float[] data = LascoBackground.flatten(image.getKernel(), width * height,
                        h.getDoubleValue("BZERO", 0), h.getDoubleValue("BSCALE", 1), h.getDoubleValue("OFFSET", 0), exposure);
                if (data == null)
                    throw new LascoBackground.Refused(file.getName() + " has a pixel type that cannot be read");
                return new Loaded(data, width, height, date(h), text(h, "DETECTOR"), text(h, "FILTER"), text(h, "POLAR"),
                        LascoBackground.roll(h));
            }
            throw new LascoBackground.Refused(file.getName() + " holds no image");
        } catch (IOException | FitsException e) {
            throw new LascoBackground.Refused(file.getName() + " could not be read: " + e.getMessage());
        }
    }

    private static String text(Header h, String key) {
        String v = h.getStringValue(key);
        return v == null ? "" : v.trim();
    }

    /** DATE-OBS, with LASCO's separate TIME-OBS when the date has no time of its own; 0 when absent or unreadable. */
    static long date(Header h) {
        String date = h.getStringValue("DATE-OBS");
        if (date == null || date.isBlank())
            return 0;
        String d = date.trim().replace('/', '-');
        String time = h.getStringValue("TIME-OBS");
        if (!d.contains("T") && time != null && !time.isBlank()) {
            String hms = time.trim();
            d = d + 'T' + (hms.length() > 8 ? hms.substring(0, 8) : hms);
        }
        try {
            return TimeUtils.parse(d);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private LocalBackground() {
    }

}
