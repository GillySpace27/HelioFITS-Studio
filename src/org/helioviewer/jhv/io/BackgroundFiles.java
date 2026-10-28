package org.helioviewer.jhv.io;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.fits.ImageHDU;
import nom.tam.image.compression.hdu.CompressedImageHDU;
import nom.tam.util.Cursor;
import nom.tam.util.FitsFile;

/**
 * The background each LASCO frame had taken off, written as a FITS file of its own, so it can be
 * opened as an ordinary layer beside the frames (the Background section's Show as layer).
 *
 * <p>One file per frame, because the background moves with time in the interpolated and pair
 * modes. Each carries its frame's header, so it sits on the Sun exactly where its frame does and
 * plays at the frame's time, with the pixel cards replaced (the values are DN per second, already
 * divided by the exposure and with no bias left) and {@link #MARK} set. A header with the mark is
 * never given a background of its own ({@link LascoBackground.Request#of}), or the layer would
 * subtract its own picture from itself.
 */
public final class BackgroundFiles {

    /** Set on every file written here; FitsMetaData names such a layer as a background. */
    public static final String MARK = "HFSBKG";

    // Cards that describe the frame's pixels rather than the observation; the written file's own take their place.
    private static final Set<String> PIXEL_CARDS = Set.of("SIMPLE", "XTENSION", "BITPIX", "NAXIS", "NAXIS1", "NAXIS2", "NAXIS3",
            "EXTEND", "PCOUNT", "GCOUNT", "BZERO", "BSCALE", "BLANK", "OFFSET", "DATAMIN", "DATAMAX", "HV_DMIN", "HV_DMAX",
            "CHECKSUM", "DATASUM", "END", "ZIMAGE", "ZBITPIX", "ZNAXIS", "ZNAXIS1", "ZNAXIS2", "ZTILE1", "ZTILE2", "ZCMPTYPE");

    /** What came of it: the files written, in frame order, and why any frame has none. */
    public record Written(List<File> files, List<String> skipped) {}

    /**
     * @param frames the layer's frame files, in order
     * @param choice where the background comes from, as the layer has it
     * @param outDir where to write; the names depend on the frame and the choice, so a new choice never overwrites a file a layer uses
     */
    public static Written write(List<File> frames, LascoBackground.Choice choice, File outDir) throws IOException {
        if (!outDir.isDirectory() && !outDir.mkdirs())
            throw new IOException("Cannot create " + outDir);
        List<File> files = new ArrayList<>(frames.size());
        List<String> skipped = new ArrayList<>();
        for (File frame : frames) {
            String why;
            try {
                File out = writeOne(frame, choice, outDir);
                if (out != null) {
                    files.add(out);
                    continue;
                }
                why = "no background for this product and time";
            } catch (LascoBackground.Refused e) {
                why = e.getMessage();
            } catch (LascoBackground.Unavailable e) {
                why = "could not be fetched from NRL";
            } catch (IOException | FitsException e) {
                why = "could not be read: " + e.getMessage();
            }
            skipped.add(frame.getName() + ": " + why);
        }
        return new Written(List.copyOf(files), List.copyOf(skipped));
    }

    @Nullable
    private static File writeOne(File frame, LascoBackground.Choice choice, File outDir) throws IOException, FitsException {
        Header header = header(frame);
        if (header == null)
            return null;
        int width = header.getIntValue("NAXIS1", 0);
        int height = header.getIntValue("NAXIS2", 0);
        LascoBackground.Request request = LascoBackground.Request.of(header, width, height);
        if (request == null)
            return null;
        LascoBackground.Result result = request.resolve(choice);
        if (result == null)
            return null;

        float[] flat = result.perSecond();
        float[][] rows = new float[height][width];
        for (int j = 0; j < height; j++)
            System.arraycopy(flat, j * width, rows[j], 0, width);
        ImageHDU hdu = (ImageHDU) Fits.makeHDU(rows);
        Header out = hdu.getHeader();
        for (Cursor<String, HeaderCard> it = header.iterator(); it.hasNext(); ) {
            HeaderCard card = it.next();
            String key = card.getKey().trim();
            if (!PIXEL_CARDS.contains(key) && !key.isEmpty() && !out.containsKey(key))
                out.addLine(card);
        }
        out.addValue(MARK, true, "HelioFITS Studio: the background taken off this frame, DN/s");
        out.addValue("HFSBSRC", choice.mode().name(), "background source");
        if (result.note() != null)
            out.insertComment(result.note());

        File file = new File(outDir, name(frame, choice));
        File partial = new File(outDir, file.getName() + ".part");
        try (Fits fits = new Fits(); FitsFile stream = new FitsFile(partial, "rw")) {
            fits.addHDU(hdu);
            fits.write(stream);
        }
        if (!partial.renameTo(file) && !file.isFile())
            throw new IOException("Cannot write " + file);
        return file;
    }

    /** The frame's base name, the source and a hash of everything that decides the pixels. */
    static String name(File frame, LascoBackground.Choice choice) {
        String base = frame.getName().replaceFirst("(?i)\\.(fits?|fts)(\\.gz)?$", "");
        String id = frame.getPath() + '@' + frame.lastModified() + '|' + choice.mode() + '|' + stamp(choice.fileA()) + '|' + stamp(choice.fileB());
        return String.format(Locale.US, "%s_bkg_%s_%08x.fits", base, choice.mode().name().toLowerCase(Locale.US), id.hashCode());
    }

    private static String stamp(String path) {
        return path.isEmpty() ? "" : path + '@' + new File(path).lastModified();
    }

    @Nullable
    private static Header header(File file) throws IOException, FitsException {
        try (Fits fits = new Fits(file)) {
            for (BasicHDU<?> hdu : fits.read()) {
                if (hdu instanceof CompressedImageHDU c)
                    return c.getImageHeader();
                if (hdu instanceof ImageHDU i && i.getAxes() != null)
                    return i.getHeader();
            }
        }
        return null;
    }

    private BackgroundFiles() {
    }

}
