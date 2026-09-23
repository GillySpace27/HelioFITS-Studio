package org.helioviewer.jhv.view.uri;

import java.io.File;
import java.nio.Buffer;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.ImageProcessingSettings;
import org.helioviewer.jhv.io.LascoBackground;
import org.helioviewer.jhv.time.TimeUtils;
import org.helioviewer.jhv.view.ClipSet;

import com.google.common.escape.Escaper;
import com.google.common.xml.XmlEscapers;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.fits.ImageHDU;
import nom.tam.fits.header.Standard;
import nom.tam.image.compression.hdu.CompressedImageHDU;
import nom.tam.util.Cursor;

public final class FITSImage {

    private static final int BAD_PIXEL = Integer.MIN_VALUE;

    private FITSImage() {}

    public static URIView.SourceInfo readInfo(File file, int plane) throws Exception {
        FITSData data = readData(file, plane);
        return new URIView.SourceInfo(getHeaderAsXML(data.header()), data.width(), data.height(), null,
                data.calculateClipSet(), planeLabels(data.header()));
    }

    public static ImageBuffer decode(File file, ImageFilter filter, ImageProcessingSettings.FITSParameters state, @Nullable ClipSet.Range clipRange) throws Exception {
        return readData(file, state.plane()).decode(filter, state, clipRange);
    }

    private static FITSData readData(File file, int plane) throws Exception {
        try (Fits f = new Fits(file)) {
            BasicHDU<?> hdu = findHDU(f);
            Header header = imageHeader(hdu);
            int[] axes = imageAxes(header);
            // A plane out of range is a stale setting (a cube's layer count remembered against a
            // plain image, or a different product), not a reason to refuse the file.
            int planes = planeCount(header);
            Object pixels = readFlatPixels(hdu, axes, plane < 0 || plane >= planes ? 0 : plane);
            boolean hasBlank = header.containsKey(Standard.BLANK);
            long blank = hasBlank ? header.getLongValue(Standard.BLANK) : 0;
            double bzero = header.getDoubleValue(Standard.BZERO, 0);
            double bscale = header.getDoubleValue(Standard.BSCALE, 1);
            if (!(pixels instanceof byte[]) && (!Double.isFinite(bzero) || !Double.isFinite(bscale)))
                throw new Exception("Invalid FITS BZERO/BSCALE");

            // The background comes off here, in DN and before anything is normalized, because that is
            // the only place the numbers still mean what the instrument measured. Doing it later, on
            // display values each frame scaled to its own min and max, would subtract a different
            // quantity from every frame. The result is DN per second, so the frames of a movie are
            // also finally on one photometric footing.
            boolean provisional = false;
            try {
                Object subtracted = subtractBackground(header, pixels, axes[0] * axes[1], hasBlank, blank, bzero, bscale);
                if (subtracted != null) {
                    pixels = subtracted;
                    hasBlank = false; // blank pixels already carry the BAD_PIXEL sentinel
                    bzero = 0;
                    bscale = 1;
                }
            } catch (LascoBackground.Unavailable e) {
                // Decode the frame as it is so the layer keeps showing, but say so on the buffer: a
                // frame missing a correction it should have had must not be cached as finished.
                provisional = true;
            }

            float min = header.getFloatValue("HV_DMIN", Float.MAX_VALUE);
            float max = header.getFloatValue("HV_DMAX", Float.MAX_VALUE);
            ClipSet.Range headerRange = min == Float.MAX_VALUE || max == Float.MAX_VALUE ? null : new ClipSet.Range(min, max);
            return new FITSData(header, pixels, axes[1], axes[0], hasBlank, blank, bzero, bscale, headerRange, provisional);
        }
    }

