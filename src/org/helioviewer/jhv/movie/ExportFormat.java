package org.helioviewer.jhv.movie;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.display.HdrTransfer;

/**
 * The codec/container half of an export. Pixel depth and colour sampling are separate axes,
 * because folding them in here is what produced entries like "H.265 10-bit" alongside "H.265
 * better": the list grows as the product of every choice, and most of the combinations never get
 * named. A codec now declares which {@link Depth} and {@link Chroma} it can carry, and the UI
 * offers only those.
 *
 * <p>Every supported pair below was read off this build's own encoders rather than assumed;
 * extra/test/ExportFormatCheck.java pins the ones that would fail silently.
 */
public enum ExportFormat {
    H264("H.264", ".mp4",
            List.of("-c:v", "libx264", "-level", "4.2", "-crf", "23", "-preset", "fast", "-tune", "animation"),
            "-x264-params", "colorprim=bt709:transfer=bt709:colormatrix=bt709:fullrange=on"),
    H264HQ("H.264 better", ".mp4",
            List.of("-c:v", "libx264", "-level", "4.2", "-crf", "17", "-preset", "medium", "-tune", "animation"),
            "-x264-params", "colorprim=bt709:transfer=bt709:colormatrix=bt709:fullrange=on"),
    H265("H.265", ".mp4",
            List.of("-c:v", "libx265", "-tag:v", "hvc1", "-crf", "28", "-preset", "fast", "-tune", "animation"),
            "-x265-params", "colorprim=bt709:transfer=bt709:colormatrix=bt709:range=full"),
    H265HQ("H.265 better", ".mp4",
            List.of("-c:v", "libx265", "-tag:v", "hvc1", "-crf", "22", "-preset", "medium", "-tune", "animation"),
            "-x265-params", "colorprim=bt709:transfer=bt709:colormatrix=bt709:range=full"),
    /**
     * The same encoder, delivering the extended range instead of throwing it away.
     *
     * <p>An SDR export clamps at interface white, so everything the HDR gain was carrying is lost
     * at the last step and the file looks blown out where the screen looked right. These two carry
     * it: the capture converts the render to absolute luminance (diffuse white at 203 cd/m2 per
     * ITU-R BT.2408, so a gain of 4 is 812) in BT.2020 primaries, and the curve is what the file
     * is tagged with. See display/HdrTransfer.java for the arithmetic.
     *
     * <p>HLG first because the ask was for something easy to play: a player that ignores the
     * tagging still shows a sane picture, where PQ without tone mapping looks flat and dark. PQ is
     * here for the cases where absolute nits matter more than graceful degradation, a dome master
     * being the obvious one. Both are Main 10 in an MP4 tagged hvc1, which QuickTime, Safari, iOS
     * and VLC play directly.
     */
    H265_HLG("H.265 HDR (HLG)", ".mp4",
            List.of("-c:v", "libx265", "-tag:v", "hvc1", "-crf", "22", "-preset", "medium", "-tune", "animation",
                    "-vf", "scale=out_color_matrix=bt2020nc:out_range=tv"),
            "-x265-params", "colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc:range=limited"),
    H265_PQ("H.265 HDR (PQ)", ".mp4",
            List.of("-c:v", "libx265", "-tag:v", "hvc1", "-crf", "22", "-preset", "medium", "-tune", "animation",
                    "-vf", "scale=out_color_matrix=bt2020nc:out_range=tv"),
            "-x265-params", "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:range=limited"),
    /**
     * Mathematically lossless, and at RGB 16-bit it is bit-exact with what the framebuffer handed
     * over: no colour conversion, no quantization, nothing to argue about later. Verified by
     * round-tripping a 30-frame capture back to raw and comparing byte for byte.
     *
     * <p>The same data as the PNG series, in one file instead of a directory, and about 28 percent
     * smaller than raw. FFV1 has no inter-frame prediction at all, so it is always all-intra and
     * the keyframe checkbox is moot for it. MKV rather than MP4: FFV1 has no MP4 mapping.
     */
    FFV1("FFV1 (lossless)", ".mkv", List.of("-c:v", "ffv1", "-level", "3"), null, null),
    PNG("PNG series (16-bit, lossless)", "%04d.png", List.of("-r", "1"), null, null),
    /**
     * Layered OpenEXR frames, written by JHV itself (ExrWriter, ExrCapture) rather than ffmpeg.
     * R,G,B,A carry the on-screen composite, linearized, so any viewer shows the picture; each
     * enabled layer then sits under its own prefix, image layers as grey data (.Y, the decoded
     * value before any slider; .V, the value the colour table was indexed with; .A, footprint)
     * with the colour table and every display setting in the header, overlays as premultiplied
     * RGBA. Half float, ZIP: a quarter the size of the float files ffmpeg wrote, and readable by
     * macOS, which refused those for their missing pixelAspectRatio attribute.
     *
     * <p>Falls back to 8-bit capture if the driver cannot render to RGBA16F.
     */
    EXR("EXR series (layered, half float)", "%04d.exr", List.of(), null, null);

