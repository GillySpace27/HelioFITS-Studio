package org.helioviewer.jhv.plugins.pointcloud;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.helioviewer.jhv.io.NetClient;

import org.json.JSONArray;
import org.json.JSONObject;

// CME cone fits from CCMC's DONKI "CME Analysis" service.
//
// Each record is a SWPC_CAT (CME Analysis Tool) fit, which is an ice-cream-cone fit: apex at
// Sun centre, radial axis at a Stonyhurst longitude/latitude, opening half-angle, leading edge
// a spherical cap. That is exactly PointCloudArrow's cone, so a record drops straight in.
//
// Deliberately NOT sourced from HEK. HEK's CME entries come from CACTus and carry only a
// plane-of-sky position angle and angular width; for a halo that is close to no directional
// information at all. DONKI is the only free catalog here that publishes a 3-D fit.
//
// Caveat worth keeping in mind when reading a fit: several CMEs can be in flight on the same
// day and DONKI lists them all. On 2021-10-28 the loud one (the X1/GLE halo, 15:53Z) sits
// ~111 deg away from the limb event these point clouds reconstruct. Pick by association, not
// by prominence.
class DonkiCone {

    private static final String BASE = "https://kauai.ccmc.gsfc.nasa.gov/DONKI/WS/get/CMEAnalysis";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final double RSUN_KM = 695700;
    private static final double H21_5 = 21.5; // DONKI reports the time the leading edge passed this height

    record Fit(String associatedCmeStart, double latitude, double longitude, double halfAngle,
               double speed, long time215Milli, String technique, String note) {

        // Constant-speed kinematics off DONKI's one fixed point. Below 21.5 Rsun this is a
        // back-extrapolation through the acceleration phase, so it OVERESTIMATES the height;
        // treat it as a starting value for the Height spinner, not a measurement.
        double heightAt(long milli) {
            return heightAtRsun(speed, (time215Milli - milli) / 1000.);
        }

        @Override
        public String toString() {
            return String.format("%s  lon %+.0f°  lat %+.0f°  half-angle %.0f°  %.0f km/s  [%s]",
                    associatedCmeStart, longitude, latitude, halfAngle, speed, technique);
        }
    }

    // Height in Rsun `secondsBefore` seconds before the leading edge reached 21.5 Rsun.
    // Split out so the self-check can exercise it without a network call.
    static double heightAtRsun(double speedKmS, double secondsBefore) {
        return H21_5 - speedKmS * secondsBefore / RSUN_KM;
    }

    // NetClient refuses to run on the EDT, so this is a Callable for Task.submit.
    static Callable<List<Fit>> query(long startMilli, long endMilli) {
        return () -> {
            String url = BASE + "?startDate=" + DAY.format(Instant.ofEpochMilli(startMilli))
                    + "&endDate=" + DAY.format(Instant.ofEpochMilli(endMilli))
                    + "&mostAccurateOnly=true";
            try (NetClient nc = NetClient.of(new URI(url), true, NetClient.NetCache.NETWORK)) {
                if (!nc.isSuccessful())
                    throw new Exception("DONKI request failed: " + url);
                return parse(nc.getSource().readUtf8());
            }
        };
    }

    // Split from the fetch so the self-check can exercise it against a captured payload.
    static List<Fit> parse(String body) {
        List<Fit> fits = new ArrayList<>();
        JSONArray arr = new JSONArray(body);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            // A fit without a direction or an opening angle cannot be drawn; skip rather than
            // substituting a default that would look like data.
            if (o.isNull("longitude") || o.isNull("latitude") || o.isNull("halfAngle"))
                continue;
            fits.add(new Fit(
                    o.optString("associatedCMEstartTime", "?"),
                    o.getDouble("latitude"),
                    o.getDouble("longitude"),
                    o.getDouble("halfAngle"),
                    o.isNull("speed") ? 0 : o.getDouble("speed"),
                    parseTime(o.optString("time21_5", null)),
                    o.optString("measurementTechnique", "?"),
                    o.optString("note", "")));
        }
        return fits;
    }

    // DONKI stamps are "2021-10-28T23:32Z".
    private static long parseTime(String s) {
        try {
            return s == null ? 0 : Instant.parse(s.replace("Z", ":00Z")).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private DonkiCone() {}

}