    /**
     * LASCO frame minus its monthly background, in DN per second, or null to leave the frame alone.
     *
     * <p>Returns a float array so the existing float path does the sampling and normalization: the
     * background is a per-pixel offset in physical units, which is exactly what BZERO and BSCALE
     * have already been applied to produce.
     */
    @Nullable
    private static Object subtractBackground(Header header, Object pixels, int count,
                                             boolean hasBlank, long blank, double bzero, double bscale) {
        String telescope = header.getStringValue("TELESCOP");
        if (telescope == null || !telescope.trim().equalsIgnoreCase("SOHO"))
            return null;
        String detector = header.getStringValue("DETECTOR");
        String filter = header.getStringValue("FILTER");
        String polar = header.getStringValue("POLAR");
        double exposure = header.getDoubleValue("EXPTIME", 0);
        if (detector == null || filter == null || polar == null || !(exposure > 0))
            return null;

        // LASCO splits the observation time across DATE-OBS and TIME-OBS and writes the date with
        // slashes, the same form FitsMetaData reassembles for its own use.
        String date = header.getStringValue("DATE-OBS");
        String time = header.getStringValue("TIME-OBS");
        if (date == null || time == null)
            return null;
        long milli;
        try {
            String hms = time.trim();
            milli = TimeUtils.parse(date.trim().replace('/', '-') + 'T' + (hms.length() > 8 ? hms.substring(0, 8) : hms));
        } catch (RuntimeException e) {
            return null;
        }
        float[] background = LascoBackground.perSecond(detector, filter, polar, milli, count);
        if (background == null)
            return null;

        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            double raw = rawAt(pixels, i);
            if (Double.isNaN(raw) || (hasBlank && raw == blank)) {
                out[i] = BAD_PIXEL;
                continue;
            }
            out[i] = (float) ((bzero + raw * bscale) / exposure - background[i]);
        }
        return out;
    }

    private static double rawAt(Object pixels, int i) {
        return switch (pixels) {
            case short[] p -> p[i];
            case int[] p -> p[i];
            case float[] p -> p[i];
            case double[] p -> p[i];
            default -> Double.NaN;
        };
    }

    private static BasicHDU<?> findHDU(Fits fits) throws Exception {
        BasicHDU<?>[] hdus = fits.read();
        // this is cumbersome
        for (BasicHDU<?> hdu : hdus) {
            if (hdu instanceof CompressedImageHDU) {
                return hdu;
            }
        }
        for (BasicHDU<?> hdu : hdus) {
            if (hdu instanceof ImageHDU ihdu && ihdu.getAxes() != null /* might be an extension */) {
                return ihdu;
            }
        }
        throw new Exception("No image found");
    }

    private static Header imageHeader(BasicHDU<?> hdu) throws Exception {
        if (hdu instanceof CompressedImageHDU chdu) {
            return chdu.getImageHeader();
        } else {
            return hdu.getHeader();
        }
    }

    /**
     * The image's own two axes. Anything beyond them is a stack of such images; see {@link #planeCount}.
     *
     * <p>This used to insist on NAXIS == 2, which refused every polarized PUNCH product outright:
     * PTM and CTM are 4096 x 4096 x 3, the B / pB / pBp triplet, and the refusal arrived two
     * milliseconds after the file was opened.
     */
    private static int[] imageAxes(Header header) throws Exception {
        if (header.getIntValue("NAXIS", 0) < 2)
            throw new Exception("Not a FITS image: fewer than two axes");
        int[] axes = {header.getIntValue("NAXIS2", 0), header.getIntValue("NAXIS1", 0)};
        if (axes[0] <= 0 || axes[1] <= 0)
            throw new Exception("Unusable FITS image size: NAXIS1 x NAXIS2 = " + axes[1] + " x " + axes[0]);
        return axes;
    }

    /** How many images the file holds at that size: 1 for a plain image, NAXIS3 x NAXIS4 ... for a cube. */
    private static int planeCount(Header header) {
        int planes = 1;
        for (int axis = 3, nAxis = header.getIntValue("NAXIS", 0); axis <= nAxis; axis++)
            planes *= Math.max(1, header.getIntValue("NAXIS" + axis, 1));
        return planes;
    }

    /**
     * One label per plane, or an empty list when the file holds a single image.
     *
     * <p>PUNCH names the layers of its cubes: OBSLAYR1 = "Polar_B", OBSLAYR2 = "Polar_pB",
     * OBSLAYR3 = "Polar_pBp" (read from PUNCH_L3_PTM_20260421000230_v0l.fits). A file that names
     * nothing gets ordinals, which is still enough to choose by.
     */
    static List<String> planeLabels(Header header) {
        int planes = planeCount(header);
        if (planes < 2)
            return List.of();
        String[] labels = new String[planes];
        for (int i = 0; i < planes; i++) {
            String named = header.getStringValue("OBSLAYR" + (i + 1));
            labels[i] = named == null || named.isBlank() ? "Plane " + (i + 1) : named.trim();
        }
        return List.of(labels);
    }

    @SuppressWarnings("deprecation")
    private static Object readFlatPixels(BasicHDU<?> hdu, int[] axes, int plane) throws Exception {
        int count = axes[0] * axes[1];
        if (hdu instanceof CompressedImageHDU chdu) {
            // A compressed cube decompresses whole and flat, plane-major, so the requested plane is
            // a slice of it. Asking unwrap for one plane more than we want is also the bounds check.
            Object all = unwrapPixelBuffer(chdu.getUncompressedData(), count * (plane + 1));
            return plane == 0 && java.lang.reflect.Array.getLength(all) == count ? all : slice(all, plane * count, count);
        } else if (hdu instanceof ImageHDU ihdu) {
            int nAxis = ihdu.getHeader().getIntValue("NAXIS", 2);
            if (nAxis == 2)
                return ihdu.getData().getTiler().getTile(new int[]{0, 0}, axes);
            // ponytail: the tiler indexes the cube's axes slowest first, so one plane is a 1-deep
            // tile of a three-axis cube. Deeper uncompressed cubes are rejected rather than
            // guessed at; no instrument in hand writes one.
            if (nAxis > 3)
                throw new Exception("Uncompressed FITS cubes deeper than three axes are not supported (NAXIS = " + nAxis + ')');
            return ihdu.getData().getTiler().getTile(new int[]{plane, 0, 0}, new int[]{1, axes[0], axes[1]});
        } else {
            throw new Exception("Unsupported FITS HDU: " + hdu.getClass().getSimpleName());
        }
    }

    private static Object slice(Object all, int from, int count) throws Exception {
        return switch (all) {
            case byte[] p -> Arrays.copyOfRange(p, from, from + count);
            case short[] p -> Arrays.copyOfRange(p, from, from + count);
            case int[] p -> Arrays.copyOfRange(p, from, from + count);
            case long[] p -> Arrays.copyOfRange(p, from, from + count);
            case float[] p -> Arrays.copyOfRange(p, from, from + count);
            case double[] p -> Arrays.copyOfRange(p, from, from + count);
            default -> throw new Exception("Unsupported FITS pixel type: " + all.getClass().getSimpleName());
        };
    }

    private static Object unwrapPixelBuffer(Buffer buffer, int expectedPixels) throws Exception {
        if (!buffer.hasArray() || buffer.arrayOffset() != 0 || buffer.position() != 0 || buffer.remaining() < expectedPixels) {
            throw new Exception("Unsupported compressed FITS pixel buffer: " + buffer.getClass().getSimpleName());
        }
        return buffer.array();
    }

    private static final String nl = System.lineSeparator();
    private static final Escaper XML_CONTENT_ESCAPER = XmlEscapers.xmlContentEscaper();
    private static final Escaper XML_ATTRIBUTE_ESCAPER = XmlEscapers.xmlAttributeEscaper();

    private static String getHeaderAsXML(Header header) {
        StringBuilder builder = new StringBuilder("<meta>" + nl + "<fits>" + nl);

        for (Cursor<String, HeaderCard> iter = header.iterator(); iter.hasNext(); ) {
            HeaderCard headerCard = iter.next();
            String key = headerCard.getKey().trim();
            if ("END".equals(key))
                continue;
            key = key.isEmpty() ? "COMMENT" : key.replace("$", "-"); // allow illegal keyword character in FITS saved by IDL

            String value = headerCard.getValue();
            String val = value == null ? "" : XML_CONTENT_ESCAPER.escape(value);
            String comment = headerCard.getComment();
            String com = comment == null ? "" : " comment=\"" + XML_ATTRIBUTE_ESCAPER.escape(comment) + "\"";

            builder.append('<').append(key).append(com).append('>').append(val).append("</").append(key).append('>').append(nl);
        }
        builder.append("</fits>").append(nl).append("</meta>");
        return builder.toString();
    }
}
