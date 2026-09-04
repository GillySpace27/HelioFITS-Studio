package org.helioviewer.jhv.metadata;

import java.io.IOException;

import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.time.JHVTime;

public final class HeliocentricCartesianMetaData {

    public interface Source {
        boolean contains(String key);

        String getString(String key) throws IOException;

        double getDouble(String key) throws IOException;

        IOException error(String message);
    }

    public static JHVTime observationTime(Source source) throws IOException {
        String value = source.getString("DATE-OBS");
        try {
            return new JHVTime(value);
        } catch (RuntimeException e) {
            throw source.error("invalid DATE-OBS: " + value);
        }
    }

    public static Quat observerRotation(Source source) throws IOException {
        require(source, "WCSNAME", "Heliocentric-cartesian");
        require(source, "CTYPE1", "SOLX");
        require(source, "CTYPE2", "SOLY");
        require(source, "CTYPE3", "SOLZ");
        require(source, "CUNIT1", "solRad");
        require(source, "CUNIT2", "solRad");
        require(source, "CUNIT3", "solRad");
        if (source.getDouble("RSUN_REF") != Sun.RadiusMeter)
            throw source.error("RSUN_REF must be " + Sun.RadiusMeter);
        if (source.contains("DSUN_OBS") && source.getDouble("DSUN_OBS") <= 0)
            throw source.error("DSUN_OBS must be positive");

        double longitude = source.getDouble("CRLN_OBS");
        double latitude = source.getDouble("CRLT_OBS");
        if (Math.abs(latitude) > 90)
            throw source.error("CRLT_OBS must be between -90 and 90 degrees");
        return Quat.createXY(Math.toRadians(latitude), -Math.toRadians(longitude));
    }

    private static void require(Source source, String key, String expected) throws IOException {
        if (!source.getString(key).equals(expected))
            throw source.error(key + " must be " + expected);
    }

    private HeliocentricCartesianMetaData() {}
}
