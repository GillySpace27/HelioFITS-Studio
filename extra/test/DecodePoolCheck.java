package org.helioviewer.jhv.thread;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Settings;

/** One shared decode pool: its width follows the Setting or the cores, and native threads are shared out by how many decodes run. */
public final class DecodePoolCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-decode-pool").toString());
        System.setProperty("java.awt.headless", "true");
        Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.io.Directories.SETTINGS.getFile().mkdirs();

        expect("12 performance cores leave 10 decodes", DecodePool.automatic(12) == 10);
        expect("a 2-core machine still decodes", DecodePool.automatic(2) == 1 && DecodePool.automatic(1) == 1);

        expect("one decode alone on 10 wide gets the most native threads (4)", DecodePool.nativeThreads(10, 1) == 4);
        expect("ten at once get one each", DecodePool.nativeThreads(10, 10) == 1);
        expect("five at once get two each", DecodePool.nativeThreads(10, 5) == 2);
        expect("more decodes than the width still get one", DecodePool.nativeThreads(10, 30) == 1);
        expect("on a width of 1 a decode gets one", DecodePool.nativeThreads(1, 1) == 1);

        Settings.setProperty(DecodePool.KEY, "3");
        expect("the Setting sets the width", DecodePool.width() == 3);
        DecodePool.apply();
        expect("and apply resizes the pool", DecodePool.executor().getMaximumPoolSize() == 3 && DecodePool.executor().getCorePoolSize() == 3);
        Settings.setProperty(DecodePool.KEY, "8");
        DecodePool.apply();
        expect("growing works", DecodePool.executor().getMaximumPoolSize() == 8 && DecodePool.executor().getCorePoolSize() == 8);
        Settings.setProperty(DecodePool.KEY, "0");
        expect("0 is automatic", DecodePool.width() >= 1 && DecodePool.width() <= Runtime.getRuntime().availableProcessors());
        Settings.setProperty(DecodePool.KEY, "garbage");
        expect("a bad value is automatic", DecodePool.width() >= 1);

        int before = DecodePool.begin();
        int second = DecodePool.begin();
        DecodePool.end();
        DecodePool.end();
        expect("begin and end pair up: a lone decode again gets as many as the first did", DecodePool.begin() == before && second <= before);
        DecodePool.end();

        System.out.println(failures == 0 ? "DecodePoolCheck: PASS" : "DecodePoolCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private DecodePoolCheck() {}

}
