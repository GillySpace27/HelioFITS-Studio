package org.helioviewer.jhv.io;

import java.io.BufferedReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.TimeUtils;

/**
 * Native FITS from the Virtual Solar Observatory, which is the only route to "most missions"
 * without writing an archive client per mission: SOHO/LASCO, SDO/AIA, STEREO, EIT and the rest
 * answer one query.
 *
 * <p>Getting files out of VSO is two calls, not one, and that is the part worth knowing. Query
 * returns <em>fileids</em>, which are archive-internal paths and are not fetchable: the SDAC ones
 * look like {@code /archive/soho/private/data/...} and 404 on every public host. GetData with
 * {@code URL-FILE} is what converts a fileid into a real URL, and it must go to the federated
 * broker rather than a provider's own endpoint (SDAC's answers Query fine and then rejects GetData
 * with "VSO-V410 Unknown Data Provider", which is a confusing way to say "wrong address").
 *
 * <p>VSO speaks SOAP and nothing else: its endpoint refuses form-encoded posts outright. No SOAP
 * library is needed for it though, because both requests are fixed envelopes with a few
 * substitutions, and both responses can be read with a regex over the elements that matter.
 */
public final class VsoClient {

    // The federated broker, from the service section of VSOi_rpc_literal.wsdl. Do NOT point this
    // at a provider endpoint: Query would still work and GetData would not.
    private static final URI ENDPOINT = URI.create("https://vso.nascom.nasa.gov/cgi-bin/sunpy_vsoi");
    private static final String CONTENT_TYPE = "text/xml; charset=utf-8";

    /**
     * VSO's schema marks Info.email as required and the broker rejects a request without it. It is
     * used to notify on staged (asynchronous) requests, which URL-FILE never produces, so nothing
     * is ever sent anywhere. A placeholder goes here deliberately rather than the user's real
     * address: the field is load-bearing for the protocol and meaningless for this method.
     */
    private static final String INFO_EMAIL = "jhelioviewer@localhost";

    // Parsed per <recorditem>, never globally. VSO does not keep a stable element order inside a
    // record -- one comes back physobs/provider/.../time/.../fileid and the next
    // extent/instrument/fileid/.../time -- so pairing the Nth fileid with the Nth provider across
    // the whole document associates the wrong ones as soon as the order shifts.
    private static final Pattern RECORD = Pattern.compile("<recorditem>(.*?)</recorditem>", Pattern.DOTALL);
    private static final Pattern FILEID = Pattern.compile("<fileid[^>]*>(.*?)</fileid>", Pattern.DOTALL);
    private static final Pattern PROVIDER = Pattern.compile("<provider[^>]*>([A-Za-z0-9_-]+)</provider>");
    // Two steps, because the instability reaches inside <time> as well: one record comes back
    // <start><end> and the next <end><start>. Scope to the block, then find the child.
    private static final Pattern TIME_BLOCK = Pattern.compile("<time>(.*?)</time>", Pattern.DOTALL);
    private static final Pattern START = Pattern.compile("<start[^>]*>(\\d{14})</start>");
    private static final Pattern URL = Pattern.compile("<url[^>]*>(.*?)</url>", Pattern.DOTALL);
    private static final Pattern FAULT = Pattern.compile("<faultstring[^>]*>(.*?)</faultstring>", Pattern.DOTALL);
    private static final Pattern STATUS = Pattern.compile("<status[^>]*>(.*?)</status>", Pattern.DOTALL);

    /** One VSO record: the archive-internal id, who resolves it, and when it was taken. */
    public record Record(String fileid, String provider, long milli) {}

    public static void submitResolve(@Nonnull FitsRequest request, @Nonnull Consumer<List<URI>> receiver) {
        Task.submit("vso", new Resolve(request), receiver::accept, "Error querying the VSO");
    }

    private record Resolve(FitsRequest request) implements Callable<List<URI>> {
        @Override
        public List<URI> call() throws Exception {
            List<Record> records = query(request);
            if (records.isEmpty())
                return List.of();
            return getData(records, request.cadence());
        }
    }

    /**
     * Query by instrument over a span. The instrument name is the product field of the request,
     * which is what VSO calls an instrument ("lasco", "aia", "eit"); the detector, where one
     * matters, rides along in the level field ("C2", "C3").
     */
    public static List<Record> query(FitsRequest request) throws Exception {
        StringBuilder block = new StringBuilder(256);
        block.append("<time><start>").append(vsoTime(request.startTime()))
                .append("</start><end>").append(vsoTime(request.endTime())).append("</end></time>");
        block.append("<instrument>").append(xml(request.product())).append("</instrument>");
        if (!request.level().isBlank())
            block.append("<detector>").append(xml(request.level())).append("</detector>");

        String body = envelope("Query", "<body><block>" + block + "</block></body>");
        return parseRecords(post(body));
    }

