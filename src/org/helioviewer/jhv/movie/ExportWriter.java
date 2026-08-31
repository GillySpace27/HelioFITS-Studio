package org.helioviewer.jhv.movie;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.helioviewer.jhv.image.nio.MappedImageFactory;
import org.helioviewer.jhv.image.nio.NativeImageFactory;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.time.TimeUtils;

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
    private int bytesPerPixel = 3; // 3 = rgb24, 6 = rgb48le; set by the first encoded frame

    private File tempFile;

    ExportWriter(ExportFormat _format, ExportFormat.Chroma _chroma, ExportFormat.Depth _depth,
                 int _w, int _h, int _fps, boolean _allIntra) {
        // Name the export after the current session so a recording is self-identifying.
        String session = org.helioviewer.jhv.app.Session.displayName();
        String base = "Untitled".equals(session) ? "JHV" : session.replaceAll("[^A-Za-z0-9._-]", "_");
        prefix = Directories.EXPORTS.getPath() + base + "_" + TimeUtils.formatFilename(System.currentTimeMillis());
        format = _format;
        chroma = _format.clamp(_chroma);
        depth = _format.clamp(_chroma, _depth);
        w = _w;
        h = _h;
        fps = _fps;
        allIntra = _allIntra;
    }

    void encode(BufferedImage mainImage, BufferedImage eveImage, int movieLinePosition, int _bytesPerPixel) throws Exception {
        bytesPerPixel = _bytesPerPixel;
        if (tempFile == null) {
            tempFile = File.createTempFile("dump", null, Directories.exportCacheDir);
            tempFile.deleteOnExit();
        }

        int mainH = mainImage.getHeight();
        BufferedImage scaled = null;
        ByteBuffer eveData = null;
        if (eveImage != null) {
            scaled = ExportUtils.scaleImage(eveImage, w, h - mainH, movieLinePosition);
            eveData = NativeImageFactory.getByteBuffer(scaled).clear().limit(3 * w * scaled.getHeight());
        }

        ByteBuffer mainData = MappedImageFactory.getByteBuffer(mainImage).clear().limit(bytesPerPixel * w * mainH);
        try (FileChannel channel = FileChannel.open(tempFile.toPath(), StandardOpenOption.APPEND)) {
            for (int j = mainH - 1; j >= 0; j--) { // write image flipped
                int pos = bytesPerPixel * w * j;
                mainData.position(pos);
                mainData.limit(pos + bytesPerPixel * w);
                writeFully(channel, mainData);
            }
            if (eveData != null)
                // The EVE strip is composited at 8 bits whatever the main frame is, and the two
                // are concatenated into one raw frame: leaving it narrow would both garble the
                // strip and make the frame the wrong length for ffmpeg, which is counting bytes.
                writeFully(channel, bytesPerPixel == 6 ? widenTo16(eveData) : eveData);
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
        if (tempFile == null) // unlikely reach here on encode error
            return null;

        try {
            // A frame-per-file format gets its own directory. Numbering them into Exports/
            // alongside everything else turned one recording into hundreds of loose files
            // interleaved with every other export.
            String outPath;
            if (format.isSeries()) {
                File dir = new File(prefix);
                if (!dir.isDirectory() && !dir.mkdirs())
                    throw new Exception("Could not create the frame directory: " + prefix);
                outPath = new File(dir, "frame" + format.extension).getPath();
            } else {
                outPath = prefix + format.extension;
            }
            runFFmpeg(buildCommand(outPath));
            return outPath;
        } catch (Exception e) {
            deleteOutputs();
            throw e;
        } finally {
            tempFile.delete();
            tempFile = null;
        }
    }

    private List<String> buildCommand(String outPath) {
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
        command.addAll(format.settings(chroma, depth));
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
        command.add("-y");
        command.add(outPath);
        return command;
    }

    private void runFFmpeg(List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder()
                .directory(Directories.exportCacheDir)
                .redirectError(File.createTempFile("fferr", null, Directories.exportCacheDir))
                .redirectOutput(File.createTempFile("ffout", null, Directories.exportCacheDir))
                .command(command);

        Process process = builder.start();
        boolean finished = process.waitFor(FFMPEG_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("FFmpeg timed out after " + FFMPEG_TIMEOUT_MINUTES + " minutes");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0)
            throw new Exception("FFmpeg exit code " + exitCode);
    }

    private void deleteOutputs() throws Exception {
        DirectoryStream.Filter<Path> filter = p -> p.toString().startsWith(prefix);
        FileUtils.deleteFromDir(Path.of(Directories.EXPORTS.getPath()), filter);
    }

}
