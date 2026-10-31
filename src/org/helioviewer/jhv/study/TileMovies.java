package org.helioviewer.jhv.study;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.helioviewer.jhv.io.Directories;

/** An N-up movie of a run's per-variant movies, side by side, with the bundled ffmpeg. */
public final class TileMovies {

    private TileMovies() {
    }

    /** The ffmpeg arguments after the executable: inputs scaled to one tile size, stacked, shortest input wins. */
    static List<String> args(List<Path> in, Path out, int tilePx) {
        int n = in.size();
        int cols = (int) Math.ceil(Math.sqrt(n));
        List<String> a = new ArrayList<>(List.of("-y"));
        StringBuilder f = new StringBuilder();
        StringBuilder labels = new StringBuilder();
        StringBuilder layout = new StringBuilder();
        for (int i = 0; i < n; i++) {
            a.add("-i");
            a.add(in.get(i).toString());
            f.append('[').append(i).append(":v]scale=").append(tilePx).append(':').append(tilePx)
                    .append(":force_original_aspect_ratio=decrease,pad=").append(tilePx).append(':').append(tilePx).append(":(ow-iw)/2:(oh-ih)/2[v").append(i).append("];");
            labels.append("[v").append(i).append(']');
            layout.append(i == 0 ? "" : "|").append((i % cols) * tilePx).append('_').append((i / cols) * tilePx);
        }
        f.append(labels).append("xstack=inputs=").append(n).append(":layout=").append(layout).append(":shortest=1[out]");
        a.addAll(List.of("-filter_complex", f.toString(), "-map", "[out]", "-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p", out.toString()));
        return a;
    }

    /** @throws IOException when ffmpeg fails; fewer than two movies is no tile and is refused */
    public static void build(List<Path> in, Path out, int tilePx) throws IOException {
        if (in.size() < 2)
            throw new IOException("A tiled movie needs at least two movies.");
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(Directories.libCacheDir, "ffmpeg").toString());
        cmd.addAll(args(in, out, tilePx));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!p.waitFor(600, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg did not finish the tiled movie in ten minutes.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("Interrupted while tiling.", e);
        }
        if (p.exitValue() != 0)
            throw new IOException("ffmpeg exited with " + p.exitValue() + " while tiling.");
    }
}
