package org.helioviewer.jhv.io;

import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import com.sun.net.httpserver.HttpServer;

/**
 * Help > Send Feedback builds the report it says it builds, never writes the home folder into it,
 * and never loses one: with no endpoint it waits in the outbox, and the next retry that reaches a
 * server moves it to sent.
 *
 * <p>The endpoint is not deployed yet (extra/feedback-worker), so the empty URL is the case every
 * user of this build hits, and the report reaches Gilly by email: the mailto draft (subject, length,
 * encoding, no home folder) and the screenshot saved beside the report are pinned here, as is the
 * check on the endpoint file published on gilly.space. The retry is run against a server on
 * localhost, never the network; the endpoint file is never fetched here.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.io.FeedbackReportCheck
 */
public final class FeedbackReportCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static List<Path> json(Path dir) throws Exception {
        if (!Files.isDirectory(dir))
            return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    /** The decoded value of one field of a mailto URL, or null when absent. */
    private static String field(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0)
            return null;
        for (String kv : url.substring(q + 1).split("&"))
            if (kv.startsWith(name + '='))
                return URLDecoder.decode(kv.substring(name.length() + 1), StandardCharsets.UTF_8);
        return null;
    }

    /** Only characters a mailto URL may carry unencoded in its two fields: nothing a mail client could misread. */
    private static boolean encodedOnly(String url) {
        return url.matches("mailto:[A-Za-z0-9.@_-]+\\?subject=[A-Za-z0-9%._*-]*&body=[A-Za-z0-9%._*-]*");
    }

    /** No lone surrogate: a cut never split a character in two. */
    private static boolean wellFormed(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(++i))))
                return false;
            if (Character.isLowSurrogate(c))
                return false;
        }
        return !s.contains("\ufffd");
    }

    /**
     * The email the user is handed when no endpoint is known: to Gilly, a subject he can sort by,
     * the key facts in the body, short enough for every mail client, and no home folder.
     */
    private static void mailtoChecks(JSONObject p, Path file, String h) {
        String url = FeedbackReport.mailto(p, file, "0.8.6");
        String subject = field(url, "subject");
        String body = field(url, "body");
        expect("mailto: subject is [HFS version] kind: first line, got " + subject,
                "[HFS 0.8.6] Bug: It broke reading ~/data/a.fits".equals(subject));
        expect("mailto: within " + FeedbackReport.MAILTO_MAX + " characters, got " + url.length(), url.length() <= FeedbackReport.MAILTO_MAX);
        expect("mailto: every field character percent-encoded where it must be", encodedOnly(url));
        expect("mailto: line breaks are %0D%0A (RFC 6068)", url.contains("%0D%0A") && !url.matches(".*(?<!%0D)%0A.*"));
        expect("mailto: the body has the message", body != null && body.contains("It broke reading ~/data/a.fits"));
        expect("mailto: the build, OS and Java lines", body != null && body.contains("Version: ") && body.contains("OS: ") && body.contains("Java: "));
        expect("mailto: the error and the top of its stack", body != null && body.contains("Load failed")
                && body.contains("java.io.IOException: gone") && body.contains("at Foo.bar(~/src/Foo.java:1)"));
        expect("mailto: the report id, to match a later copy", body != null && body.contains(p.optString("id")));
        expect("mailto: names the saved file and asks for it to be attached", body != null
                && body.contains(file.getFileName().toString()) && body.contains("attach"));
        expect("mailto: the home folder appears nowhere", !url.contains(h) && (subject == null || !subject.contains(h))
                && (body == null || !body.contains(h)) && !url.contains(URLEncoder.encode(h, StandardCharsets.UTF_8)));

        // A long message and a deep stack: cut to fit, the attach line kept, the stack cut before the facts.
        StringBuilder deep = new StringBuilder("java.lang.IllegalStateException: deep\n");
        for (int i = 0; i < 300; i++)
            deep.append("\tat org.helioviewer.jhv.Frame").append(i).append(".call(Frame.java:").append(i).append(")\n");
        deep.append("Caused by: java.io.EOFException: root cause here\n\tat org.helioviewer.jhv.Reader.read(Reader.java:9)\n");
        JSONObject big = FeedbackReport.build(FeedbackReport.Category.BUG, "x".repeat(5000), "",
                new FeedbackReport.ErrorContext("Boom", "it fell over", deep.toString()),
                FeedbackReport.systemInfo("Test Renderer"), null, null);
        String bigUrl = FeedbackReport.mailto(big, file, "0.8.6");
        String bigBody = field(bigUrl, "body");
        expect("mailto: a long report still fits, got " + bigUrl.length(), bigUrl.length() <= FeedbackReport.MAILTO_MAX);
        expect("mailto: and keeps the attach line", bigBody != null && bigBody.contains(file.getFileName().toString()) && bigBody.contains("attach"));
        expect("mailto: and the version line", bigBody != null && bigBody.contains("Version: "));
        expect("mailto: and the exception line", bigBody != null && bigBody.contains("IllegalStateException: deep"));
        expect("mailto: and says the message was cut", bigBody != null && bigBody.contains("[cut"));

        // Characters that grow nine times when encoded, and a cut that lands inside a surrogate pair.
        JSONObject wide = FeedbackReport.build(FeedbackReport.Category.QUESTION, "a" + "\u00e9\ud83c\udf1e".repeat(1500), "", null, null, null, null);
        String wideUrl = FeedbackReport.mailto(wide, file, "0.8.6");
        String wideBody = field(wideUrl, "body");
        expect("mailto: non-ASCII text fits too, got " + wideUrl.length(), wideUrl.length() <= FeedbackReport.MAILTO_MAX);
        expect("mailto: and no character is split", wideBody != null && wellFormed(wideBody) && !wideBody.contains("?") /* URLEncoder writes a lone surrogate as ? */ && wideBody.contains("\u00e9\ud83c\udf1e"));

        expect("mailto: a cut inside a surrogate pair keeps the pair out whole", "a".equals(FeedbackReport.clip("a\ud83c\udf1eb", 2))
                && "a\ud83c\udf1e".equals(FeedbackReport.clip("a\ud83c\udf1eb", 3)));

        String unsaved = FeedbackReport.mailto(p, null, "0.8.6");
        String unsavedBody = field(unsaved, "body");
        expect("mailto: with no saved file it says so", unsaved.length() <= FeedbackReport.MAILTO_MAX
                && unsavedBody != null && unsavedBody.contains("could not be saved"));
    }

    /** The published endpoint file: one https URL to a Worker or to gilly.space, or nothing. */
    private static void endpointFileChecks() {
        expect("endpoint file: a workers.dev URL is taken",
                "https://hfs-feedback.gilly.workers.dev/".equals(FeedbackReport.publishedEndpoint("https://hfs-feedback.gilly.workers.dev/\n")));
        expect("endpoint file: blank lines and CRLF around it are fine",
                "https://feedback.gilly.space/report".equals(FeedbackReport.publishedEndpoint("\r\n  https://feedback.gilly.space/report  \r\n\r\n")));
        expect("endpoint file: gilly.space itself is taken", "https://gilly.space/feedback".equals(FeedbackReport.publishedEndpoint("https://gilly.space/feedback")));
        for (String bad : new String[]{null, "", "  \n\n", "http://hfs.gilly.workers.dev/", "https://evil.example.com/",
                "https://evilgilly.space/", "https://workers.dev/", "https://x.workers.dev.evil.com/", "https://gilly.space.evil.com/",
                "<!DOCTYPE html><html><body>404</body></html>", "https://a.workers.dev/\nhttps://b.workers.dev/",
                "https://user@hfs.gilly.workers.dev/", "https://hfs.gilly.workers.dev:8443/", "https://hfs.gilly.workers.dev/ x",
                "ftp://hfs.gilly.workers.dev/", "https:///path", "javascript:alert(1)//.workers.dev",
                "https://" + "a".repeat(600) + ".workers.dev/"})
            expect("endpoint file: refused " + (bad == null ? "null" : bad.length() > 60 ? bad.substring(0, 60) + "..." : bad.replace("\n", "\\n")),
                    "".equals(FeedbackReport.publishedEndpoint(bad)));
    }

    public static void main(String[] args) throws Exception {
        // Before the first mention of Directories or Settings: both capture user.home when loaded.
        Path home = Files.createTempDirectory("hfs-feedback");
        System.setProperty("user.home", home.toString());
        Directories.SETTINGS.getFile().mkdirs(); // else every Settings write logs a stack trace over the results
        String h = home.toString();

        JSONObject session = new JSONObject().put("org.helioviewer.jhv.state", new JSONObject()
                .put("imageLayers", new JSONArray().put(new JSONObject().put("data", new JSONObject()
                        .put("uris", new JSONArray().put("file:" + h + "/data/a.fits"))))));
        FeedbackReport.ErrorContext error = new FeedbackReport.ErrorContext("Load failed",
                "Could not read " + h + "/data/a.fits",
                "java.io.IOException: gone\n\tat Foo.bar(" + h + "/src/Foo.java:1)\n");
        JSONObject p = FeedbackReport.build(FeedbackReport.Category.BUG, "It broke reading " + h + "/data/a.fits",
                "someone@example.org", error, FeedbackReport.systemInfo("Test Renderer"),
                "line one\nopened " + h + "/data/a.fits\n", session);

        // The fields the Worker reads.
        expect("schema 1, got " + p.opt("schema"), p.optInt("schema") == 1);
        expect("a report id", p.optString("id").matches("[0-9a-f-]{36}"));
        expect("a creation time, got " + p.opt("created"), p.optString("created").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"));
        String install = p.optString("installId");
        expect("an install id, got " + install, install.matches("[0-9a-f-]{36}"));
        expect("kept in the one settings key", install.equals(Settings.getProperty(FeedbackReport.INSTALL_ID_KEY)));
        expect("the user agent", AppInfo.userAgent.equals(p.optString("userAgent")) && !p.optString("userAgent").isEmpty());
        expect("category bug, got " + p.opt("category"), "bug".equals(p.optString("category")));
        expect("the message", p.optString("message").startsWith("It broke reading "));
        expect("the reply-to address", "someone@example.org".equals(p.optString("replyTo")));
        JSONObject e = p.optJSONObject("error");
        expect("the error's title, message and stack trace", e != null && "Load failed".equals(e.optString("title"))
                && e.optString("message").startsWith("Could not read") && e.optString("stackTrace").contains("IOException"));
        JSONObject sys = p.optJSONObject("system");
        expect("system info: build, OS, Java, GL renderer, got " + sys, sys != null
                && sys.optString("buildId").equals(AppInfo.buildId()) && !sys.optString("os").isEmpty()
                && !sys.optString("java").isEmpty() && "Test Renderer".equals(sys.optString("gl")));
        expect("the log tail", p.optString("log").startsWith("line one"));
        expect("the session state", p.optJSONObject("session") != null);

        // Redaction, everywhere.
        expect("the home folder appears nowhere in the payload", !p.toString().contains(h));
        expect("it is written as ~ in the message", p.optString("message").endsWith("~/data/a.fits"));
        expect("in the error and its stack trace", e != null && e.optString("message").endsWith("~/data/a.fits")
                && e.optString("stackTrace").contains("(~/src/Foo.java:1)"));
        expect("in the log", p.optString("log").contains("opened ~/data/a.fits"));
        expect("and in the session", p.toString().contains("file:~/data/a.fits"));

        // Nothing the user left unticked.
        JSONObject bare = FeedbackReport.build(FeedbackReport.Category.QUESTION, "Why?", "", null, null, null, null);
        List<String> absent = Stream.of("replyTo", "error", "system", "log", "session", "screenshot").filter(bare::has).toList();
        expect("an unticked item is absent, present: " + absent, absent.isEmpty());
        expect("and the install id is stable", install.equals(bare.optString("installId")));

        // Only HTTPS leaves the machine; plain HTTP only to this machine.
        expect("https is allowed", FeedbackReport.allowed("https://feedback.example.workers.dev/"));
        expect("http to localhost is allowed", FeedbackReport.allowed("http://127.0.0.1:8080/"));
        expect("http elsewhere is refused", !FeedbackReport.allowed("http://example.org/"));
        expect("an empty URL is refused", !FeedbackReport.allowed(""));

        // No endpoint: the report waits in the outbox.
        Path outbox = FeedbackReport.outbox();
        expect("the outbox is under ~/HFStudio, got " + outbox, outbox.startsWith(Path.of(Directories.HOME.getPath())));
        FeedbackReport.Delivery d = FeedbackReport.submit(p, "");
        expect("an empty URL does not send", d != null && !d.sent());
        List<Path> waiting = json(outbox);
        expect("and leaves one report in the outbox, got " + waiting, waiting.size() == 1 && d != null && waiting.get(0).equals(d.file()));
        String saved = waiting.isEmpty() ? "" : Files.readString(waiting.get(0));
        expect("which is the payload", !saved.isEmpty() && new JSONObject(saved).optString("id").equals(p.optString("id")));
        expect("email text names the address and fits a mailto URL", d != null
                && FeedbackReport.mailto(p, d.file()).startsWith("mailto:" + AppInfo.emailAddress + "?subject=")
                && FeedbackReport.mailto(p, d.file()).length() < 2000 && !FeedbackReport.mailto(p, d.file()).contains(" "));
        if (d != null)
            mailtoChecks(p, d.file(), h);
        endpointFileChecks();

        // A retry against a server on this machine.
        AtomicInteger status = new AtomicInteger(503);
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> type = new AtomicReference<>();
        AtomicReference<String> agent = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            body.set(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            type.set(x.getRequestHeaders().getFirst("Content-Type"));
            agent.set(x.getRequestHeaders().getFirst("User-Agent"));
            byte[] reply = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status.get(), reply.length);
            x.getResponseBody().write(reply);
            x.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            int sent = FeedbackReport.retryOutbox(url);
            expect("a server error sends nothing, got " + sent, sent == 0);
            expect("and the report stays in the outbox", json(outbox).equals(waiting));

            status.set(201);
            sent = FeedbackReport.retryOutbox(url);
            expect("a working server takes it, got " + sent, sent == 1);
            expect("the body is the saved file", saved.equals(body.get()));
            expect("as JSON, got " + type.get(), type.get() != null && type.get().startsWith("application/json"));
            expect("with the user agent, got " + agent.get(), AppInfo.userAgent.equals(agent.get()));
            expect("the outbox is empty", json(outbox).isEmpty());
            List<Path> done = json(outbox.resolve("sent"));
            expect("and the report moved to sent, got " + done, done.size() == 1
                    && !waiting.isEmpty() && done.get(0).getFileName().equals(waiting.get(0).getFileName()));

            body.set(null);
            FeedbackReport.Delivery direct = FeedbackReport.submit(bare, url);
            expect("submitting with a URL sends at once", direct != null && direct.sent() && body.get() != null
                    && direct.file().getParent().equals(outbox.resolve("sent")));
        } finally {
            server.stop(0);
        }

        // The email road: the report in the outbox, and its screenshot beside it as a file a mail program can attach.
        JSONObject pictured = FeedbackReport.build(FeedbackReport.Category.BUG, "With a picture", "", null, null, null, null);
        FeedbackReport.attachScreenshot(pictured, new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB));
        Path mailed = FeedbackReport.saveForEmail(pictured);
        Path png = FeedbackReport.screenshotFile(mailed);
        expect("email road: the report is saved in the outbox", mailed.getParent().equals(outbox) && Files.isRegularFile(mailed));
        expect("email road: the screenshot sits beside it as " + png.getFileName(), png.getParent().equals(outbox)
                && png.getFileName().toString().equals(mailed.getFileName().toString().replace(".json", ".png")));
        BufferedImage back = Files.isRegularFile(png) ? ImageIO.read(png.toFile()) : null;
        expect("email road: and it is the PNG, 4 x 3", back != null && back.getWidth() == 4 && back.getHeight() == 3);
        Path plain = FeedbackReport.saveForEmail(bare.put("id", "00000000-0000-0000-0000-000000000000"));
        expect("email road: no screenshot, no picture file", !Files.exists(FeedbackReport.screenshotFile(plain)));
        expect("email road: the outbox retry reads only the reports", FeedbackReport.waiting().equals(Stream.of(mailed, plain).sorted().toList()));

        System.out.println(failures == 0 ? "FeedbackReportCheck: ok" : "FeedbackReportCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
