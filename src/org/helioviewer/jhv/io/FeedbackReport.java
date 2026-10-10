package org.helioviewer.jhv.io;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.movie.Provenance;
import org.helioviewer.jhv.opengl.GL;
import org.helioviewer.jhv.thread.AppThread;

import org.json.JSONObject;

/**
 * A feedback report, as Help &gt; Send Feedback and the Report this... button on error dialogs send it,
 * and the outbox that keeps it until it is delivered.
 *
 * <p>A report is JSON POSTed to {@link #endpoint()}, a Cloudflare Worker (extra/feedback-worker) that
 * turns it into an issue, so nobody needs a GitHub account to report a problem. Every report is
 * written to the outbox first and moved to outbox/sent once the server answers 2xx, so a report
 * survives no network, no endpoint and a crash mid-send; nothing here deletes one. The outbox is
 * retried at the next launch with a window ({@link #retryInBackground}).
 *
 * <p>The endpoint is compiled in ({@link #ENDPOINT}), set ({@link #ENDPOINT_KEY}), or published in
 * {@link #ENDPOINT_FILE} and looked up once per launch. With none, the dialog takes the email road
 * instead: {@link #saveForEmail} and a {@link #mailto} draft to Gilly that asks for the file to be attached.
 *
 * <p>The home folder is written as "~" everywhere in a report, by the export provenance code's own
 * rewrite. FeedbackReportCheck pins the fields, the redaction and the outbox.
 */
public final class FeedbackReport {

    /**
     * Where reports go once the Worker is deployed: Gilly sets this, and a release carries it. Empty
     * until then, so the endpoint comes from {@link #ENDPOINT_FILE}, or else reports go by email. A
     * non-empty {@link #ENDPOINT_KEY} setting wins, to try a Worker before a release has its URL.
     */
    static final String ENDPOINT = "";
    public static final String ENDPOINT_KEY = "feedback.endpoint";
    /**
     * When the build and the setting name no endpoint, the app reads it from this file on Gilly's site,
     * once per launch, so the Worker can go live without a new build. A data contract, append-only:
     * released builds read this exact URL, so it is never moved or renamed (extra/feedback-worker/README.md).
     */
    static final String ENDPOINT_FILE = "https://gilly.space/hfstudio/feedback-endpoint.txt";
    private static final int ENDPOINT_FILE_MAX = 512;
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    /** The endpoint the file named at this launch, "" for none; completed once, by the one lookup. */
    private static final CompletableFuture<String> PUBLISHED = new CompletableFuture<>();
    private static final AtomicBoolean LOOKUP_STARTED = new AtomicBoolean();
    /** A random id per installation, so the Worker can rate-limit one machine without knowing who it is. */
    public static final String INSTALL_ID_KEY = "feedback.installId";
    /** Lines of this run's log that a report carries. */
    public static final int LOG_LINES = 300;
    /** The long side of an attached screenshot, at most, in pixels. */
    private static final int SCREENSHOT_MAX = 1600;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Object LOCK = new Object(); // one delivery at a time, so no report is posted twice

    public enum Category {
        BUG("bug", "Bug"), FEATURE("feature", "Feature request"), QUESTION("question", "Question");

        final String key;
        private final String label;

