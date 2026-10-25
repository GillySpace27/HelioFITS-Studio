package org.helioviewer.jhv.view.j2k.jpip;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.helioviewer.jhv.app.MemoryBudget;

/**
 * One byte budget for the JPIP data bins of every open movie.
 *
 * <p>Bins used to be kept forever: a 1291-frame layer held 27 GB of them in a 30 GB heap and the
 * collector spent most of its time compacting. Now, when the total passes the limit, the frames
 * farthest from each movie's playhead are dropped, but only those the disk cache holds a copy of,
 * because a dropped frame comes back from there (J2KReader.restoreFrame). A frame nobody saved
 * stays.
 *
 * <p>ponytail: distance is plain frame number distance, so a looping movie can drop the frames
 * just before its wrap. Add circular distance if that shows up.
 */
public final class JPIPBinBudget {

    /** Frames this close to the playhead are never dropped: they are being decoded or about to be. */
    static final int PROTECTED = 2;
    /** Drop down to this fraction of the limit, so a trim is not needed again after every frame. */
    private static final double TARGET = 0.9;

    private static final Set<JPIPCache> caches = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final ReentrantLock lock = new ReentrantLock();

    private JPIPBinBudget() {}

    public static void register(JPIPCache cache) {
        caches.add(cache);
    }

    /** Called after a frame lands. Cheap when under the limit. */
    public static void trim() {
        if (!lock.tryLock())
            return; // another reader thread is already trimming
        try {
            trim(snapshot(), MemoryBudget.binBytes());
        } finally {
            lock.unlock();
        }
    }

    /** Whether loading one more frame is worth it: false once the budget is full and the frame is not near the playhead. */
    public static boolean worthLoading(JPIPCache cache, int frame) {
        return Math.abs(frame - cache.playhead()) <= PROTECTED || total(snapshot()) < MemoryBudget.binBytes();
    }

    private static List<JPIPCache> snapshot() {
        synchronized (caches) {
            return new ArrayList<>(caches);
        }
    }

    private static long total(Collection<JPIPCache> all) {
        long total = 0;
        for (JPIPCache cache : all)
            total += cache.bins().bytesHeld();
        return total;
    }

    /** The trimming itself, with the limit given so it can be checked. @return bytes freed */
    static long trim(Collection<JPIPCache> all, long limit) {
        long total = total(all);
        if (total <= limit)
            return 0;

        record Candidate(JPIPCache cache, int frame, int distance) {}
        List<Candidate> candidates = new ArrayList<>();
        for (JPIPCache cache : all)
            for (Map.Entry<Long, Long> held : cache.bins().frameBytes().entrySet()) {
                int frame = held.getKey().intValue();
                int distance = Math.abs(frame - cache.playhead());
                if (distance > PROTECTED && cache.isRestorable(frame))
                    candidates.add(new Candidate(cache, frame, distance));
            }
        candidates.sort(Comparator.comparingInt(Candidate::distance).reversed());

        long freed = 0, target = (long) (limit * TARGET);
        for (Candidate candidate : candidates) {
            if (total - freed <= target)
                break;
            freed += candidate.cache().evictFrame(candidate.frame());
        }
        return freed;
    }

}
