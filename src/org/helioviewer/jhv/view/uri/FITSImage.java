package org.helioviewer.jhv.view.uri;

import java.io.File;
import java.nio.Buffer;

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

    public static URIView.SourceInfo readInfo(File file) throws Exception {
        FITSData data = readData(file);
        return new URIView.SourceInfo(getHeaderAsXML(data.header()), data.width(), data.height(), null, data.calculateClipSet());
    }

    public static ImageBuffer decode(File file, ImageFilter filter, ImageProcessingSettings.FITSParameters state, @Nullable ClipSet.Range clipRange) throws Exception {
        return readData(file).decode(filter, state, clipRange);
    }

    private static FITSData readData(File file) throws Exception {
        try (Fits f = new Fits(file)) {
            BasicHDU<?> hdu = findHDU(f);
            Header header = imageHeader(hdu);
            int[] axes = imageAxes(header);
            Object pixels = readFlatPixels(hdu, axes);
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

    private static int[] imageAxes(Header header) throws Exception {
        int nAxis = header.getIntValue("NAXIS", 0);
        if (nAxis != 2)
            throw new Exception("Only 2D FITS files supported");
        int[] axes = {header.getIntValue("NAXIS2", 0), header.getIntValue("NAXIS1", 0)};
        if (axes[0] <= 0 || axes[1] <= 0)
            throw new Exception("Only 2D FITS files supported");
        return axes;
    }

    @SuppressWarnings("deprecation")
    private static Object readFlatPixels(BasicHDU<?> hdu, int[] axes) throws Exception {
        if (hdu instanceof CompressedImageHDU chdu) {
            return unwrapPixelBuffer(chdu.getUncompressedData(), axes[0] * axes[1]);
        } else if (hdu instanceof ImageHDU ihdu) {
            return ihdu.getData().getTiler().getTile(new int[]{0, 0}, axes);
        } else {
            throw new Exception("Unsupported FITS HDU: " + hdu.getClass().getSimpleName());
        }
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