        Category(String _key, String _label) {
            key = _key;
            label = _label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The error dialog a report was opened from: its title, its text and, when there was one, the exception's stack. */
    public record ErrorContext(String title, String message, @Nullable String stackTrace) {
        public static ErrorContext of(String title, String message, @Nullable Throwable cause) {
            if (cause == null)
                return new ErrorContext(title, message, null);
            StringWriter sw = new StringWriter();
            try (PrintWriter pw = new PrintWriter(sw)) {
                cause.printStackTrace(pw);
            }
            return new ErrorContext(title, message, sw.toString());
        }
    }

    /** Where a submitted report ended up: in outbox/sent when sent, in the outbox otherwise. */
    public record Delivery(Path file, boolean sent) {}

    // ---- building ---------------------------------------------------------------------------

    /**
     * The payload. Items the user left unticked are passed as null and are absent, not empty. Call on
     * the EDT when session comes from State.snapshot(). The screenshot, if any, is added afterwards
     * by {@link #attachScreenshot}, outside the redaction, which must never touch image data.
     */
    public static JSONObject build(Category category, String message, String replyTo, @Nullable ErrorContext error,
                                   @Nullable JSONObject system, @Nullable String log, @Nullable JSONObject session) {
        JSONObject p = new JSONObject()
                .put("schema", 1)
                .put("id", UUID.randomUUID().toString())
                .put("created", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                .put("installId", installId())
                .put("userAgent", AppInfo.userAgent)
                .put("category", category.key)
                .put("message", message.strip());
        if (!replyTo.isBlank())
            p.put("replyTo", replyTo.strip());
        if (error != null) {
            JSONObject e = new JSONObject().put("title", error.title()).put("message", error.message());
            if (error.stackTrace() != null)
                e.put("stackTrace", error.stackTrace());
            p.put("error", e);
        }
        if (system != null)
            p.put("system", system);
        if (log != null)
            p.put("log", log);
        if (session != null)
            p.put("session", session);
        String home = System.getProperty("user.home", "");
        return (JSONObject) Provenance.rewrite(p, s -> Provenance.stripHome(s, home));
    }

    /** What the About window says about this build, and the machine it runs on. */
    public static JSONObject systemInfo() {
        return systemInfo(GL.renderer.isBlank() ? "" : GL.renderer + ", " + GL.version);
    }

    static JSONObject systemInfo(String gl) {
        return new JSONObject()
                .put("program", AppInfo.programName)
                .put("buildId", AppInfo.buildId())
                .put("version", AppInfo.version)
                .put("revision", AppInfo.revision)
                .put("commit", AppInfo.commit())
                .put("dirty", AppInfo.dirty())
                .put("os", System.getProperty("os.name") + ' ' + System.getProperty("os.version") + ' ' + System.getProperty("os.arch"))
                .put("java", System.getProperty("java.vendor") + ' ' + System.getProperty("java.version"))
                .put("gl", gl.isBlank() ? "unknown" : gl)
                .put("detail", AppInfo.versionDetail);
    }

    /** The last lines of this run's log file, or a line saying why there are none. */
    public static String logTail(int lines) {
        try {
            List<String> all = new String(Files.readAllBytes(Path.of(Log.filename())), StandardCharsets.UTF_8).lines().toList();
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "(this run's log could not be read: " + e.getMessage() + ')';
        }
    }

    /** Adds the image, scaled to at most SCREENSHOT_MAX on its long side, as a base64 PNG. */
    public static void attachScreenshot(JSONObject payload, BufferedImage image) throws IOException {
        double scale = Math.min(1, SCREENSHOT_MAX / (double) Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(out, "png", png);
        payload.put("screenshot", new JSONObject().put("type", "image/png").put("width", w).put("height", h)
                .put("base64", Base64.getEncoder().encodeToString(png.toByteArray())));
    }

    /** The id this installation sends, made once and kept in one setting. */
    public static synchronized String installId() {
        String id = Settings.getProperty(INSTALL_ID_KEY);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            Settings.setProperty(INSTALL_ID_KEY, id);
        }
        return id;
    }

    // ---- sending ----------------------------------------------------------------------------

    /** The endpoint a build or the user names: the setting when it is set, else the one compiled in. Empty means none. */
    static String configuredEndpoint() {
        String set = Settings.getProperty(ENDPOINT_KEY);
        return set == null || set.isBlank() ? ENDPOINT : set.strip();
    }

    /**
     * The endpoint in force: {@link #configuredEndpoint()}, else the one this launch's lookup found in
     * {@link #ENDPOINT_FILE}. Empty means none, including while the lookup is still running. Never waits.
     */
    public static String endpoint() {
        String configured = configuredEndpoint();
        return configured.isBlank() ? PUBLISHED.getNow("") : configured;
    }

    /**
     * Starts this launch's one lookup of {@link #ENDPOINT_FILE}, on its own thread, when neither the build
     * nor the setting names an endpoint; later calls start nothing. Safe on the EDT. The future completes
     * with the endpoint in force ("" for none) once it is known.
     */
    public static CompletableFuture<String> lookUpEndpoint() {
        String configured = configuredEndpoint();
        if (!configured.isBlank())
            return CompletableFuture.completedFuture(configured);
        if (LOOKUP_STARTED.compareAndSet(false, true))
            AppThread.create(() -> {
                try {
                    PUBLISHED.complete(fetchPublishedEndpoint());
                } finally {
                    PUBLISHED.complete(""); // whatever went wrong, the lookup ends with an answer
                }
            }, "HFS-FeedbackEndpoint").start();
        return PUBLISHED;
    }

    /** One GET of the endpoint file, with a short timeout. Anything but a 200 holding one acceptable URL means none. */
    private static String fetchPublishedEndpoint() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(LOOKUP_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT_FILE))
                    .timeout(LOOKUP_TIMEOUT)
                    .header("User-Agent", AppInfo.userAgent)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                Log.info("No feedback endpoint published (HTTP " + response.statusCode() + " for " + ENDPOINT_FILE + ')');
                return "";
            }
            String found = publishedEndpoint(response.body());
            Log.info(found.isEmpty() ? "Feedback endpoint file ignored: it holds no acceptable URL" : "Feedback endpoint from " + ENDPOINT_FILE + ": " + found);
            return found;
        } catch (IOException | IllegalArgumentException e) {
            Log.info("Feedback endpoint lookup failed: " + e);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /**
     * The endpoint a published file names, or "" for none. The file holds one line, an https URL whose host
     * is a Cloudflare Worker (ends with .workers.dev) or gilly.space or one of its subdomains, with no user
     * name and no port. Anything else (blank, an HTML error page, two lines, plain http, another host) is
     * ignored, so a broken or hijacked file cannot send a report anywhere else.
     */
    static String publishedEndpoint(@Nullable String body) {
        if (body == null || body.length() > ENDPOINT_FILE_MAX)
            return "";
        List<String> lines = body.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.size() != 1)
            return "";
        String url = lines.getFirst();
        try {
            URI u = new URI(url);
            String host = u.getHost();
            if (!"https".equalsIgnoreCase(u.getScheme()) || host == null || u.getRawUserInfo() != null || u.getPort() != -1)
                return "";
            host = host.toLowerCase(Locale.ROOT);
            return host.endsWith(".workers.dev") || host.equals("gilly.space") || host.endsWith(".gilly.space") ? url : "";
        } catch (URISyntaxException e) {
            return "";
        }
    }

