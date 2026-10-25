package org.helioviewer.jhv.view.j2k.opj;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

        @Override
        public int hashCode() {
            return Key.mix(klass, codestream * 0x9E3779B97F4A7C15L + id);
        }
    }

    /** A bin's name inside one codestream's map. The default record hash collides on these small numbers. */
    private record Key(int klass, long id) {
        static int mix(int klass, long id) {
            long h = (id ^ ((long) klass << 56)) * 0x9E3779B97F4A7C15L; // the class sits above any id
            return (int) (h ^ (h >>> 32));
        }

        @Override
        public int hashCode() {
            return mix(klass, id);
        }
    }

    /**
     * What the heap really costs per bin besides its bytes: the Bin, its array header, the Key and
     * the map node. Measured as about 130 bytes in a live heap histogram; counted so the budget is
     * not blind to it.
     */
    static final int OVERHEAD_PER_BIN = 128;

    private static final class Bin {
        private byte[] run = new byte[0]; // bytes from zero with no hole, exactly sized
        private NavigableMap<Long, byte[]> aside; // pieces past a hole, rare; null until needed
        private long asideBytes;
        private long end;        // the far end of the furthest piece, hole or no hole
        private boolean complete;

        /** @return how many payload bytes this call added (negative when it replaced more than it added) */
        synchronized long put(long offset, byte[] data, boolean isFinal) {
            long before = run.length + asideBytes;
            if (data.length > 0) {
                end = Math.max(end, offset + data.length);
                if (offset <= run.length) {
                    extend(offset, data);
                    if (aside != null) { // a piece may now join the run
                        var iterator = aside.entrySet().iterator();
                        while (iterator.hasNext()) {
                            Map.Entry<Long, byte[]> piece = iterator.next();
                            if (piece.getKey() > run.length)
                                break;
                            iterator.remove();
                            asideBytes -= piece.getValue().length;
                            extend(piece.getKey(), piece.getValue());
                        }
                    }
                } else {
                    if (aside == null)
                        aside = new TreeMap<>();
                    byte[] previous = aside.put(offset, data);
                    asideBytes += data.length - (previous == null ? 0 : previous.length);
                }
            }
            if (isFinal)
                complete = true;
            return run.length + asideBytes - before;
        }

        // ponytail: exact-size copy per extension; bins are small and extended a few times, so this
        // beats a spare-capacity scheme that would hold slack in 18 million arrays.
        private void extend(long offset, byte[] data) {
            int to = (int) (offset + data.length);
            if (to > run.length)
                run = Arrays.copyOf(run, to);
            System.arraycopy(data, 0, run, (int) offset, data.length);
        }

        synchronized long contiguous() {
            return run.length;
        }

        synchronized long asideHeld() {
            return asideBytes;
        }

        synchronized boolean complete() {
            // The server said this is the last byte, and nothing is missing before it.
            return complete && run.length == end;
        }

        @Nullable
        synchronized byte[] bytes() {
            return run.length == 0 ? null : run.clone();
        }
    }

    /** One codestream's bins, and what they hold, kept so one frame can be counted or dropped without a scan of the rest. */
    private static final class Frame {
        final ConcurrentHashMap<Key, Bin> bins = new ConcurrentHashMap<>();
        final AtomicLong payload = new AtomicLong();

        long held() {
            return payload.get() + (long) OVERHEAD_PER_BIN * bins.size();
        }
    }

    private final ConcurrentHashMap<Long, Frame> frames = new ConcurrentHashMap<>();

    /** Add one message's worth of bytes to a bin. Repeats and out-of-order pieces are expected. */
    public void put(int klass, long codestream, long id, long offset, byte[] data, boolean isFinal) {
        Frame frame = frames.computeIfAbsent(codestream, key -> new Frame());
        Bin bin = frame.bins.computeIfAbsent(new Key(klass, id), key -> new Bin());
        frame.payload.addAndGet(bin.put(offset, data, isFinal));
    }

    @Nullable
    private Bin bin(int klass, long codestream, long id) {
        Frame frame = frames.get(codestream);
        return frame == null ? null : frame.bins.get(new Key(klass, id));
    }

    /** The bytes of a bin from its start up to the first hole, or null when nothing usable is here. */
    @Nullable
    public byte[] bytes(int klass, long codestream, long id) {
        Bin bin = bin(klass, codestream, id);
        return bin == null ? null : bin.bytes();
    }

    /** How many bytes are available from the start of the bin without a hole. */
    public long available(int klass, long codestream, long id) {
        Bin bin = bin(klass, codestream, id);
        return bin == null ? 0 : bin.contiguous();
    }

    /** Whether the server said this bin is finished and nothing is missing before its end. */
    public boolean isComplete(int klass, long codestream, long id) {
        Bin bin = bin(klass, codestream, id);
        return bin != null && bin.complete();
    }

    /** The bins of one class held for one codestream, in identifier order, for the rebuild to walk. */
    public List<Long> ids(int klass, long codestream) {
        List<Long> out = new ArrayList<>();
        Frame frame = frames.get(codestream);
        if (frame != null)
            frame.bins.forEach((key, bin) -> {
                if (key.klass() == klass)
                    out.add(key.id());
            });
        out.sort(null);
        return out;
    }

    /** Everything held, in a stable order, for writing the cache out. */
    public List<BinId> contents() {
        List<BinId> out = new ArrayList<>();
        frames.forEach((codestream, frame) -> frame.bins.forEach((key, bin) -> out.add(new BinId(key.klass(), codestream, key.id()))));
        out.sort(null);
        return out;
    }

    /** What one codestream holds, in a stable order: the disk cache writes a frame at a time. */
    public List<BinId> contents(long codestream) {
        List<BinId> out = new ArrayList<>();
        Frame frame = frames.get(codestream);
        if (frame != null)
            frame.bins.forEach((key, bin) -> out.add(new BinId(key.klass(), codestream, key.id())));
        out.sort(null);
        return out;
    }

    public void clear(long codestream) {
        frames.remove(codestream);
    }

    public void clear() {
        frames.clear();
    }

    /**
     * Drop one codestream's bins except one class, and say how many bytes that freed.
     *
     * <p>The class kept is the metadata: it describes the file, arrives under whichever frame was
     * asked for first, and is not re-sent with a frame.
     */
    public long evict(long codestream, int keepKlass) {
        Frame frame = frames.get(codestream);
        if (frame == null)
            return 0;
        long before = frame.held();
        frame.bins.entrySet().removeIf(entry -> entry.getKey().klass() != keepKlass);
        long kept = 0; // recounted, not subtracted: a piece may land while this runs, and a recount heals that
        for (Bin bin : frame.bins.values())
            kept += bin.contiguous() + bin.asideHeld();
        frame.payload.set(kept);
        return before - frame.held();
    }

    /** The codestreams held and what each holds, for the budget to pick from. */
    public Map<Long, Long> frameBytes() {
        Map<Long, Long> out = new HashMap<>();
        frames.forEach((codestream, frame) -> out.put(codestream, frame.held()));
        return out;
    }

    /** What this is holding, for the cache budget: payload plus the per-bin cost of the objects around it. */
    public long bytesHeld() {
        long total = 0;
        for (Frame frame : frames.values())
            total += frame.held();
        return total;
    }

}
