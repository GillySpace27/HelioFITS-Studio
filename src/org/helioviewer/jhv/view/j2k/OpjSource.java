package org.helioviewer.jhv.view.j2k;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.view.j2k.opj.Codestream;
import org.helioviewer.jhv.view.j2k.opj.DataBinCache;
import org.helioviewer.jhv.view.j2k.opj.Jp2Boxes;

/**
 * What a JPEG 2000 image is, read without Kakadu.
 *
 * <p>Kakadu answered four questions for every image the viewer opened: how many frames it has,
 * how large each is at each resolution, what the FITS header of each frame says, and whether the
 * file carries its own colour table. This answers the same four from the boxes and the codestream
 * headers, for a file on disk and for a JPIP session alike.
 *
 * <p>The two differ only in where a frame's codestream comes from. A file holds each one in a
 * jp2c box. A JPIP session holds none of them: it sends data bins, a placeholder box stands where
 * each codestream would be, and {@link Codestream} rebuilds one on demand from whatever has
 * arrived. Counting those placeholders is how a movie's frames are counted before any of them has
 * been delivered.
 */
final class OpjSource {

    /** Bin classes as this fork's JPIP parser numbers them, which is Kakadu's numbering. */
    private static final int METADATA_BIN = 4;

    @Nullable
    private final DataBinCache bins;      // a JPIP session, or null for a file
    private final byte[] boxes;           // the file, or the metadata bin
    private final List<byte[]> inline;    // codestreams the file carries, empty over JPIP
    private final List<String> headers;   // one FITS header per frame, in frame order
    private final int frames;

    private OpjSource(@Nullable DataBinCache _bins, byte[] _boxes, List<byte[]> _inline, List<String> _headers, int _frames) {
        bins = _bins;
        boxes = _boxes;
        inline = _inline;
        headers = _headers;
        frames = _frames;
    }

    static OpjSource ofFile(String path) throws IOException {
        byte[] data = Files.readAllBytes(Path.of(path));
        if (!Jp2Boxes.isBoxed(data)) // a bare codestream, which some tools write
            return new OpjSource(null, new byte[0], List.of(data), List.of(), 1);

        List<byte[]> codestreams = Jp2Boxes.codestreams(data);
        List<String> xml = Jp2Boxes.xmls(data);
        return new OpjSource(null, data, codestreams, xml, Math.max(1, codestreams.size()));
    }

    static OpjSource ofCache(DataBinCache cache) {
        byte[] metadata = cache.bytes(METADATA_BIN, 0, 0);
        if (metadata == null)
            return new OpjSource(cache, new byte[0], List.of(), List.of(), 1);

        List<String> xml = Jp2Boxes.xmls(metadata);
        // One placeholder per codestream the server has not sent inline, which is all of them.
        int frames = Math.max(1, Math.max(Jp2Boxes.placeholders(metadata), xml.size()));
        return new OpjSource(cache, metadata, List.of(), xml, frames);
    }

    int frameCount() {
        return frames;
    }

    /** The codestream of one frame, or null while a JPIP session has not delivered enough of it. */
    @Nullable
    byte[] codestream(int frame) {
        if (bins != null)
            return Codestream.build(bins, frame);
        return frame >= 0 && frame < inline.size() ? inline.get(frame) : null;
    }

    /** The frame's FITS header, or null when this image carries none. */
    @Nullable
    String header(int frame) {
        return frame >= 0 && frame < headers.size() ? headers.get(frame) : null;
    }

    /**
     * The resolutions of one frame, or null while its header has not arrived.
     *
     * <p>A JPEG 2000 image is decodable at every power of two down from its own size, one level
     * per wavelet decomposition, and that ladder is what the viewer picks from when it asks for a
     * frame no larger than the screen needs.
     */
    @Nullable
    ResolutionSet resolutionSet(int frame) {
        byte[] codestream = codestream(frame);
        Codestream.Header header = codestream == null ? null : Codestream.parseHeader(codestream);
        if (header == null)
            return null;

        int levels = header.decompositions() + 1;
        ResolutionSet set = new ResolutionSet(levels, header.components());
        int width0 = header.width() - header.offsetX(), height0 = header.height() - header.offsetY();
        for (int i = 0; i < levels; i++) {
            int width = ceilDiv(width0, 1 << i), height = ceilDiv(height0, 1 << i);
            set.addLevel(i, width, height, width0 / (double) width, height0 / (double) height);
        }
        return set;
    }

    private static int ceilDiv(int a, int b) {
        return Math.max(1, (a + b - 1) / b);
    }

    /**
     * The palette a file carries, when it has one.
     *
     * <p>Rare in these archives, whose images are greyscale and take their colour from the
     * viewer's own tables, but a file that carries one means it, and dropping it would recolour
     * the picture silently.
     */
    @Nullable
    LUT lut() {
        for (Jp2Boxes.Box box : Jp2Boxes.walk(boxes))
            if ("jp2h".equals(box.type()))
                for (Jp2Boxes.Box inner : Jp2Boxes.walk(boxes, box.contentAt(), box.contentAt() + box.contentLength()))
                    if ("pclr".equals(inner.type()))
                        return palette(boxes, inner.contentAt(), inner.contentLength());
        return null;
    }

    @Nullable
    private static LUT palette(byte[] data, int at, int length) {
        if (length < 3)
            return null;
        int entries = ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
        int channels = data[at + 2] & 0xFF;
        if (entries <= 0 || channels < 3)
            return null; // a palette that cannot make a colour is not one this can use

        int[] depth = new int[channels];
        int cursor = at + 3;
        for (int c = 0; c < channels; c++)
            depth[c] = (data[cursor++] & 0x7F) + 1; // the high bit is the sign, which a palette does not use
        for (int c = 0; c < channels; c++)
            if (depth[c] != 8)
                return null; // ponytail: eight bits a channel, which is every palette these archives write

        if (cursor + entries * channels > at + length)
            return null;

        byte[] red = new byte[entries], green = new byte[entries], blue = new byte[entries];
        for (int e = 0; e < entries; e++) {
            red[e] = data[cursor + e * channels];
            green[e] = data[cursor + e * channels + 1];
            blue[e] = data[cursor + e * channels + 2];
        }
        return LUT.fromOpaqueRgb("built-in", red, green, blue);
    }

    /** Whether anything at all has arrived for a frame, which over JPIP is not a given. */
    boolean hasFrame(int frame) {
        return codestream(frame) != null;
    }

    @Override
    public String toString() {
        return (bins == null ? "file" : "jpip") + " source, " + frames + " frame(s), "
                + headers.size() + " header(s), " + new String(boxes, 0, Math.min(4, boxes.length), StandardCharsets.ISO_8859_1);
    }

}