    /** HTTPS anywhere, or plain HTTP to this machine only (for testing a Worker under wrangler dev). */
    static boolean allowed(String url) {
        try {
            URI u = new URI(url.strip());
            String host = u.getHost();
            if (host == null)
                return false;
            if ("https".equalsIgnoreCase(u.getScheme()))
                return true;
            return "http".equalsIgnoreCase(u.getScheme())
                    && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    public static Path outbox() {
        return Path.of(Directories.HOME.getPath(), "Outbox");
    }

    /** Saves the report to the outbox, then sends it when an endpoint is given. Off the EDT: it may wait on the network. */
    public static Delivery submit(JSONObject payload, String endpoint) throws IOException {
        Path file = save(payload);
        return deliver(file, endpoint) ? new Delivery(file.resolveSibling("sent").resolve(file.getFileName()), true) : new Delivery(file, false);
    }

    /**
     * For the email road, when no endpoint is known: saves the report to the outbox as {@link #save} does
     * and, when it carries a screenshot, also writes the picture beside it ({@link #screenshotFile}) so a
     * mail program can attach it. Returns the report's file. Off the EDT.
     */
    public static Path saveForEmail(JSONObject payload) throws IOException {
        Path file = save(payload);
        JSONObject shot = payload.optJSONObject("screenshot");
        if (shot != null) {
            try {
                Files.write(screenshotFile(file), Base64.getDecoder().decode(shot.optString("base64")));
            } catch (IllegalArgumentException | IOException e) { // the report itself is saved; the picture is still inside it
                Log.warn("Feedback screenshot not written beside " + file.getFileName() + ": " + e);
            }
        }
        return file;
    }

    /** Where {@link #saveForEmail} puts a report's screenshot: beside it, same name, .png. The outbox retry reads only .json. */
    public static Path screenshotFile(Path report) {
        String name = report.getFileName().toString();
        return report.resolveSibling((name.endsWith(".json") ? name.substring(0, name.length() - 5) : name) + ".png");
    }

    /** Written through a temporary file, so a retry never reads half a report. Named by time, so the outbox sorts oldest first. */
    static Path save(JSONObject payload) throws IOException {
        Path dir = outbox();
        Files.createDirectories(dir);
        String id = payload.optString("id");
        String name = payload.optString("created").replace(":", "") + '-' + id.substring(0, Math.min(8, id.length())) + ".json";
        Path file = dir.resolve(name);
        Path temp = dir.resolve(name + ".tmp");
        Files.writeString(temp, payload.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file);
        }
        return file;
    }

    /** POSTs one saved report and, on a 2xx answer, moves it into sent/. False otherwise, with the reason in the log. */
    static boolean deliver(Path file, String endpoint) {
        if (!allowed(endpoint)) {
            if (!endpoint.isBlank())
                Log.warn("Feedback not sent: the endpoint must be HTTPS, or HTTP to this machine: " + endpoint);
            return false;
        }
        synchronized (LOCK) {
            if (!Files.isRegularFile(file)) // already delivered by the other thread
                return false;
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.strip()))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("User-Agent", AppInfo.userAgent)
                        .POST(HttpRequest.BodyPublishers.ofFile(file))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    String body = response.body();
                    Log.warn("Feedback " + file.getFileName() + " not accepted: HTTP " + response.statusCode() + ' '
                            + body.substring(0, Math.min(200, body.length())));
                    return false;
                }
                Path sent = file.resolveSibling("sent");
                Files.createDirectories(sent);
                Files.move(file, sent.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                Log.info("Feedback " + file.getFileName() + " sent");
                return true;
            } catch (IOException | IllegalArgumentException e) {
                Log.warn("Feedback " + file.getFileName() + " not sent: " + e);
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Tries every report in the outbox once, oldest first. Returns how many were sent. */
    static int retryOutbox(String endpoint) {
        int sent = 0;
        for (Path file : waiting())
            if (deliver(file, endpoint))
                sent++;
        return sent;
    }

    /** The reports waiting in the outbox, oldest first: its .json files only, never a screenshot beside one. */
    static List<Path> waiting() {
        Path dir = outbox();
        if (!Files.isDirectory(dir))
            return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p)).sorted().toList();
        } catch (IOException e) {
            Log.warn("Feedback outbox unreadable: " + e);
            return List.of();
        }
    }

    /**
     * At a launch with a window: look up the endpoint when the build names none, then send what the outbox
     * holds, each on a thread of its own. Nothing is sent without an endpoint.
     */
    public static void retryInBackground() {
        lookUpEndpoint().thenAccept(endpoint -> {
            if (endpoint.isBlank())
                return;
            AppThread.create(() -> {
                int sent = retryOutbox(endpoint);
                if (sent > 0)
                    Log.info("Sent " + sent + " saved feedback report(s) from " + outbox());
            }, "HFS-FeedbackOutbox").start();
        });
    }

    // ---- words for people -------------------------------------------------------------------

    /**
     * The report as a person reads it: the Preview, the clipboard copy. Every item it carries, in full,
     * except the screenshot's bytes, which text cannot show; the dialog shows the picture beside it.
     */
    public static String asText(JSONObject p) {
        StringBuilder sb = new StringBuilder()
                .append("Category: ").append(categoryLabel(p.optString("category"))).append('\n')
                .append("Message:\n").append(p.optString("message")).append("\n\n")
                .append("Reply to: ").append(p.optString("replyTo", "(none given)")).append('\n')
                .append("Report id: ").append(p.optString("id")).append('\n')
                .append("Created: ").append(p.optString("created")).append('\n')
                .append("Install id: ").append(p.optString("installId")).append('\n')
                .append("User agent: ").append(p.optString("userAgent")).append('\n');
        JSONObject e = p.optJSONObject("error");
        if (e != null) {
            sb.append("\nError shown: ").append(e.optString("title")).append('\n').append(e.optString("message")).append('\n');
            if (e.has("stackTrace"))
                sb.append("Stack trace:\n").append(e.optString("stackTrace")).append('\n');
        }
        JSONObject sys = p.optJSONObject("system");
        if (sys != null) {
            sb.append("\nSystem:\n");
            for (String key : List.of("program", "buildId", "os", "java", "gl", "detail"))
                sb.append("  ").append(key).append(": ").append(sys.opt(key)).append('\n');
        }
        if (p.has("log"))
            sb.append("\nLog (last ").append(LOG_LINES).append(" lines of this run):\n").append(p.optString("log")).append('\n');
        JSONObject session = p.optJSONObject("session");
        if (session != null)
            sb.append("\nSession state:\n").append(session.toString(2)).append('\n');
        JSONObject shot = p.optJSONObject("screenshot");
        if (shot != null)
            sb.append("\nScreenshot: ").append(shot.optInt("width")).append(" x ").append(shot.optInt("height"))
                    .append(" PNG, ").append(shot.optString("base64").length() * 3 / 4 / 1024).append(" KB\n");
        return sb.toString();
    }

    /**
     * The longest mailto: URL handed to the mail program, in characters, encoded. An estimate: RFC 6068
     * sets no limit, but on Windows a URL goes through ShellExecuteEx, which stops at
     * INTERNET_MAX_URL_LENGTH (about 2048 characters, per EurekaLog's notes on sending reports by mailto),
     * and some mail programs are said to cut sooner. 1900 leaves a margin; it was not measured per client.
     */
    static final int MAILTO_MAX = 1900;
    /** Stack frames the email keeps per exception in the chain; the saved file has them all. */
    private static final int MAIL_FRAMES = 8;
    /** The message's share of the email body, before encoding; the saved file has all of it. */
    private static final int MAIL_MESSAGE = 700;

    /** A mailto: URL to Gilly for this report and its saved file, as {@link #mailto(JSONObject, Path, String)} with this build's version. */
    public static String mailto(JSONObject p, @Nullable Path file) {
        return mailto(p, file, AppInfo.label());
    }

    /**
     * A mailto: URL to Gilly: subject "[HFS version] Kind: first line", and a body with the message, the
     * build and system lines, the report id, the error and the top of its stack, and a last paragraph that
     * asks for the saved file to be attached (a mailto link cannot attach one). Kept within
     * {@link #MAILTO_MAX} by cutting the end of the details, never the attach paragraph. Line breaks are
     * CRLF, as RFC 6068 asks. Every part comes from the payload, where the home folder is already "~",
     * and the file's path is rewritten the same way.
     */
    static String mailto(JSONObject p, @Nullable Path file, String version) {
        String message = p.optString("message").strip();
        String first = message.lines().findFirst().orElse("").strip();
        String subject = "[HFS " + version + "] " + categoryLabel(p.optString("category")) + ": " + clip(first, 60);

        StringBuilder details = new StringBuilder();
        details.append(message.length() > MAIL_MESSAGE ? clip(message, MAIL_MESSAGE) + " [cut; the full text is in the attached report]" : message)
                .append("\n\n");
        JSONObject sys = p.optJSONObject("system");
        details.append("Version: ").append(sys != null ? sys.optString("buildId") : version).append('\n');
        if (sys != null)
            details.append("OS: ").append(sys.optString("os")).append('\n')
                    .append("Java: ").append(sys.optString("java")).append('\n')
                    .append("Graphics: ").append(sys.optString("gl")).append('\n');
        details.append("Report id: ").append(p.optString("id")).append('\n');
        JSONObject e = p.optJSONObject("error");
        if (e != null) {
            details.append("\nError shown: ").append(e.optString("title")).append(": ").append(e.optString("message")).append('\n');
            if (e.has("stackTrace"))
                details.append(stackSummary(e.optString("stackTrace"), MAIL_FRAMES));
        }

        String home = System.getProperty("user.home", "");
        String attach = file == null
                ? "\n\nThe full report could not be saved as a file. Please use Copy to Clipboard in " + AppInfo.programName
                  + " and paste it here."
                : "\n\nPlease attach the full report before sending: " + Provenance.stripHome(file.toString(), home)
                  + " (and the .png screenshot beside it, if there is one). This email cannot attach it for you.";
        String to = "mailto:" + AppInfo.emailAddress + "?subject=" + encode(subject) + "&body=";
        String tail = encode(attach);
        String all = details.toString().stripTrailing();
        String body = encode(all);
        if (to.length() + body.length() + tail.length() <= MAILTO_MAX)
            return to + body + tail;
        // Too long: the longest start of the details that fits with a cut marker, found by halving.
        String marker = "\n[cut to fit an email]";
        int lo = 0, hi = all.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (to.length() + encode(clip(all, mid) + marker).length() + tail.length() <= MAILTO_MAX)
                lo = mid;
            else
                hi = mid - 1;
        }
        return to + encode(clip(all, lo) + marker) + tail;
    }

    /**
     * The part of a stack trace that says most in a few lines: every exception line (the first and each
     * "Caused by:"), each followed by at most {@code frames} of its "at" lines, and a count of what was left.
     */
    static String stackSummary(String trace, int frames) {
        StringBuilder sb = new StringBuilder();
        int kept = 0, skipped = 0;
        for (String line : trace.lines().toList()) {
            String t = line.strip();
            if (t.isEmpty())
                continue;
            boolean frame = t.startsWith("at ") || t.startsWith("... ");
            if (!frame) {
                if (skipped > 0)
                    sb.append("    (").append(skipped).append(" more)\n");
                kept = 0;
                skipped = 0;
                sb.append(t).append('\n');
            } else if (kept < frames) {
                kept++;
                sb.append("    ").append(t).append('\n');
            } else
                skipped++;
        }
        if (skipped > 0)
            sb.append("    (").append(skipped).append(" more)\n");
        return sb.toString();
    }

    /** The first n characters, never splitting a surrogate pair. */
    static String clip(String s, int n) {
        if (s.length() <= n)
            return s;
        if (n > 0 && Character.isHighSurrogate(s.charAt(n - 1)))
            n--;
        return s.substring(0, n);
    }

    /** Percent-encoded for a mailto field: spaces as %20, line breaks as %0D%0A (RFC 6068 section 5). */
    private static String encode(String s) {
        return URLEncoder.encode(s.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n"), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String categoryLabel(String key) {
        for (Category c : Category.values())
            if (c.key.equals(key))
                return c.label;
        return key;
    }

    private FeedbackReport() {}
}
