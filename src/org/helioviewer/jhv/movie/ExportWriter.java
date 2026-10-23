package org.helioviewer.jhv.movie;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.image.nio.MappedImageFactory;
import org.helioviewer.jhv.image.nio.NativeImageFactory;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.time.TimeUtils;

import org.json.JSONObject;

class ExportWriter {

    private static final long FFMPEG_TIMEOUT_MINUTES = 30;
    private static final List<String> ffmpeg = List.of(new File(Directories.libCacheDir, "ffmpeg").getAbsolutePath());

    private final String prefix;
    private final ExportFormat format;
    private final int w;
    private final int h;
    private final int fps;
    private final boolean allIntra;
    private final ExportFormat.Chroma chroma;
    private final ExportFormat.Depth depth;
    private final @Nullable JSONObject provenance; // Provenance.session() taken when the recording started
    private int bytesPerPixel = 3; // 3 = rgb24, 6 = rgb48le; set by the first encoded frame

    private File tempFile;
    private @Nullable Exception failure;

    ExportWriter(ExportFormat _format, ExportFormat.Chroma _chroma, ExportFormat.Depth _depth,
                 int _w, int _h, int _fps, boolean _allIntra, @Nullable JSONObject _provenance) {
        // Name the export after the current session so a recording is self-identifying.
        String session = org.helioviewer.jhv.app.Session.displayName();
        String base = "Untitled".equals(session) ? "HFStudio" : session.replaceAll("[^A-Za-z0-9._-]", "_");
        prefix = Directories.EXPORTS.getPath() + base + "_" + TimeUtils.formatFilename(System.currentTimeMillis());
        format = _format;
        chroma = _format.clamp(_chroma);
        depth = _format.clamp(_chroma, _depth);
        w = _w;
        h = _h;
        fps = _fps;
        allIntra = _allIntra;
        provenance = _provenance;
    }

    ExportFormat format() {
        return format;
    }

    int fps() {
        return fps;
    }

    private int exrFrames;

    /** A layered EXR frame is its own file: nothing for ffmpeg to do at close. */
    void encodeExr(ExrWriter frame) throws Exception {
        frame.write(new File(seriesDir(), String.format("frame%04d.exr", ++exrFrames)));
    }

    // A frame-per-file format gets its own directory. Numbering them into Exports/ alongside
    // everything else turned one recording into hundreds of loose files interleaved with every
    // other export.
    private File seriesDir() throws Exception {
        File dir = new File(prefix);
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new Exception("Could not create the frame directory: " + prefix);
        return dir;
    }

    void encode(@Nullable BufferedImage mainImage, @Nullable BufferedImage timelineImage,
                int movieLinePosition, int _bytesPerPixel) throws Exception {
        bytesPerPixel = _bytesPerPixel;
        if (tempFile == null) {
            tempFile = File.createTempFile("dump", null, Directories.exportCacheDir);
            tempFile.deleteOnExit();
        }

        int mainH = mainImage == null ? 0 : mainImage.getHeight();
        BufferedImage scaled = null;
        ByteBuffer timelineData = null;
        if (timelineImage != null) {
            scaled = ExportUtils.scaleImage(timelineImage, w, h - mainH, movieLinePosition);
            timelineData = NativeImageFactory.getByteBuffer(scaled).clear().limit(3 * w * scaled.getHeight());
        }

        try (FileChannel channel = FileChannel.open(tempFile.toPath(), StandardOpenOption.APPEND)) {
            if (mainImage != null) {
                ByteBuffer mainData = MappedImageFactory.getByteBuffer(mainImage).clear().limit(bytesPerPixel * w * mainH);
                for (int j = mainH - 1; j >= 0; j--) { // write image flipped
                    int pos = bytesPerPixel * w * j;
                    mainData.position(pos);
                    mainData.limit(pos + bytesPerPixel * w);
                    writeFully(channel, mainData);
                }
            }
            if (timelineData != null)
                // The timeline strip is composited at 8 bits whatever the main frame is, and the two
                // are concatenated into one raw frame: leaving it narrow would both garble the
                // strip and make the frame the wrong length for ffmpeg, which is counting bytes.
                writeFully(channel, bytesPerPixel == 6 ? widenTo16(timelineData) : timelineData);
        } catch (Exception e) {
            tempFile.delete();
            tempFile = null;
            throw e;
        } finally {
            NativeImageFactory.free(scaled);
        }
    }

