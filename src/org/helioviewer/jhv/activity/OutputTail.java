package org.helioviewer.jhv.activity;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Follows a file a child process is writing its output to and puts each finished line into the
 * activity log, so the Activity window shows HelioFITS Core's terminal output while it runs.
 * Not thread safe: one caller polls it.
 */
public final class OutputTail {

    private static final int MAX_READ = 64 * 1024; // one poll; the rest is read on the next
    private final Path file;
    private final String source;
    private long offset;
    private final StringBuilder partial = new StringBuilder();

    public OutputTail(Path _file, String _source) {
        file = _file;
        source = _source;
    }

    /** Logs the lines finished since the last call; {@code end} also logs an unfinished last line. */
    public void drain(boolean end) {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            long length = f.length();
            if (length > offset) {
                f.seek(offset);
                byte[] bytes = new byte[(int) Math.min(length - offset, MAX_READ)];
                f.readFully(bytes);
                offset += bytes.length;
                partial.append(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return; // gone or not yet there: nothing to show
        }
        int nl;
        while ((nl = partial.indexOf("\n")) >= 0) {
            String line = partial.substring(0, nl).stripTrailing();
            partial.delete(0, nl + 1);
            if (!line.isEmpty())
                Activity.event(Activity.Kind.CORE, source, line);
        }
        if (end && !partial.isEmpty()) {
            Activity.event(Activity.Kind.CORE, source, partial.toString().strip());
            partial.setLength(0);
        }
    }

}
