package org.helioviewer.jhv.view.uri;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A tile-compressed float image that is not dithered (ZQUANTIZ = NO_DITHER) decodes to
 * i * ZSCALE + ZZERO, as astropy and cfitsio give it. nom-tam-fits 1.22.0 adds half a quantum;
 * FITSImage.removeHalfQuantum takes it off again. Reference pixels in
 * extra/test/data/decode-quantum-reference.json come from astropy, not from this code.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.view.uri.FitsQuantumCheck
 */
public final class FitsQuantumCheck {

    public static void main(String[] args) throws Exception {
        Path data = Path.of("extra/test/data");
        JSONObject ref = new JSONObject(Files.readString(data.resolve("decode-quantum-reference.json")));
        double tol = ref.getDouble("tolerance");
        FITSData d = FITSImage.readData(new File(data.toFile(), ref.getString("file")), 0);
        float[] v = FitsDecodeGoldenCheck.physical(d);
        JSONArray px = ref.getJSONArray("pixels");
        int failures = 0;
        double worst = 0;
        for (int k = 0; k < px.length(); k++) {
            JSONObject p = px.getJSONObject(k);
            double diff = v[p.getInt("y") * d.width() + p.getInt("x")] - p.getDouble("value");
            worst = Math.max(worst, Math.abs(diff));
            if (Math.abs(diff) > tol)
                failures++;
        }
        System.out.printf("  %s astropy reference pixels within %.1e (worst %.2e of %d)%n", failures == 0 ? "ok  " : "FAIL", tol, worst, px.length());
        if (failures > 0)
            System.exit(1);
    }
}