    // 8-bit samples to 16-bit little-endian. 257 = 65535/255, so 0 stays 0 and 255 reaches full
    // scale rather than landing at 65280 and darkening the strip by a quarter of a percent.
    private static ByteBuffer widenTo16(ByteBuffer src) {
        ByteBuffer out = ByteBuffer.allocate(2 * src.remaining());
        while (src.hasRemaining()) {
            int v = (src.get() & 0xFF) * 257;
            out.put((byte) v).put((byte) (v >>> 8));
        }
        return out.flip();
    }

    void recordFailure(Exception e) {
        if (failure == null)
            failure = e;
    }

    private static void writeFully(FileChannel channel, ByteBuffer data) throws Exception {
        while (data.hasRemaining()) {
            channel.write(data);
        }
    }

    // What is left here is what depends only on the CONTAINER. Everything that varies with the
    // codec or the pixel combination moved to ExportFormat.settings: -pix_fmt (pinned to 8-bit,
    // it capped the video path at 256 levels while the grab handed over 16), -tune (an encoder
    // private option FFV1 does not have), -profile:v (H.264 errors rather than downgrades when
    // the profile cannot hold the format), and the colour signalling (naming a YUV colourspace
    // made ffmpeg convert an RGB export into YUV to satisfy it).
    private static final List<String> formatVideo = List.of(
            // One -movflags wins over another in ffmpeg, so combine them: +faststart moves the
            // moov atom to the front, which is what makes the file seek/scrub responsively.
            "-movflags", "+faststart+write_colr"
    );
    private static final List<String> formatImage = List.of(
            "-vf", "scale=in_range=pc:out_range=pc"
    );

    @Nullable
    String close() throws Exception {
        return close(null);
    }

    /**
     * As close(), with the processing record from ProcessingRecord.prepare (null for none) put
     * inside the file beside the session: a tEXt chunk in each PNG, a metadata tag in a movie. An
     * EXR frame took its copy when it was captured.
     */
    @Nullable
    String close(@Nullable JSONObject record) throws Exception {
        if (failure != null) {
            if (tempFile != null)
                tempFile.delete();
            throw failure;
        }
        if (format == ExportFormat.EXR)
            return exrFrames == 0 ? null : new File(prefix, "frame" + format.extension).getPath();
        if (tempFile == null) // unlikely reach here on encode error
            return null;

        try {
            String outPath;
            if (format.isSeries()) {
                outPath = new File(seriesDir(), "frame" + format.extension).getPath();
            } else {
                outPath = prefix + format.extension;
            }
            File meta = record == null || format.isSeries() ? null : movieMetadata(record);
            try {
                runFFmpeg(buildCommand(outPath, meta));
            } finally {
                if (meta != null)
                    meta.delete();
            }
            if (format == ExportFormat.PNG)
                stampPngSeries(record);
            return outPath;
        } catch (Exception e) {
            deleteOutputs();
            throw e;
        } finally {
            tempFile.delete();
            tempFile = null;
        }
    }

    private List<String> buildCommand(String outPath, @Nullable File meta) {
        List<String> input = List.of(
                "-hide_banner",
                "-f", "rawvideo",
                "-pix_fmt", bytesPerPixel == 6 ? "rgb48le" : "rgb24",
                "-r", format.isSeries() ? "1" : String.valueOf(fps),
                "-s", w + "x" + h,
                "-i", tempFile.getPath()
        );

        List<String> command = new ArrayList<>(ffmpeg);
        command.addAll(input);
        if (meta != null) // a second input holding only metadata; its tags become the output's
            command.addAll(List.of("-f", "ffmetadata", "-i", meta.getPath(), "-map_metadata", "1"));
        command.addAll(meta == null ? encodeArgs(format, chroma, depth, w, h, fps, allIntra)
                : withMetadataTags(encodeArgs(format, chroma, depth, w, h, fps, allIntra)));
        if (meta == null) // else the comment is in the metadata file with the rest
            command.addAll(metadataArgs(format, provenance == null ? null : Provenance.metadataComment(provenance)));
        command.add("-y");
        command.add(outPath);
        return command;
    }

    /**
     * Everything between the raw input and the output path: codec, colour, container, pixel
     * format, keyframing. A static function of its inputs so ExportCompatibilityCheck can pin what
     * the presets a person hands to someone else actually put on the command line.
     */
    /** setparams for the -color_primaries, -color_trc and -colorspace a format sets, or null for none. */
    @Nullable
    static String colourTags(List<String> args) {
        List<String> tags = new ArrayList<>();
        String[][] map = {{"-color_primaries", "color_primaries"}, {"-color_trc", "color_trc"}, {"-colorspace", "colorspace"}};
        for (String[] m : map) {
            int i = args.indexOf(m[0]);
            if (i >= 0 && i + 1 < args.size())
                tags.add(m[1] + "=" + args.get(i + 1));
        }
        return tags.isEmpty() ? null : "setparams=" + String.join(":", tags);
    }

