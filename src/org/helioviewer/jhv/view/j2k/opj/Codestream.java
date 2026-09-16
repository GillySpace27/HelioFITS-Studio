package org.helioviewer.jhv.view.j2k.opj;

import java.io.ByteArrayOutputStream;

import javax.annotation.Nullable;

/**
 * Rebuilding a JPEG 2000 codestream from the JPIP data bins a server has sent.
 *
 * <p>This is the half of Kakadu's cache that {@link DataBinCache} does not do. A JPIP server in
 * JPP mode sends the main header, a tile header, and then the packets of each precinct as its own
 * numbered bin. None of that is a codestream: the packets have to be written back in the order the
 * codestream's own progression declares, with the missing ones replaced by empty packets so that
 * everything after them still lines up. The archive's server was asked for whole tiles, which
 * would have made this a concatenation, and answered with precinct bins regardless.
 *
 * <p>Missing detail is not an error here. A packet that says "no code-block contributions" is how
 * JPEG 2000 itself expresses a frame that has arrived only in part, so a half-delivered frame
 * rebuilds into a valid codestream that decodes blurred rather than broken.
 *
 * <p>Ported from OpenJPEG's own JPIP implementation (src/lib/openjpip, BSD-2), in particular its
 * recons_codestream_from_JPPstream and recons_precinct.
 *
 * <p>ponytail: one tile, RPCL progression, and precinct bins used only when complete. That covers
 * every image the Helioviewer archives serve, which are single-tile RPCL. The other progressions
 * put a precinct's layers in several places in the stream, so they need packet lengths rather than
 * a copy; the ceiling is checked and reported rather than guessed at.
 */
public final class Codestream {

    // Bin classes as this fork's JPIP parser numbers them, which is Kakadu's numbering.
    public static final int PRECINCT_BIN = 0;
    public static final int TILE_HEADER_BIN = 1;
    public static final int MAIN_HEADER_BIN = 3;

    private static final int SOC = 0xFF4F, SIZ = 0xFF51, COD = 0xFF52, SOT = 0xFF90, SOD = 0xFF93, EOC = 0xFFD9;
    private static final int RPCL = 2;

