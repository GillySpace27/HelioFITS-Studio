package org.helioviewer.jhv.io;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.TimeUtils;

/**
 * Native LASCO level-0.5 FITS from NRL's LZ archive, which is the route to recent data: the
 * VSO's LASCO catalog simply stops in early 2025 (measured 2026-08-31: 27 records for six hours
 * of 2025-01-01, zero from 2025-06-01 on), while the LZ archive is current. Same autoindex
 * pattern the PUNCH client parses, one directory per UTC day per detector:
 * {@code level_05/YYMMDD/c2/NNNNNNNN.fts}.
 *
 * <p>Filenames are sequence numbers, not timestamps, so cadence thinning here is by index
 * within each day: the files sort in time order, and the layer keys frames by their FITS
 * DATE-OBS once decoded, so day granularity costs at most a few frames outside the exact hours
 * asked for.
 */
public final class LascoClient {

    private static final String BASE_URL = "https://lasco-www.nrl.navy.mil/lz/level_05";
    private static final Pattern FILE_PATTERN = Pattern.compile("href=\"(\\d+\\.fts)\"");

    public static void submitResolve(@Nonnull FitsRequest request, @Nonnull Consumer<List<URI>> receiver) {
        Task.submit("lasco", new Resolve(request), receiver::accept, "Error listing the LASCO archive");
    }

    private record Resolve(FitsRequest request) implements Callable<List<URI>> {
        @Override
        public List<URI> call() throws Exception {
            return query(request);
        }
    }

    static List<URI> query(FitsRequest request) throws Exception {
        String detector = request.product().toLowerCase(Locale.ROOT); // "c2" / "c3"
        long cadence = request.cadence();
        int perDay = cadence <= 0 ? Integer.MAX_VALUE
                : (int) Math.max(1, TimeUtils.DAY_IN_MILLIS / cadence);

        List<URI> out = new ArrayList<>();
        for (long day = TimeUtils.floorDay(request.startTime()); day <= request.endTime(); day += TimeUtils.DAY_IN_MILLIS) {
            LocalDateTime date = LocalDateTime.ofEpochSecond(day / 1000, 0, ZoneOffset.UTC);
            String dirUrl = String.format("%s/%02d%02d%02d/%s/",
                    BASE_URL, date.getYear() % 100, date.getMonthValue(), date.getDayOfMonth(), detector);
            String html = readIndex(dirUrl);
            if (html == null) // a day the archive does not have is a gap, not an error
                continue;

            List<String> files = new ArrayList<>();
            Matcher m = FILE_PATTERN.matcher(html);
            while (m.find())
                files.add(m.group(1));
            files.sort(null); // fixed-width numeric names, lexicographic == chronological

            int step = perDay == Integer.MAX_VALUE ? 1 : Math.max(1, (int) Math.ceil(files.size() / (double) perDay));
            int kept = 0;
            for (int i = 0; i < files.size(); i += step) {
                out.add(URI.create(dirUrl + files.get(i)));
                kept++;
            }
            Log.info("LASCO " + detector + " " + dirUrl + " -> " + files.size() + " files, kept " + kept);
        }
        return out;
    }

    private static String readIndex(String url) throws Exception {
        try (NetClient nc = NetClient.of(new URI(url), true, NetClient.NetCache.NETWORK)) {
            if (!nc.isSuccessful()) {
                Log.info("LASCO " + url + " -> not ok");
                return null;
            }
            return nc.getSource().readUtf8();
        }
    }

    private LascoClient() {
    }

}
