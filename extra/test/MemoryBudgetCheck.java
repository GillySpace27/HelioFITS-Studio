package org.helioviewer.jhv.app;

/**
 * The shared memory budget: heap plus decoded frames stay inside the share, whatever the machine,
 * and the share is read from Settings with a safe default. Before it, decoded frames alone took
 * 40% of physical memory and the heap another 25%.
 */
public final class MemoryBudgetCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-memory-budget").toString());
        System.setProperty("java.awt.headless", "true");
        Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.io.Directories.SETTINGS.getFile().mkdirs(); // else every Settings write logs a stack trace over the results

        long gb = 1L << 30;
        boolean inside = true, binsSmallerThanDecoded = true;
        for (long physical : new long[]{8 * gb, 16 * gb, 64 * gb, 128 * gb})
            for (int percent = 10; percent <= 90; percent += 10) {
                long total = MemoryBudget.total(physical, percent);
                long bins = MemoryBudget.binBytes(total), decoded = MemoryBudget.decodedBytes(total);
                // the heap's part is twice the bins' (bins are half of it); together with the frames it is the share
                if (total >= 2 * gb && 2 * bins + decoded > total)
                    inside = false;
                if (total >= 2 * gb && bins >= decoded)
                    binsSmallerThanDecoded = false;
            }
        expect("heap and decoded frames stay inside the share on 8 to 128 GB machines at 10 to 90%", inside);
        expect("the bins get a smaller part than the decoded frames", binsSmallerThanDecoded);
        expect("on this scale the old 40% decoded-only budget would have gone past the 50% share with the heap",
                128 * gb * 40 / 100 + 128 * gb / 4 > MemoryBudget.total(128 * gb, 50));
        expect("a 128 GB machine at 50% gives 64 GB in all", MemoryBudget.total(128 * gb, 50) == 64 * gb);

        expect("the bins' budget is the share's part without an override", MemoryBudget.binBytes() == MemoryBudget.binBytes(MemoryBudget.total()));
        System.setProperty("jhv.binBudgetMB", "150");
        expect("-Djhv.binBudgetMB sets the bins' budget directly", MemoryBudget.binBytes() == 150L << 20);
        System.clearProperty("jhv.binBudgetMB");

        expect("the share is 50% when nothing is set", MemoryBudget.sharePercent() == 50);
        Settings.setProperty(MemoryBudget.SHARE_KEY, "30");
        expect("and follows the Setting", MemoryBudget.sharePercent() == 30);
        Settings.setProperty(MemoryBudget.SHARE_KEY, "nonsense");
        expect("a bad value falls back to the default", MemoryBudget.sharePercent() == 50);
        Settings.setProperty(MemoryBudget.SHARE_KEY, "500");
        expect("and a huge one is held to 90%", MemoryBudget.sharePercent() == 90);

        System.out.println(failures == 0 ? "MemoryBudgetCheck: PASS" : "MemoryBudgetCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private MemoryBudgetCheck() {}

}
