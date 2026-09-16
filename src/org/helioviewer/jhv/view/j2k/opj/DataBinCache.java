package org.helioviewer.jhv.view.j2k.opj;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * The JPIP data bins the server has sent us, held in Java.
 *
 * <p>This replaces Kakadu's cache. A JPIP server does not send files: it sends numbered pieces of
 * numbered bins, in whatever order suits the view window, and a client accumulates them until it
 * has enough to decode. Kakadu did that accumulation and the rebuilding of a codestream from it;
 * this class does the first half, and the codestream rebuild does the second.
 *
 * <p>Pieces can arrive out of order and can repeat, so each bin keeps its pieces by offset and
 * hands back only the run that starts at zero with no hole in it. That is the honest answer to
 * "what can be decoded now": a decoder given bytes past a hole reads rubbish and says nothing.
 *
 * <p>Thread safety: the reader thread fills this while the decoder thread reads it, so the bin map
 * is concurrent and each bin is guarded by its own monitor. Bins are small and rarely contended.
 */
public final class DataBinCache {

    /** Class, codestream and in-class identifier, which together name one bin. */
    public record BinId(int klass, long codestream, long id) implements Comparable<BinId> {
        private static final Comparator<BinId> ORDER =
                Comparator.comparingLong(BinId::codestream).thenComparingInt(BinId::klass).thenComparingLong(BinId::id);

        @Override
        public int compareTo(BinId other) {
            return ORDER.compare(this, other);
        }
    }

    private static final class Bin {
        private final NavigableMap<Long, byte[]> pieces = new TreeMap<>();
        private long contiguous; // bytes from zero with no hole
        private long end;        // the far end of the furthest piece, hole or no hole
        private boolean complete;
        private long held;       // what the pieces occupy, for accounting

        synchronized void put(long offset, byte[] data, boolean isFinal) {
            if (data.length > 0) {
                byte[] previous = pieces.put(offset, data);
                held += data.length - (previous == null ? 0 : previous.length);
                end = Math.max(end, offset + data.length);
                long run = contiguous;
                for (Map.Entry<Long, byte[]> piece : pieces.tailMap(run, true).entrySet()) {
                    if (piece.getKey() > run)
                        break; // a hole: everything past it stays out of reach
                    run = Math.max(run, piece.getKey() + piece.getValue().length);
                }
                contiguous = run;
            }
            if (isFinal)
                complete = true;
        }

        synchronized long contiguous() {
            return contiguous;
        }

        synchronized boolean complete() {
            // The server said this is the last byte, and nothing is missing before it.
            return complete && contiguous == end;
        }

        synchronized long held() {
            return held;
        }

        @Nullable
        synchronized byte[] bytes() {
            int length = (int) Math.min(contiguous, Integer.MAX_VALUE);
            if (length == 0)
                return null;

            byte[] out = new byte[length];
            for (Map.Entry<Long, byte[]> piece : pieces.headMap(contiguous, false).entrySet()) {
                long offset = piece.getKey();
                byte[] data = piece.getValue();
                int count = (int) Math.min(data.length, length - offset);
                if (count > 0)
                    System.arraycopy(data, 0, out, (int) offset, count);
            }
            return out;
        }
    }

    private final ConcurrentHashMap<BinId, Bin> bins = new ConcurrentHashMap<>();

    /** Add one message's worth of bytes to a bin. Repeats and out-of-order pieces are expected. */
    public void put(int klass, long codestream, long id, long offset, byte[] data, boolean isFinal) {
        bins.computeIfAbsent(new BinId(klass, codestream, id), key -> new Bin()).put(offset, data, isFinal);
    }

    /** The bytes of a bin from its start up to the first hole, or null when nothing usable is here. */
    @Nullable
    public byte[] bytes(int klass, long codestream, long id) {
        Bin bin = bins.get(new BinId(klass, codestream, id));
        return bin == null ? null : bin.bytes();
    }

    /** How many bytes are available from the start of the bin without a hole. */
    public long available(int klass, long codestream, long id) {
        Bin bin = bins.get(new BinId(klass, codestream, id));
        return bin == null ? 0 : bin.contiguous();
    }

    /** Whether the server said this bin is finished and nothing is missing before its end. */
    public boolean isComplete(int klass, long codestream, long id) {
        Bin bin = bins.get(new BinId(klass, codestream, id));
        return bin != null && bin.complete();
    }

    /** The bins of one class held for one codestream, in identifier order, for the rebuild to walk. */
    public List<Long> ids(int klass, long codestream) {
        List<Long> out = new ArrayList<>();
        bins.forEach((id, bin) -> {
            if (id.klass() == klass && id.codestream() == codestream)
                out.add(id.id());
        });
        out.sort(null);
        return out;
    }

    /** Everything held, in a stable order, for writing the cache out. */
    public List<BinId> contents() {
        List<BinId> out = new ArrayList<>(bins.keySet());
        out.sort(null);
        return out;
    }

    public void clear(long codestream) {
        bins.keySet().removeIf(id -> id.codestream() == codestream);
    }

    public void clear() {
        bins.clear();
    }

    /** Roughly what this is holding, for the cache budget. Payload only: the map overhead is not counted. */
    public long bytesHeld() {
        long total = 0;
        for (Bin bin : bins.values())
            total += bin.held();
        return total;
    }

}
