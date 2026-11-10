package org.helioviewer.jhv.activity;

import org.helioviewer.jhv.thread.FetchGate;

/**
 * The watchdog's verdicts and effects, apart from its clock and the event thread: 5 s of silence is
 * nothing, 10 s holds fetches, 30 s cancels loads. And the local API's /stop answers off the event
 * thread, with the token, and holds fetches.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.activity.WatchdogCheck
 */
public final class WatchdogCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true"); // a stall that needs a person opens no dialog here
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-watchdog").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();

        expect("5 s is no stage", Watchdog.stageFor(5_000) == Watchdog.Stage.NONE);
        expect("10 s pauses", Watchdog.stageFor(10_000) == Watchdog.Stage.PAUSE);
        expect("30 s cancels", Watchdog.stageFor(30_000) == Watchdog.Stage.CANCEL);

        Watchdog.onSilence(5_000);
        expect("a short silence holds nothing", !FetchGate.isPaused() && !Watchdog.intervened());
        Watchdog.onSilence(11_000);
        expect("a long one holds fetches", FetchGate.isPaused() && Watchdog.intervened());

        // /stop, over HTTP, with the event thread free to do nothing at all: the call is answered on the server's own thread.
        FetchGate.pause(false);
        var ep = org.helioviewer.jhv.io.api.LocalApi.start();
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.URI uri = java.net.URI.create("http://127.0.0.1:" + ep.port() + "/stop");
        var denied = http.send(java.net.http.HttpRequest.newBuilder(uri).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        expect("/stop without the token is refused, got " + denied.statusCode(), denied.statusCode() == 401);
        expect("and holds nothing", !FetchGate.isPaused());
        var ok = http.send(java.net.http.HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + ep.token())
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        expect("/stop with it answers 200 and says what it did, got " + ok.statusCode() + " " + ok.body(),
                ok.statusCode() == 200 && ok.body().contains("\"cancelled\":0"));
        expect("and holds fetches", FetchGate.isPaused());
        org.helioviewer.jhv.io.api.LocalApi.stop();

        long t = 1_000_000;
        Watchdog.answered(t);
        Watchdog.answered(t + Watchdog.QUIET_MS);
        expect("a stop stays held until a person resumes", FetchGate.isPaused());
        Watchdog.resume();
        expect("and Resume lets go", !FetchGate.isPaused());

        expect("a plain stall resumes itself", Watchdog.autoResumes(false, 1));
        expect("a cancelling one does not", !Watchdog.autoResumes(true, 1));
        expect("nor the third within the window", !Watchdog.autoResumes(false, 3));

        t += 10_000_000;
        Watchdog.onSilence(11_000);
        Watchdog.answered(t);
        Watchdog.answered(t + Watchdog.QUIET_MS - 1);
        expect("still held just before the quiet period is up", FetchGate.isPaused());
        Watchdog.answered(t + Watchdog.QUIET_MS);
        expect("resumed once it is", !FetchGate.isPaused());

        t += 10_000_000; // a burst, each stall inside the last one's quiet period: one event, and a person's
        for (int i = 0; i < 3; i++) {
            Watchdog.onSilence(11_000);
            Watchdog.answered(t + i * 20_000L);
        }
        expect("a burst is not settled while it lasts", FetchGate.isPaused());
        Watchdog.answered(t + 40_000 + Watchdog.QUIET_MS);
        expect("and three in five minutes stay held", FetchGate.isPaused());
        Watchdog.resume();

        System.out.println(failures == 0 ? "WatchdogCheck: all ok" : "WatchdogCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
