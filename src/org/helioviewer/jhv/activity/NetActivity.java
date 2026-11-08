package org.helioviewer.jhv.activity;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.EventListener;

/**
 * Feeds the NET light and the activity log from Studio's HTTP clients: an OkHttp listener for the
 * shared client in NetClientRemote, which carries almost every download, and a wrapper for the few
 * places that use the JDK's own client. JPIP streams count through {@link Activity#countingNet}.
 */
public final class NetActivity {

    private NetActivity() {}

    /** One listener per OkHttp call: lit while the call is open, one log line when it ends. */
    public static final EventListener.Factory OKHTTP = call -> new Listener();

    private static final class Listener extends EventListener {
        private final long start = System.nanoTime();
        private long bytes;
        private boolean network;
        private boolean open;

        @Override
        public void callStart(Call call) {
            open = true;
            Activity.begin(Activity.Kind.NET);
        }

        @Override
        public void connectionAcquired(Call call, okhttp3.Connection connection) {
            network = true;
        }

        @Override
        public void responseBodyEnd(Call call, long byteCount) {
            bytes = byteCount;
            Activity.netBytes(byteCount);
        }

        @Override
        public void callEnd(Call call) {
            finish(call, "");
        }

        @Override
        public void callFailed(Call call, IOException ioe) {
            finish(call, " failed: " + ioe.getMessage());
        }

        @Override
        public void canceled(Call call) {
            if (open)
                finish(call, " cancelled");
        }

        private void finish(Call call, String outcome) {
            if (!open)
                return;
            open = false;
            Activity.end(Activity.Kind.NET);
            if (network) // a cache hit moved nothing over the network
                Activity.event(Activity.Kind.NET, "http", line(call.request().method(), call.request().url().toString(), bytes, start) + outcome);
        }
    }

    static String line(String method, String url, long bytes, long startNanos) {
        return String.format(Locale.ROOT, "%s %s %s in %.1f s", method, url, size(bytes), (System.nanoTime() - startNanos) / 1e9);
    }

    public static String size(long bytes) {
        if (bytes < 0)
            return "?";
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1 << 20)
            return String.format(Locale.ROOT, "%.1f kB", bytes / 1024.);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1 << 20));
    }

    /** {@code client.send}, counted: the NET light while it runs and one log line after. */
    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        Activity.begin(Activity.Kind.NET);
        String outcome = " failed";
        long bytes = -1;
        try {
            HttpResponse<T> response = client.send(request, handler);
            outcome = " HTTP " + response.statusCode();
            if (response.body() instanceof String s)
                bytes = s.length();
            Activity.netBytes(Math.max(0, bytes));
            return response;
        } finally {
            Activity.end(Activity.Kind.NET);
            Activity.event(Activity.Kind.NET, "http", line(request.method(), request.uri().toString(), bytes, start) + outcome);
        }
    }

}