    /** Split out from the network call so extra/test can exercise it against a canned response. */
    static List<Record> parseRecords(String response) {
        List<Record> out = new ArrayList<>();
        Matcher rec = RECORD.matcher(response);
        while (rec.find()) {
            String item = rec.group(1);
            String fileid = first(FILEID, item);
            if (fileid == null || fileid.isBlank())
                continue;
            String provider = first(PROVIDER, item);
            String timeBlock = first(TIME_BLOCK, item);
            String start = timeBlock == null ? null : first(START, timeBlock);
            out.add(new Record(fileid, provider == null ? "SDAC" : provider, parseVsoTime(start)));
        }
        out.sort(java.util.Comparator.comparingLong(Record::milli));
        return out;
    }

    /** {@code yyyyMMddHHmmss} in UTC to epoch millis, or 0 when absent or malformed. */
    private static long parseVsoTime(@javax.annotation.Nullable String s) {
        if (s == null || s.length() != 14)
            return 0;
        try {
            return java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Turn records into fetchable URLs. Grouped by provider because a DataRequestItem carries one
     * provider and the fileids belonging to it, and a federated query can span several.
     */
    public static List<URI> getData(List<Record> records, long cadence) throws Exception {
        List<Record> selected = thin(records, cadence);

        Set<String> providers = new LinkedHashSet<>();
        selected.forEach(r -> providers.add(r.provider));

        StringBuilder container = new StringBuilder(512);
        for (String provider : providers) {
            container.append("<datarequestitem><provider>").append(xml(provider)).append("</provider><fileiditem>");
            for (Record r : selected)
                if (r.provider.equals(provider))
                    container.append("<fileid>").append(xml(r.fileid)).append("</fileid>");
            container.append("</fileiditem></datarequestitem>");
        }

        String body = envelope("GetData", "<body><request>"
                + "<method><methodtype>URL-FILE</methodtype></method>"
                + "<info><email>" + INFO_EMAIL + "</email></info>"
                + "<datacontainer>" + container + "</datacontainer>"
                + "</request></body>");
        String response = post(body);

        return parseUrls(response);
    }

    /** Also split out for testing; see parseRecords. */
    static List<URI> parseUrls(String response) {
        for (String status : all(STATUS, response))
            if (!status.isBlank())
                Log.warn("VSO GetData status: " + status);

        List<URI> uris = new ArrayList<>();
        for (String u : all(URL, response)) {
            String clean = u.strip();
            if (clean.isEmpty())
                continue;
            try {
                // The broker returns a doubled slash after the host on SDAC paths. Harmless to most
                // servers, but it makes two spellings of one file, and the file cache keys on the
                // URI string, so the same frame would be downloaded and stored twice.
                uris.add(URI.create(clean.replaceFirst("(?<!:)//", "/")));
            } catch (RuntimeException e) {
                Log.warn("VSO returned an unusable URL: " + clean);
            }
        }
        return uris;
    }

    /**
     * Keep records no closer together than {@code cadence} ms. VSO has no cadence parameter, so
     * thinning happens here rather than at the archive; a cadence of 0 keeps everything.
     */
    static List<Record> thin(List<Record> records, long cadence) {
        if (cadence <= 0)
            return records;
        List<Record> out = new ArrayList<>(records.size());
        long last = Long.MIN_VALUE;
        for (Record r : records) {
            // A record with no parseable time is kept rather than dropped: losing a frame because
            // its timestamp did not parse would be a silent hole in the movie.
            if (r.milli == 0 || last == Long.MIN_VALUE || r.milli - last >= cadence) {
                out.add(r);
                if (r.milli != 0)
                    last = r.milli;
            }
        }
        return out;
    }

    @javax.annotation.Nullable
    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? unxml(m.group(1).strip()) : null;
    }

    private static String post(String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (NetClient nc = NetClient.post(ENDPOINT, CONTENT_TYPE, bytes, true, NetClient.NetCache.NETWORK);
             BufferedReader reader = new BufferedReader(nc.getReader())) {
            StringBuilder sb = new StringBuilder(1 << 14);
            char[] buf = new char[1 << 13];
            for (int n; (n = reader.read(buf)) > 0; )
                sb.append(buf, 0, n);
            String response = sb.toString();

            Matcher fault = FAULT.matcher(response);
            if (fault.find())
                throw new Exception("VSO refused the request: " + unxml(fault.group(1)));
            return response;
        }
    }

    private static String envelope(String operation, String inner) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " xmlns:VSO=\"http://virtualsolar.org/VSO/VSOi\"><soap:Body><VSO:" + operation + ">"
                + inner + "</VSO:" + operation + "></soap:Body></soap:Envelope>";
    }

    private static List<String> all(Pattern p, String s) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find())
            out.add(unxml(m.group(1).strip()));
        return out;
    }

    /** VSO wants YYYYMMDDHHMMSS in UTC, with no separators and no zone suffix. */
    private static String vsoTime(long milli) {
        return TimeUtils.format(milli).replaceAll("[-:T]", "").substring(0, 14);
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String unxml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private VsoClient() {}
}
