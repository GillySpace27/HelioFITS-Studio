package org.helioviewer.jhv.io;

import java.io.BufferedReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
            List<Record> records = filterRecords(queryChunked(request), request.version());
            if (records.isEmpty())
                return List.of();

            long cadence = request.cadence();
            if (cadence <= 0)
                return getData(records, 0);

            // Ask for more frames than the cadence needs, so that a frame thrown out below has a
            // neighbour ready to stand in for it rather than leaving a hole. Three quarters of the
            // cadence puts the stand-in a few minutes from the moment that was wanted.
            List<URI> generous = getData(records, cadence * 3 / 4);
            return rejectDarkFrames(generous, cadence);
        }
    }

    private static final int PROBE_THREADS = 6;
    /** A frame carrying this little of the usual spread has no image in it. */
    private static final double SIGNAL_FLOOR = .1;

    /**
     * Drop the frames that contain no picture, then thin what is left to the cadence asked for.
     *
     * <p>SUVI writes an occasional frame with nothing in it, a dark or a shutter that did not
     * open, and it arrives looking like uniform noise stretched across the whole display range
     * because the decoder scales every frame to its own extremes. Its own header says so: on
     * 2025-09-20 the two frames at 05:03 and 05:23 carry IMG_SDEV near 0.015 against 2.3 to 4.6
     * for every other frame that day, and IMG_MEAN of 0.0004 against 1.15.
     *
     * <p>Judged against the median of the frames actually asked for rather than a fixed number,
     * so it travels across channels, exposure times and missions instead of encoding one day of
     * SUVI. A frame whose header will not read, or an archive that publishes no such statistic,
     * is kept: neither is evidence of a bad frame.
     */
    private static List<URI> rejectDarkFrames(List<URI> uris, long cadence) throws Exception {
        if (uris.size() < 4) // too few to have a median worth trusting
            return uris;

        List<Probe> probes;
        ExecutorService pool = Executors.newFixedThreadPool(PROBE_THREADS);
        try {
            List<Future<Probe>> futures = new ArrayList<>(uris.size());
            for (URI uri : uris)
                futures.add(pool.submit(() -> probe(uri)));
            probes = new ArrayList<>(uris.size());
            for (Future<Probe> f : futures)
                probes.add(f.get());
        } finally {
            pool.shutdown();
        }

        double[] signals = probes.stream().mapToDouble(Probe::signal).filter(s -> s > 0).sorted().toArray();
        if (signals.length < 4)
            return pickOnGrid(probes, cadence); // nothing to compare against; just honour the cadence
        double floor = signals[signals.length / 2] * SIGNAL_FLOOR;

        List<Probe> kept = new ArrayList<>(probes.size());
        int dropped = 0;
        for (Probe p : probes) {
            if (Double.isNaN(p.signal()) || p.signal() >= floor)
                kept.add(p);
            else
                dropped++;
        }
        if (dropped > 0)
            Log.info("VSO dropped " + dropped + " frame(s) with no image, keeping " + kept.size());
        return pickOnGrid(kept, cadence);
    }

    /** A candidate frame and what its own header says about whether there is a picture in it. */
    private record Probe(URI uri, long milli, double signal) {}

    private static Probe probe(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        Map<String, String> header = FitsHeaders.read(uri, path.toLowerCase(java.util.Locale.US).endsWith(".gz"));
        if (header == null)
            return new Probe(uri, 0, Double.NaN);
        long milli = 0;
        String date = header.get("DATE-OBS");
        if (date != null) {
            try {
                milli = TimeUtils.parse(date.length() > 19 ? date.substring(0, 19) : date);
            } catch (RuntimeException ignore) {
            }
        }
        return new Probe(uri, milli, FitsHeaders.number(header, "IMG_SDEV"));
    }

    /**
     * The frame nearest each wanted moment, which is what substitution means.
     *
     * <p>Not a minimum-gap walk. The candidates arrive spaced more finely than the cadence so that
     * a rejected frame has a neighbour, and walking that list keeping anything at least a cadence
     * apart lands on every second candidate instead: asking for 96 frames a day at 15 minutes
     * returned 59, an accidental 22-minute cadence. Choosing the closest frame to each point on
     * the wanted grid keeps the count and the spacing that were asked for, and where a frame was
     * thrown out it quietly takes the next one along, a few minutes either side.
     */
    private static List<URI> pickOnGrid(List<Probe> probes, long cadence) {
        List<Probe> timed = probes.stream().filter(p -> p.milli() > 0)
                .sorted(java.util.Comparator.comparingLong(Probe::milli)).toList();
        if (timed.isEmpty()) // no usable times: hand back what there is rather than nothing
            return probes.stream().map(Probe::uri).toList();

        long first = timed.getFirst().milli();
        long last = timed.getLast().milli();
        Set<URI> used = new LinkedHashSet<>();
        for (long want = first; want <= last; want += cadence) {
            Probe best = null;
            long bestGap = Long.MAX_VALUE;
            for (Probe p : timed) {
                long gap = Math.abs(p.milli() - want);
                if (gap < bestGap && !used.contains(p.uri())) {
                    bestGap = gap;
                    best = p;
                }
            }
            if (best != null)
                used.add(best.uri());
        }
        return List.copyOf(used);
    }

    private static final int QUERY_THREADS = 4;

    /**
     * One query per day, run a few at a time, thinned as each lands.
     *
     * <p>The VSO answers a whole span in one response and takes about as long as that span is
     * wide: even narrowed to a single SUVI channel a day costs 8 s, so a fortnight in one request
     * is two minutes of apparent hang. Days are independent questions, so they overlap.
     *
     * <p>Each day's records come back whole rather than thinned to cadence, which is tempting and
     * wrong: the satellite choice in {@link #filterRecords} is a decision about the whole range and
     * runs afterwards, so thinning first hands it an already-sparse list and it halves that again.
     * Thinning per day cost a third of the frames before cadence was ever applied.
     *
     * <p>A day that fails is a gap rather than a failed load: the rest of the range is still worth
     * showing, and the log says which day was lost.
     */
    private static List<Record> queryChunked(FitsRequest request) throws Exception {
        long start = request.startTime();
        long end = request.endTime();
        if (end - start <= TimeUtils.DAY_IN_MILLIS)
            return query(request);

        List<FitsRequest> days = new ArrayList<>();
        for (long day = start; day < end; day += TimeUtils.DAY_IN_MILLIS)
            days.add(request.withSpan(day, Math.min(end, day + TimeUtils.DAY_IN_MILLIS)));

        ExecutorService pool = Executors.newFixedThreadPool(QUERY_THREADS);
        try {
            List<Future<List<Record>>> futures = new ArrayList<>(days.size());
            for (FitsRequest day : days)
                futures.add(pool.submit(() -> query(day)));
            List<Record> all = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    all.addAll(futures.get(i).get());
                } catch (ExecutionException e) {
                    Log.warn("VSO query failed for " + TimeUtils.format(days.get(i).startTime()), e.getCause());
                }
            }
            all.sort(java.util.Comparator.comparingLong(Record::milli));
            return all;
        } finally {
            pool.shutdown();
        }
    }

    private static final Pattern SATELLITE = Pattern.compile("_(G\\d+)_");

    /**
     * Narrow a federated answer by fileid. VSO filters by instrument and detector, but some
     * missions distinguish what matters in some other way: SUVI encodes channel and satellite in
     * the fileid ("SUVI-L1b-Fe195_G19"), so a query for "suvi" returns every channel of every
     * GOES flying, six channels times two spacecraft. The request's version field carries a
     * token to keep ("Fe195"; blank keeps everything, which is every non-SUVI source). After the
     * token, when more than one satellite still answers, only the best-covered one is kept: a
     * movie that alternates spacecraft jitters by their pointing difference every frame.
     */
    static List<Record> filterRecords(List<Record> records, String token) {
        if (token.isBlank())
            return records;

        String needle = token.toLowerCase(java.util.Locale.US);
        List<Record> kept = new ArrayList<>(records.size());
        for (Record r : records)
            if (r.fileid().toLowerCase(java.util.Locale.US).contains(needle))
                kept.add(r);
        // The token also narrows the query by wavelength at the server, so an archive that names
        // its files some other way has already been filtered and matching none of them here means
        // the token was never about the fileid. Emptying the layer would be the wrong conclusion.
        if (kept.isEmpty() && !records.isEmpty()) {
            Log.info("VSO token " + token + " matched no fileid; keeping the " + records.size() + " records as queried");
            return records;
        }

        java.util.Map<String, Integer> perSatellite = new java.util.HashMap<>();
        for (Record r : kept) {
            Matcher m = SATELLITE.matcher(r.fileid());
            if (m.find())
                perSatellite.merge(m.group(1), 1, Integer::sum);
        }
        if (perSatellite.size() < 2)
            return kept;
        String best = java.util.Collections.max(perSatellite.entrySet(), java.util.Map.Entry.comparingByValue()).getKey();
        return kept.stream().filter(r -> {
            Matcher m = SATELLITE.matcher(r.fileid());
            return m.find() && best.equals(m.group(1));
        }).toList();
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
        // Narrow at the server when the channel is known. SUVI answers about 17000 records a day
        // across six channels and two spacecraft, and asking for all of them to keep one twelfth
        // took 23 s for a single day and scaled linearly: a week-long range simply looked hung.
        int wave = waveFromToken(request.version());
        if (wave > 0)
            block.append("<wave><wavemin>").append(wave - WAVE_SLOP).append("</wavemin><wavemax>")
                    .append(wave + WAVE_SLOP).append("</wavemax><waveunit>Angstrom</waveunit></wave>");

        String body = envelope("Query", "<body><block>" + block + "</block></body>");
        return parseRecords(post(body));
    }

    // Wide enough to cover a channel named for its line and catalogued at its rounded wavelength:
    // the 9.4 nm channel is "Fe093" in the filename and 94 in the catalog, 30.4 is "He303" and 304.
    private static final int WAVE_SLOP = 6;
    private static final Pattern TOKEN_DIGITS = Pattern.compile("(\\d{2,4})$");

    /** Angstroms from a channel token like "Fe195", or 0 when the token names no channel. */
    static int waveFromToken(String token) {
        Matcher m = TOKEN_DIGITS.matcher(token.trim());
        if (!m.find())
            return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
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