    static List<String> encodeArgs(ExportFormat format, ExportFormat.Chroma chroma, ExportFormat.Depth depth,
                                   int w, int h, int fps, boolean allIntra) {
        List<String> command = new ArrayList<>(format.settings(chroma, depth));
        // One filter chain: ffmpeg keeps only the last -vf, so a format's own filter and the size
        // fit must not each bring one.
        List<String> filters = new ArrayList<>();
        int[] fit = format.fit(w, h); // a frame hardware decoders would refuse, brought down to one they take
        if (fit != null)
            filters.add("scale=" + fit[0] + ":" + fit[1] + ":flags=lanczos");
        int vf = command.indexOf("-vf");
        if (vf >= 0) {
            filters.add(command.get(vf + 1));
            command.subList(vf, vf + 2).clear();
        }
        // The colour tags again, on the frames. ffmpeg 9 writes the MP4 colr box from the frames,
        // and raw input frames carry none, so every export said "unspecified" primaries and
        // transfer to the players that read that box first (QuickTime and the Apple frameworks
        // under Keynote and PowerPoint): an HLG file not known to be HLG, and SDR left to a
        // guessed gamma. The -color_* options above still set the bitstream's own tags.
        String tags = colourTags(command);
        if (tags != null)
            filters.add(tags);
        if (!filters.isEmpty())
            command.addAll(List.of("-vf", String.join(",", filters)));
        command.addAll(format.isSeries() ? formatImage : formatVideo);
        String pixFmt = format.pixFmt(chroma, depth);
        if (pixFmt != null) {
            command.add("-pix_fmt");
            command.add(pixFmt);
        }
        if (!format.isSeries()) {
            // All-intra means every frame is a keyframe, with no inter-frame prediction at all.
            //
            // It is a scientific-imagery choice, not a compression one, and costs roughly 3-10x
            // the file size. Inter-frame coding spends its bits on what CHANGED, so on faint
            // low-contrast structure over a noisy background it does two things this footage
            // cannot tolerate: it smooths away the features the enhancement exists to reveal,
            // and its motion compensation can carry blocks along a predicted path, which reads
            // as apparent motion that is not in the data. Frame-exact scrubbing in both
            // directions falls out for free.
            //
            // Note this makes frames INDEPENDENT, not lossless: H.264/265 still quantise within
            // each frame. For frame fidelity use the PNG series.
            //
            // Off, it keeps the old ~2 keyframes/sec (scene-cut detection disabled) so reverse
            // scrubbing stays smooth without the default GOP of ~250.
            int gop = allIntra ? 1 : Math.max(1, fps / 2);
            command.add("-g");
            command.add(String.valueOf(gop));
            command.add("-keyint_min");
            command.add(String.valueOf(gop));
            command.add("-sc_threshold");
            command.add("0");
        }
        return command;
    }

    /** ffmpeg's comment for a movie; nothing for a frame series, whose files are stamped one by one. */
    static List<String> metadataArgs(ExportFormat format, @Nullable String comment) {
        return format.isSeries() || comment == null ? List.of() : List.of("-metadata", "comment=" + comment);
    }

    /**
     * The mp4 muxer writes only a fixed set of tags, so a custom one (hfrecord) needs
     * use_metadata_tags, added to the one -movflags a format already has (ffmpeg keeps only the
     * last -movflags). Matroska keeps any tag and has no -movflags to change.
     */
    static List<String> withMetadataTags(List<String> encodeArgs) {
        List<String> out = new ArrayList<>(encodeArgs);
        int i = out.indexOf("-movflags");
        if (i >= 0 && i + 1 < out.size() && !out.get(i + 1).contains("use_metadata_tags"))
            out.set(i + 1, out.get(i + 1) + "+use_metadata_tags");
        return out;
    }

