package org.helioviewer.jhv.io;

import java.util.List;

import org.json.JSONObject;
import org.json.JSONTokener;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

/**
 * The MLSO API client: the query it builds, the replies it reads, and where its session cookie goes.
 *
 * <p>The replies below follow the key names and the example values in the client's documentation
 * (mlso-api-client 1.0.0, endpoints.html and usage.html); none was captured from the live API.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.io.MlsoClientCheck
 */
public final class MlsoClientCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-mlso").toString());

        // The every filter: the largest exact unit, plural as the documentation spells it.
        expect("native cadence sends no every", MlsoClient.every(0) == null);
        expect("10 minutes", "10minutes".equals(MlsoClient.every(600_000)));
        expect("1 hour", "1hour".equals(MlsoClient.every(3_600_000)));
        expect("6 hours", "6hours".equals(MlsoClient.every(6 * 3_600_000)));
        expect("1 day", "1day".equals(MlsoClient.every(86_400_000)));
        expect("90 seconds", "90seconds".equals(MlsoClient.every(90_000)));

        long start = org.helioviewer.jhv.time.TimeUtils.parse("2025-03-24T00:00:00");
        long end = org.helioviewer.jhv.time.TimeUtils.parse("2025-03-24T23:59:59");
        String url = MlsoClient.filesUrl("ucomp", "l2", start, end, 3_600_000, "1074");
        expect("files URL: " + url, ("http://api.mlso.ucar.edu/v1/instruments/ucomp/products/l2/files" +
                "?start-date=2025-03-24T00%3A00%3A00&end-date=2025-03-24T23%3A59%3A59&every=1hour&wave-region=1074").equals(url));
        expect("no every or wave region when not asked",
                MlsoClient.filesUrl("kcor", "pb", start, end, 0, null).endsWith("end-date=2025-03-24T23%3A59%3A59"));

        // The instrument list: bare, as documented, or wrapped in an object.
        expect("ids from a bare list", List.of("kcor", "ucomp").equals(MlsoClient.parseIds(json("[\"kcor\", \"ucomp\"]"))));
        expect("ids from a wrapped list", List.of("kcor", "ucomp").equals(MlsoClient.parseIds(json("{\"instruments\": [\"kcor\", \"ucomp\"]}"))));
        MlsoClient.Instrument kcor = MlsoClient.parseInstrument("kcor", new JSONObject(
                "{\"name\": \"COSMO K-Coronagraph (KCor)\", \"dates\": {\"start-date\": \"2013-09-30T18:57:54\", \"end-date\": \"2025-03-24T21:03:55\"}}"));
        expect("instrument name shown", "COSMO K-Coronagraph (KCor)".equals(kcor.toString()));
        expect("instrument dates", "2013-09-30T18:57:54 to 2025-03-24T21:03:55".equals(kcor.dates()));

        List<MlsoClient.Product> products = MlsoClient.parseProducts(json(
                "{\"products\": [{\"id\": \"l1\", \"title\": \"Level 1\", \"description\": \"IQUV and backgrounds for various wavelengths\"}," +
                        " {\"title\": \"no id\"}, {\"id\": \"all\", \"title\": \"All\", \"description\": \"all products\"}]}"));
        expect("products without an id are dropped", products.size() == 2 && "l1".equals(products.get(0).id()));

        List<MlsoClient.DataItem> files = MlsoClient.parseFiles(json(
                "{\"instrument\": \"ucomp\", \"product\": \"l2\", \"start-date\": \"2025-03-23\", \"end-date\": \"2025-03-24T21:03:55\", \"total_filesize\": 0, \"files\": [" +
                        "{\"date-obs\": \"2025-03-24T20:06:52\", \"filename\": \"20250324.200652.ucomp.789.l2.fts\", \"filesize\": 0, \"instrument\": \"ucomp\", \"product\": \"l2\"," +
                        " \"url\": \"http://api.mlso.ucar.edu/v1/download?obsday-id=10137&client=python&instrument=ucomp&filename=20250324.200652.ucomp.789.l2.fts\"}," +
                        "{\"date-obs\": \"not a time\", \"filename\": \"bad.fts\", \"url\": \"http://api.mlso.ucar.edu/v1/download\"}," +
                        "{\"date-obs\": \"2025-03-23T19:03:36\", \"filename\": \"20250323.190336.ucomp.789.l2.fts\", \"filesize\": 12345, \"instrument\": \"ucomp\", \"product\": \"l2\"," +
                        " \"url\": \"http://api.mlso.ucar.edu/v1/download?obsday-id=10136&client=python&instrument=ucomp&filename=20250323.190336.ucomp.789.l2.fts\"}]}"));
        expect("two good files, the bad row skipped", files.size() == 2);
        expect("sorted by time", files.size() == 2 && files.get(0).filename().startsWith("20250323"));
        expect("time from date-obs", files.size() == 2 && files.get(1).milli() == org.helioviewer.jhv.time.TimeUtils.parse("2025-03-24T20:06:52"));
        expect("download URL kept whole", files.size() == 2 && files.get(1).uri().getQuery().contains("obsday-id=10137"));
        expect("size kept", files.size() == 2 && files.get(0).size() == 12345);

        // The session cookie goes back to the API host and to no other.
        HttpUrl api = HttpUrl.get("http://api.mlso.ucar.edu/v1/authenticate?username=a%40b.org");
        HttpUrl other = HttpUrl.get("https://umbra.nascom.nasa.gov/punch/");
        MlsoClient.COOKIES.saveFromResponse(api, List.of(Cookie.parse(api, "session=abc; Path=/")));
        MlsoClient.COOKIES.saveFromResponse(other, List.of(Cookie.parse(other, "tracker=x; Path=/")));
        expect("cookie sent to a download", MlsoClient.COOKIES.loadForRequest(HttpUrl.get("http://api.mlso.ucar.edu/v1/download?filename=x.fts")).size() == 1);
        expect("cookie never sent elsewhere", MlsoClient.COOKIES.loadForRequest(other).isEmpty());
        MlsoClient.COOKIES.saveFromResponse(api, List.of(Cookie.parse(api, "session=def; Path=/")));
        List<Cookie> now = MlsoClient.COOKIES.loadForRequest(api);
        expect("a new session replaces the old one", now.size() == 1 && "def".equals(now.get(0).value()));

        if (failures > 0) {
            System.out.println("MlsoClientCheck: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("MlsoClientCheck: PASS");
    }

    private static Object json(String s) {
        return new JSONTokener(s).nextValue();
    }
}