    /** What the main header says, as far as rebuilding needs it. */
    public record Header(int width, int height, int offsetX, int offsetY, int tileWidth, int tileHeight,
                         int tilesAcross, int tilesDown, int components, int progression, int layers,
                         int decompositions, boolean precinctsSignalled, int[] precinctX, int[] precinctY) {

        /** Precincts across and down at one resolution, which is how many packets that resolution holds. */
        int precincts(int resolution) {
            if (!precinctsSignalled)
                return 1; // the default partition is larger than any image the archives hold
            int shift = decompositions - resolution;
            int across = ceilDiv(ceilDiv(width - offsetX, 1 << shift), 1 << precinctX[resolution]);
            int down = ceilDiv(ceilDiv(height - offsetY, 1 << shift), 1 << precinctY[resolution]);
            return Math.max(1, across) * Math.max(1, down);
        }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static int u16(byte[] d, int at) {
        return ((d[at] & 0xFF) << 8) | (d[at + 1] & 0xFF);
    }

    private static int u32(byte[] d, int at) {
        return ((d[at] & 0xFF) << 24) | ((d[at + 1] & 0xFF) << 16) | ((d[at + 2] & 0xFF) << 8) | (d[at + 3] & 0xFF);
    }

    /**
     * Read the image and coding parameters out of a main header.
     *
     * @param mainHeader the main header bin, or a whole codestream: both start at the SOC marker
     * @return the parameters, or null when this is not a codestream or stops before the COD marker
     */
    @Nullable
    public static Header parseHeader(byte[] mainHeader) {
        if (mainHeader.length < 4 || u16(mainHeader, 0) != SOC || u16(mainHeader, 2) != SIZ)
            return null;

        int at = 2;
        int width = 0, height = 0, offsetX = 0, offsetY = 0, tileWidth = 0, tileHeight = 0, tileOffsetX = 0, tileOffsetY = 0;
        int components = 0;
        while (at + 4 <= mainHeader.length) {
            int marker = u16(mainHeader, at);
            if (marker == SOT || marker == SOD)
                break;
            int length = u16(mainHeader, at + 2);
            if (at + 2 + length > mainHeader.length)
                break; // the header stops mid-marker: nothing here can be trusted past this point

            if (marker == SIZ) {
                // marker, Lsiz, Rsiz, then the sizes: A.5.1
                width = u32(mainHeader, at + 6);
                height = u32(mainHeader, at + 10);
                offsetX = u32(mainHeader, at + 14);
                offsetY = u32(mainHeader, at + 18);
                tileWidth = u32(mainHeader, at + 22);
                tileHeight = u32(mainHeader, at + 26);
                tileOffsetX = u32(mainHeader, at + 30);
                tileOffsetY = u32(mainHeader, at + 34);
                components = u16(mainHeader, at + 38);
            } else if (marker == COD) {
                int scod = mainHeader[at + 4] & 0xFF;
                int progression = mainHeader[at + 5] & 0xFF;
                int layers = u16(mainHeader, at + 6);
                int decompositions = mainHeader[at + 9] & 0xFF;
                boolean signalled = (scod & 1) != 0;

                int[] px = new int[decompositions + 1];
                int[] py = new int[decompositions + 1];
                for (int r = 0; r <= decompositions; r++) {
                    int pp = signalled ? mainHeader[at + 14 + r] & 0xFF : 0xFF;
                    px[r] = signalled ? pp & 0x0F : 15;
                    py[r] = signalled ? (pp >> 4) & 0x0F : 15;
                }
                if (width == 0 || components == 0)
                    return null; // COD before SIZ is not a codestream we can read

                int across = ceilDiv(width - tileOffsetX, tileWidth);
                int down = ceilDiv(height - tileOffsetY, tileHeight);
                return new Header(width, height, offsetX, offsetY, tileWidth, tileHeight, across, down,
                        components, progression, layers, decompositions, signalled, px, py);
            }
            at += 2 + length;
        }
        return null;
    }

    /**
     * Rebuild what has arrived for one codestream into something a decoder can read.
     *
     * @return the codestream, or null while the main header or the tile header is still missing
     * @throws UnsupportedOperationException for an image this rebuild does not cover, rather than
     *         returning a stream that would decode into something wrong
     */
    @Nullable
    public static byte[] build(DataBinCache cache, long codestream) {
        byte[] mainHeader = cache.bytes(MAIN_HEADER_BIN, codestream, 0);
        if (mainHeader == null || !cache.isComplete(MAIN_HEADER_BIN, codestream, 0))
            return null;

        Header header = parseHeader(mainHeader);
        if (header == null)
            return null;
        if (header.tilesAcross() * header.tilesDown() != 1)
            throw new UnsupportedOperationException("the rebuild covers single-tile images, not "
                    + header.tilesAcross() + "x" + header.tilesDown() + " tiles");
        if (header.progression() != RPCL)
            throw new UnsupportedOperationException("the rebuild covers RPCL progression, not order "
                    + header.progression());

        if (!cache.isComplete(TILE_HEADER_BIN, codestream, 0))
            return null;
        byte[] tileHeader = cache.bytes(TILE_HEADER_BIN, codestream, 0);

        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        out.writeBytes(mainHeader);

        int sotOffset = out.size();
        out.write(SOT >> 8);
        out.write(SOT & 0xFF);
        if (tileHeader != null && tileHeader.length >= 10 && u16(tileHeader, 0) == 10) {
            out.writeBytes(tileHeader); // the bin carries the tile-part header, starting at Lsot
        } else {
            // This server sends an empty tile header bin: the tile-part header is ours to write.
            // A.4.2: one tile, one tile-part, length patched once the packets are in.
            out.write(0); out.write(10);    // Lsot
            out.write(0); out.write(0);     // Isot, the only tile
            out.write(0); out.write(0); out.write(0); out.write(0); // Psot, patched below
            out.write(0);                   // TPsot, the first tile-part
            out.write(1);                   // TNsot, the only one
            if (tileHeader != null)
                out.writeBytes(tileHeader); // any markers the server did send
        }
        if (!endsWith(out, SOD)) {
            out.write(SOD >> 8);
            out.write(SOD & 0xFF);
        }

        // Resolution by resolution, precinct by precinct, component by component: the order RPCL
        // declares, which is also the order the packets of one precinct sit in its bin.
        long sequence = 0;
        for (int r = 0; r <= header.decompositions(); r++) {
            int precincts = header.precincts(r);
            for (int p = 0; p < precincts; p++, sequence++)
                for (int c = 0; c < header.components(); c++) {
                    long bin = precinctBin(c, sequence, header.components());
                    byte[] packets = cache.isComplete(PRECINCT_BIN, codestream, bin)
                            ? cache.bytes(PRECINCT_BIN, codestream, bin) : null;
                    if (packets != null)
                        out.writeBytes(packets);
                    else if (header.precinctsSignalled())
                        for (int layer = 0; layer < header.layers(); layer++)
                            out.write(0); // an empty packet: no code-block contributions in this one
                }
        }

        int tilePartLength = out.size() - sotOffset; // Psot counts the tile-part, not the EOC after it

        out.write(EOC >> 8);
        out.write(EOC & 0xFF);

        byte[] stream = out.toByteArray();
        writeTilePartLength(stream, sotOffset, tilePartLength);
        return stream;
    }

    private static boolean endsWith(ByteArrayOutputStream out, int marker) {
        byte[] tail = out.toByteArray();
        return tail.length >= 2 && u16(tail, tail.length - 2) == marker;
    }

    /** The in-class identifier of a precinct bin, JPIP A.3.2, for the single-tile case. */
    private static long precinctBin(int component, long sequence, int components) {
        return component + sequence * components;
    }

    /** Psot, the tile-part length, which the server's tile header cannot know in advance. */
    private static void writeTilePartLength(byte[] stream, int sotOffset, int length) {
        int at = sotOffset + 6; // marker, Lsot, Isot
        stream[at] = (byte) (length >>> 24);
        stream[at + 1] = (byte) (length >>> 16);
        stream[at + 2] = (byte) (length >>> 8);
        stream[at + 3] = (byte) length;
    }

    private Codestream() {}

}