    /**
     * The movie's tags as an ffmetadata file, read by ffmpeg as a second input: comment (the
     * session, as metadataArgs puts it), description (the suite credit line) and hfrecord. A file
     * and not -metadata, for two reasons measured here: one command-line argument is capped by the
     * system (the bundled ffmpeg on Linux took a 131000-byte value and refused 131072 bytes with
     * "Argument list too long", while a 16 MiB tag went through a file and read back whole), and an
     * argument is converted to the platform's encoding (sun.jnu.encoding), so under a POSIX locale
     * the session's "Å" reached the file as "?". The file is UTF-8 whatever the locale.
     */
    private File movieMetadata(JSONObject record) throws IOException {
        File meta = File.createTempFile("meta", ".txt", Directories.exportCacheDir);
        String comment = provenance == null ? null : Provenance.metadataComment(provenance);
        boolean hasScene = false;
        if (comment != null)
            try {
                hasScene = new JSONObject(comment).has("scene");
            } catch (org.json.JSONException e) {
                hasScene = false; // the comment is always JSON; if not, keep the session in the record
            }
        JSONObject suite = record.optJSONObject("provenance");
        String credit = suite == null ? null : suite.optString("credit", null);
        java.nio.file.Files.writeString(meta.toPath(), ffmetadata(comment, credit, ProcessingRecord.movieText(record, hasScene)),
                java.nio.charset.StandardCharsets.UTF_8);
        return meta;
    }

    /** An ffmetadata file with the tags that are given: comment, description, hfrecord. */
    static String ffmetadata(@Nullable String comment, @Nullable String description, @Nullable String hfrecord) {
        StringBuilder sb = new StringBuilder(";FFMETADATA1\n");
        String[][] tags = {{"comment", comment}, {"description", description}, {ProcessingRecord.EMBED_KEY, hfrecord}};
        for (String[] t : tags)
            if (t[1] != null && !t[1].isEmpty())
                sb.append(t[0]).append('=').append(ffmetadataEscape(t[1])).append('\n');
        return sb.toString();
    }

    /** ffmetadata's escaping: '=', ';', '#', backslash and newline each take a backslash. */
    static String ffmetadataEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c == ';' || c == '#' || c == '\\' || c == '\n')
                sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    // ffmpeg has written the frames; each gets the session as a tEXt chunk before its IEND, then
    // the record's embedded copy (hfrecord) and the suite provenance chunks. A frame that cannot
    // take them is logged and left as it is: the export itself has succeeded.
    private void stampPngSeries(@Nullable JSONObject record) {
        if (provenance == null)
            return;
        File[] frames = new File(prefix).listFiles((dir, name) -> name.startsWith("frame") && name.endsWith(".png"));
        if (frames == null)
            return;
        String embedded = record == null ? null : ProcessingRecord.pngText(record);
        for (File frame : frames) {
            try {
                Provenance.writePngChunk(frame.toPath(), provenance);
                if (record != null)
                    stampRecord(frame.toPath(), record, embedded);
            } catch (IOException e) {
                Log.warn("Provenance not written into " + frame.getName(), e);
            }
        }
    }

    /** hfrecord, then the suite provenance chunks (heliosoftware/spec/provenance.md): RHEF and Disclaimer only with a stamp. */
    static void stampRecord(Path png, JSONObject record, String embedded) throws IOException {
        Provenance.writePngText(png, ProcessingRecord.EMBED_KEY, embedded);
        Provenance.writePngText(png, ProcessingRecord.SUITE_PNG_KEYWORD, ProcessingRecord.suiteText(record));
        JSONObject suite = record.optJSONObject("provenance");
        String stamp = suite == null || suite.isNull("rhef") ? null : suite.optString("rhef", null);
        if (stamp != null && !stamp.isEmpty()) {
            Provenance.writePngText(png, "RHEF", stamp);
            Provenance.writePngText(png, "Disclaimer", ProcessingRecord.SUITE_DISCLAIMER);
        }
    }

    private void runFFmpeg(List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder()
                .directory(Directories.exportCacheDir)
                .redirectError(File.createTempFile("fferr", null, Directories.exportCacheDir))
                .redirectOutput(File.createTempFile("ffout", null, Directories.exportCacheDir))
                .command(command);

        Process process = builder.start();
        try {
            if (!process.waitFor(FFMPEG_TIMEOUT_MINUTES, TimeUnit.MINUTES))
                throw new Exception("FFmpeg timed out after " + FFMPEG_TIMEOUT_MINUTES + " minutes");
            int exitCode = process.exitValue();
            if (exitCode != 0)
                throw new Exception("FFmpeg exit code " + exitCode);
        } finally {
            process.destroyForcibly();
        }
    }

    private void deleteOutputs() throws Exception {
        DirectoryStream.Filter<Path> filter = p -> p.toString().startsWith(prefix);
        FileUtils.deleteFromDir(Path.of(Directories.EXPORTS.getPath()), filter);
    }

}
