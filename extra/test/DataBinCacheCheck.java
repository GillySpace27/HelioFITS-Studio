package org.helioviewer.jhv.view.j2k.opj;

import java.util.Arrays;
import java.util.List;

/**
 * The data bin cache hands back only what a decoder can actually use.
 *
 * <p>A JPIP server sends numbered pieces of numbered bins in whatever order suits the view window,
 * and it repeats pieces the client may already hold. What matters is the answer to "what is usable
 * now": the run from the start of the bin with no hole in it. Handing back bytes from past a hole
 * would feed a decoder rubbish it cannot detect, which is the failure this pins.
 *
 * <p>Run: java -cp "bin:extra/test-classes" org.helioviewer.jhv.view.j2k.opj.DataBinCacheCheck
 */
public final class DataBinCacheCheck {

    private static final int PRECINCT = 0, MAIN_HEADER = 3;

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static byte[] run(int from, int count) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++)
            out[i] = (byte) (from + i);
        return out;
    }

    public static void main(String[] args) {
        DataBinCache cache = new DataBinCache();

        // In order, in two pieces, then declared finished.
        cache.put(MAIN_HEADER, 0, 0, 0, run(0, 4), false);
        cache.put(MAIN_HEADER, 0, 0, 4, run(4, 4), true);
        expect("two pieces in order read back as one run", Arrays.equals(run(0, 8), cache.bytes(MAIN_HEADER, 0, 0)));
        expect("and the bin counts as complete", cache.isComplete(MAIN_HEADER, 0, 0));

        // A piece that arrives with a gap before it is held but stays out of reach.
        cache.put(PRECINCT, 0, 7, 8, run(80, 4), false);
        expect("a piece past a hole yields nothing yet", cache.bytes(PRECINCT, 0, 7) == null);
        expect("and nothing is available", cache.available(PRECINCT, 0, 7) == 0);

        cache.put(PRECINCT, 0, 7, 0, run(0, 8), false);
        expect("filling the hole makes the whole run readable",
                Arrays.equals(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 80, 81, 82, 83}, cache.bytes(PRECINCT, 0, 7)));
        expect("which is twelve bytes", cache.available(PRECINCT, 0, 7) == 12);

        // The server marked the end, but a piece before it is still missing: not usable as complete.
        cache.put(PRECINCT, 0, 9, 4, run(40, 4), true);
        expect("an end marker with a hole before it is not complete", !cache.isComplete(PRECINCT, 0, 9));
        cache.put(PRECINCT, 0, 9, 0, run(0, 4), false);
        expect("and becomes complete once the hole is filled", cache.isComplete(PRECINCT, 0, 9));

        // A repeated piece must not double count or corrupt the run.
        long before = cache.bytesHeld();
        cache.put(PRECINCT, 0, 9, 0, run(0, 4), false);
        expect("a repeated piece changes nothing", cache.bytesHeld() == before
                && Arrays.equals(new byte[]{0, 1, 2, 3, 40, 41, 42, 43}, cache.bytes(PRECINCT, 0, 9)));

        // Bins of one class for one codestream, in order, which is how the rebuild walks them.
        cache.put(PRECINCT, 0, 3, 0, run(0, 2), true);
        expect("precinct bins list in identifier order", List.of(3L, 7L, 9L).equals(cache.ids(PRECINCT, 0)));
        expect("and a different class is a different list", List.of(0L).equals(cache.ids(MAIN_HEADER, 0)));

        // Codestreams are independent: a second frame's bins do not disturb the first.
        cache.put(PRECINCT, 1, 3, 0, run(9, 2), true);
        expect("the same bin number in another codestream is another bin",
                Arrays.equals(run(9, 2), cache.bytes(PRECINCT, 1, 3)) && Arrays.equals(run(0, 2), cache.bytes(PRECINCT, 0, 3)));

        cache.clear(1);
        expect("clearing one codestream leaves the other", cache.bytes(PRECINCT, 1, 3) == null
                && Arrays.equals(run(0, 2), cache.bytes(PRECINCT, 0, 3)));

        expect("nothing unasked for appears", cache.bytes(PRECINCT, 0, 1234) == null
                && !cache.isComplete(PRECINCT, 0, 1234) && cache.available(PRECINCT, 0, 1234) == 0);

        cache.clear();
        expect("clearing empties the cache", cache.contents().isEmpty() && cache.bytesHeld() == 0);

        System.out.println(failures == 0 ? "DataBinCacheCheck: PASS" : "DataBinCacheCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private DataBinCacheCheck() {}

}
