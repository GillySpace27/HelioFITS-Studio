package org.helioviewer.jhv.io;

import java.net.URI;
import java.util.List;

/**
 * VSO's response parsing, against canned responses with deliberately shuffled element order.
 *
 * <p>That shuffling is the whole point. VSO does not preserve child order, and it bit twice while
 * this client was being written: first pairing the Nth fileid with the Nth provider across the
 * document, which mis-associates as soon as a record reorders, and then reading start times with a
 * pattern that assumed {@code <start>} came before {@code <end>} inside {@code <time>}. Neither
 * failed loudly. The first would attribute a file to the wrong archive, the second silently
 * stamped 1970 on the frame and disabled cadence thinning for it.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.io.VsoClientCheck
 */
public final class VsoClientCheck {

    private static int failures;

    // Three records: normal order, reversed time children, and fields shuffled around the fileid.
    private static final String QUERY_RESPONSE = """
            <QueryResponse><body>
            <record><recorditem>
              <provider>SDAC</provider>
              <time><start>20120101000005</start><end>20120101000031</end></time>
              <fileid>/archive/a.fts</fileid>
              <instrument>LASCO</instrument>
            </recorditem>
            <recorditem>
              <extent><type>CORONA</type></extent>
              <fileid>/archive/b.fts</fileid>
              <time><end>20120101001230</end><start>20120101001205</start></time>
              <provider>JSOC</provider>
            </recorditem>
            <recorditem>
              <time><start>20120101003605</start><end>20120101003631</end></time>
              <fileid>/archive/c.fts</fileid>
              <provider>SDAC</provider>
            </recorditem></record>
            </body></QueryResponse>
            """;

    public static void main(String[] args) {
        List<VsoClient.Record> recs = VsoClient.parseRecords(QUERY_RESPONSE);
        expect(recs.size() == 3, "three records parsed, got " + recs.size());

        // Every time must parse. A zero here is the 1970 bug: silent, and it disables thinning.
        for (VsoClient.Record r : recs)
            expect(r.milli() > 0, r.fileid() + ": time did not parse (" + r.milli() + ")");

        // Records come back sorted, whatever order the document had.
        for (int i = 1; i < recs.size(); i++)
            expect(recs.get(i - 1).milli() <= recs.get(i).milli(), "records are in time order");

        // The provider must follow its own record, not its position in the document.
        VsoClient.Record b = recs.stream().filter(r -> r.fileid().endsWith("b.fts")).findFirst().orElseThrow();
        expect("JSOC".equals(b.provider()), "b.fts belongs to JSOC, got " + b.provider());

        // Thinning uses real times: 12 min apart, so a 15 min floor drops the middle one.
        expect(VsoClient.thin(recs, 900_000).size() == 2, "15 min thinning keeps 2 of 3");
        expect(VsoClient.thin(recs, 0).size() == 3, "no cadence keeps everything");
        // A record whose time did not parse is kept rather than dropped: a silent hole in a movie
        // is worse than a frame too many.
        List<VsoClient.Record> withUnknown = List.of(new VsoClient.Record("/x.fts", "SDAC", 0));
        expect(VsoClient.thin(withUnknown, 900_000).size() == 1, "an unparsed time is kept, not dropped");

        // The broker doubles the slash after the host; two spellings of one file would be cached
        // and downloaded twice, since the file cache keys on the URI string.
        List<URI> urls = VsoClient.parseUrls(
                "<body><dataitem><url>https://seal.nascom.nasa.gov//archive/a.fts</url></dataitem></body>");
        expect(urls.size() == 1, "one url parsed");
        expect("https://seal.nascom.nasa.gov/archive/a.fts".equals(urls.getFirst().toString()),
                "the doubled slash is collapsed, got " + urls.getFirst());

        expect(VsoClient.parseRecords("<QueryResponse></QueryResponse>").isEmpty(), "an empty response yields nothing");

        if (failures != 0)
            throw new AssertionError(failures + " VSO failure(s)");
        System.out.println("VsoClientCheck: PASS");
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
