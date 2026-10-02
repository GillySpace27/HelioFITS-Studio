package org.helioviewer.jhv.io;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

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
 * user of this build hits. The retry is run against a server on localhost, never the network.
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

        System.out.println(failures == 0 ? "FeedbackReportCheck: ok" : "FeedbackReportCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
