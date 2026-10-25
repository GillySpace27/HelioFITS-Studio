package org.helioviewer.jhv.view.j2k.jpip;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.helioviewer.jhv.view.j2k.opj.DataBinCache;

/**
 * The JPIP bin budget drops the frames farthest from the playhead, only ones the disk cache holds,
 * never the metadata, and tells the source so it can restore them. Also pins the cache's own
 * accounting after the per-frame rework: pieces merge into one array, a frame can be dropped alone,
 * and the key hash spreads the small numbers bins are named by.
 */
public final class JPIPBinBudgetCheck {

    private static final int PRECINCT = 0, META = Constants.Bin.META_DATABIN;
    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static void fill(JPIPCache cache, int frame, int bins, int size) {
        for (int i = 0; i < bins; i++)
            cache.bins().put(PRECINCT, frame, i, 0, new byte[size], true);
    }

    public static void main(String[] args) {
        // Pieces of one bin merge, whatever order they arrive in, and the count follows.
        DataBinCache bins = new DataBinCache();
        bins.put(PRECINCT, 0, 1, 4, new byte[]{4, 5}, false);
        bins.put(PRECINCT, 0, 1, 0, new byte[]{0, 1, 2, 3}, false);
        bins.put(PRECINCT, 0, 1, 6, new byte[]{6}, true);
        expect("out of order pieces read back as one run", java.util.Arrays.equals(new byte[]{0, 1, 2, 3, 4, 5, 6}, bins.bytes(PRECINCT, 0, 1)));
        expect("and the bin is complete", bins.isComplete(PRECINCT, 0, 1));
        expect("payload plus per-bin overhead is what is held", bins.bytesHeld() == 7 + 128);

        // Dropping a frame frees its bins, keeps the metadata, and leaves other frames alone.
        bins.put(META, 0, 0, 0, new byte[10], true);
        bins.put(PRECINCT, 1, 1, 0, new byte[3], true);
        long freed = bins.evict(0, META);
        expect("evict frees the frame's bins", bins.bytes(PRECINCT, 0, 1) == null && freed == 7 + 128);
        expect("but not its metadata", bins.bytes(META, 0, 0) != null);
        expect("nor another frame", bins.bytes(PRECINCT, 1, 1) != null);
        expect("and the count matches what is left", bins.bytesHeld() == (10 + 128) + (3 + 128));

        // The key hash must not collapse small numbers into a few buckets (it did: 18M tree nodes).
        Set<Integer> hashes = new HashSet<>();
        for (int klass = 0; klass < 3; klass++)
            for (long id = 0; id < 1000; id++)
                hashes.add(new DataBinCache.BinId(klass, 5, id).hashCode());
        expect("3000 bin ids give nearly 3000 hashes", hashes.size() > 2900);

        // The budget: eight frames of about 1 KB each; frame 7 was never saved to disk.
        JPIPCache cache = new JPIPCache();
        Set<Integer> told = new HashSet<>();
        cache.onEvicted(told::add);
        for (int frame = 0; frame < 8; frame++) {
            fill(cache, frame, 1, 1000 - 128);
            if (frame != 7)
                cache.markRestorable(frame);
        }
        cache.bins().put(META, 0, 0, 0, new byte[100], true);
        cache.setPlayhead(0);
        long each = cache.bins().frameBytes().get(1L);
        List<JPIPCache> all = new ArrayList<>(List.of(cache));
        expect("under the limit nothing is dropped", JPIPBinBudget.trim(all, each * 20) == 0 && told.isEmpty());

        // Far frames go first; frames within 2 of the playhead and unsaved ones stay.
        cache.setPlayhead(1);
        JPIPBinBudget.trim(all, each * 15 / 2); // 8 frames and the metadata: two must go to reach 90% of 7.5
        expect("the saved frames farthest from the playhead were dropped and announced", told.equals(Set.of(5, 6)));
        expect("an unsaved frame is never dropped", cache.bins().bytes(PRECINCT, 7, 0) != null);
        expect("frames near the playhead stay", cache.bins().bytes(PRECINCT, 0, 0) != null
                && cache.bins().bytes(PRECINCT, 3, 0) != null && cache.bins().bytes(PRECINCT, 4, 0) != null);
        expect("the metadata stays", cache.bins().bytes(META, 0, 0) != null);
        expect("a dropped frame is no longer restorable until it is read again", !cache.isRestorable(6));

        System.out.println(failures == 0 ? "JPIPBinBudgetCheck: PASS" : "JPIPBinBudgetCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private JPIPBinBudgetCheck() {}

}