    /** Bits per channel of the encoded output. Not the capture depth, which is 16 or 8. */
    public enum Depth {
        EIGHT(8), TEN(10), TWELVE(12), SIXTEEN(16);

        public final int bits;

        Depth(int _bits) {
            bits = _bits;
        }

        @Override
        public String toString() {
            return bits + "-bit";
        }
    }

    /**
     * How colour is carried. The J:a:b numbers count chroma samples in a block 4 wide and 2 tall:
     * 4:4:4 is one chroma sample per pixel, 4:2:0 is one per 2x2 pixels, a quarter as many.
     * Subsampling banks on the eye resolving colour worse than brightness, which holds for a
     * photograph and fails for a colour table, where the hue IS the measurement.
     */
    public enum Chroma {
        YUV420("4:2:0"), YUV444("4:4:4"), RGB("RGB");

        private final String label;

        Chroma(String _label) {
            label = _label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final String name;
    final String extension;
    private final List<String> base;
    @Nullable private final String paramFlag; // -x264-params / -x265-params, null if the codec has none
    @Nullable private final String paramBase;

    /** Frame-per-file formats: numbered into their own directory rather than one video file. */
    public boolean isSeries() {
        return this == PNG || this == EXR;
    }

    /** A series carries its own depth and sampling; only the video formats take the two axes. */
    public boolean isConfigurable() {
        return !isSeries();
    }

    /** The transfer this format delivers, and what the capture has to encode into. */
    public HdrTransfer.Curve hdrCurve() {
        return switch (this) {
            case H265_HLG -> HdrTransfer.Curve.HLG;
            case H265_PQ -> HdrTransfer.Curve.PQ;
            default -> HdrTransfer.Curve.NONE;
        };
    }

    public boolean supports(Chroma chroma, Depth depth) {
        return switch (this) {
            // libx264 here offers 4:2:0 and 4:4:4 at 8 and 10 bits, and no planar RGB at all.
            case H264, H264HQ -> chroma != Chroma.RGB && (depth == Depth.EIGHT || depth == Depth.TEN);
            // libx265 adds 12-bit and gbrp, and stops short of 16: the standard's 16-bit intra
            // profile exists but x265 does not implement it, so nothing here can write one.
            case H265, H265HQ -> depth != Depth.SIXTEEN;
            // HDR is carried in a YUV matrix and needs the code words: 10 or 12 bit, never RGB
            // (which drops the matrix) and never 8, where the curves band visibly.
            case H265_HLG, H265_PQ -> chroma != Chroma.RGB && (depth == Depth.TEN || depth == Depth.TWELVE);
            // FFV1 reaches 16, but has no 8-bit planar RGB, hence the one exclusion.
            case FFV1 -> !(chroma == Chroma.RGB && depth == Depth.EIGHT);
            // A series has exactly one answer rather than none. Reporting it lets the UI show what
            // the format will do, greyed out, instead of two empty boxes that read as broken.
            case PNG, EXR -> chroma == Chroma.RGB && depth == Depth.SIXTEEN;
        };
    }

    public List<Depth> depths(Chroma chroma) {
        List<Depth> list = new ArrayList<>(Depth.values().length);
        for (Depth d : Depth.values())
            if (supports(chroma, d))
                list.add(d);
        return list;
    }

    public List<Chroma> chromas() {
        List<Chroma> list = new ArrayList<>(Chroma.values().length);
        for (Chroma c : Chroma.values())
            for (Depth d : Depth.values())
                if (supports(c, d)) {
                    list.add(c);
                    break;
                }
        return list;
    }

    /** The nearest supported pair to what was asked for, so a stale setting cannot wedge a codec. */
    public Chroma clamp(Chroma chroma) {
        List<Chroma> available = chromas();
        return available.isEmpty() ? Chroma.YUV420 : available.contains(chroma) ? chroma : available.getFirst();
    }

    public Depth clamp(Chroma chroma, Depth depth) {
        List<Depth> available = depths(clamp(chroma));
        return available.isEmpty() ? Depth.EIGHT : available.contains(depth) ? depth : available.getFirst();
    }

    /** ffmpeg's name for the pair, or null for a series, which lets ffmpeg pick. */
    @Nullable
    public String pixFmt(Chroma chroma, Depth depth) {
        if (isSeries())
            return null;
        // gbrp, not rgb48le, even though rgb48le is the layout the grab hands over: ffmpeg maps
        // one to the other anyway, and naming what actually gets written keeps "what was asked
        // for" and "what came out" comparable. The reordering is lossless -- a 4-frame capture
        // round-tripped through FFV1 gbrp16le and back to raw compares byte for byte.
        String stem = switch (chroma) {
            case YUV420 -> "yuv420p";
            case YUV444 -> "yuv444p";
            case RGB -> "gbrp";
        };
        return depth == Depth.EIGHT ? stem : stem + depth.bits + "le";
    }

    /**
     * The encoder options for one combination, with the codec's private parameter string built
     * once. It has to be assembled rather than concatenated at the call site: ffmpeg lets the last
     * -x265-params win outright, so a second one carrying the 4:4:4 fix would silently drop the
     * colour signalling in the first.
     *
     * <p>The one combination-dependent entry is that fix. x265 forces a +6 chroma QP offset on
     * 4:4:4 input whenever psy-rd is on, which halves exactly the chroma quality 4:4:4 was chosen
     * for, and it overrides any attempt to set the offset back down, so psy-rd has to go instead.
     * Measured at CRF 22, 10-bit, against the 16-bit source: 4:2:0 gave u 34.62 / v 35.85 dB,
     * 4:4:4 with the forced offset gave u 35.86 / v 36.74, and 4:4:4 with psy-rd off gave
     * u 38.46 / v 39.21 for 11 percent more file and no measurable luma cost. psy-rd protects
     * perceived texture in natural video; on a colour-mapped render it trades away the data.
     */
    List<String> settings(Chroma chroma, Depth depth) {
        List<String> out = new ArrayList<>(base.size() + 4);
        out.addAll(base);

        // H.264 names each (sampling, depth) combination as its own profile, and asking for one
        // that cannot hold the pixel format is a hard error rather than a downgrade: "high profile
        // doesn't support 4:4:4". So the profile is derived, never fixed. x265 needs no equivalent,
        // it picks its own profile from the input.
        if (this == H264 || this == H264HQ) {
            out.add("-profile:v");
            out.add(chroma == Chroma.YUV444 ? "high444" : depth == Depth.EIGHT ? "high" : "high10");
        }

        // Colour signalling, which is why this cannot live with the container options either.
        // Naming a YUV colourspace on an RGB pixel format makes ffmpeg insert a conversion to
        // satisfy it, so asking for gbrp quietly produced yuv444p: an "RGB" export that had been
        // through the very matrix it exists to avoid. RGB keeps the primaries and transfer, which
        // still mean something, and drops the matrix, which does not.
        if (!isSeries()) {
            if (hdrCurve() != HdrTransfer.Curve.NONE) {
                // Limited range, because that is what every HDR decoder expects, and the frames
                // are handed over as full-range RGB for the scaler to map.
                out.addAll(List.of("-color_primaries", "bt2020",
                        "-color_trc", hdrCurve() == HdrTransfer.Curve.HLG ? "arib-std-b67" : "smpte2084",
                        "-colorspace", "bt2020nc", "-color_range", "1"));
            } else {
                out.addAll(List.of("-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "2"));
                if (chroma != Chroma.RGB)
                    out.addAll(List.of("-colorspace", "bt709"));
            }
        }

        // x265 picks its own profile, and for this configuration it picked Rext rather than
        // Main 10: decodable in software, but Apple's hardware decoder keys on the profile, and a
        // file that falls back to software decode is not the "easy to play" this format is for.
        // Only 4:2:0 at 10 bits, which is what Main 10 means; the wider combinations are Rext by
        // definition and naming Main 10 there would be a hard error rather than a downgrade.
        if (hdrCurve() != HdrTransfer.Curve.NONE && chroma == Chroma.YUV420 && depth == Depth.TEN)
            out.addAll(List.of("-profile:v", "main10"));

        if (paramFlag != null && paramBase != null) {
            String params = chroma == Chroma.RGB ? paramBase.replace(":colormatrix=bt709", "") : paramBase;
            if ((this == H265 || this == H265HQ || this == H265_HLG || this == H265_PQ) && chroma == Chroma.YUV444)
                params += ":psy-rd=0";
            out.add(paramFlag);
            out.add(params);
        }
        return out;
    }

    /**
     * Whether the capture should be asked for an RGBA16F target rather than RGB8. An 8-bit
     * framebuffer would quantize to 256 levels before the encoder ever saw the pixels, and no
     * output depth can put that back.
     */
    public boolean wantsHighBitDepth(Depth depth) {
        return isSeries() || depth != Depth.EIGHT;
    }

    ExportFormat(String _name, String _extension, List<String> _base, @Nullable String _paramFlag, @Nullable String _paramBase) {
        name = _name;
        extension = _extension;
        base = _base;
        paramFlag = _paramFlag;
        paramBase = _paramBase;
    }

    @Override
    public String toString() {
        return name;
    }

}
