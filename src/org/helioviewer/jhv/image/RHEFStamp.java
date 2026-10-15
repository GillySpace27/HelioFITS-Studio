package org.helioviewer.jhv.image;

import java.math.BigDecimal;
import java.util.List;

/**
 * The RHEF stamp line (RH-11): which RHEF made an image, in the format of fastRHEF
 * conventions/RHEF-SPEC.md section 5 (public copy: gilly.space heliosoftware/spec/rhef/conventions.md):
 * {@code RHEF <convention> via <implementation> <version>; upsilon=<lo>,<hi>; deviations=<ID>,<ID>}.
 *
 * <p>The ids are the registry row hfstudio-java in fastRHEF conventions/implementations.json, whose
 * deviations repeat the RHEF-CONVENTION header line of FilterRHEF.java; RHEFStampCheck pins the two
 * together. That row ranks; Upsilon is applied by the shader (row hfstudio-glsl), which is what its
 * UPS-EXT deviation says, and the upsilon pair here is the layer's own setting.
 * RHEF output is a visualization, not a calibrated radiance.
 */
public final class RHEFStamp {

    public static final String CONVENTION = "oRHEF-2.0";
    public static final String IMPLEMENTATION = "hfstudio-java";
    public static final List<String> DEVIATIONS = List.of("RANK-N1", "POS-ONLY", "FP16", "MIN-BIN", "GEOM-PX", "UPS-EXT", "DTYPE-IN");

    /** The stamp for one RHEF layer; a version that is blank or has a space is written "unknown". */
    public static String line(String version, double upsilonLow, double upsilonHigh) {
        String v = version == null || version.isBlank() || version.chars().anyMatch(Character::isWhitespace) ? "unknown" : version;
        return "RHEF " + CONVENTION + " via " + IMPLEMENTATION + ' ' + v + "; upsilon=" + number(upsilonLow) + ',' + number(upsilonHigh)
                + "; deviations=" + (DEVIATIONS.isEmpty() ? "none" : String.join(",", DEVIATIONS));
    }

    /** Shortest decimal with no trailing zeros, as the spec names for Java. */
    static String number(double x) {
        return BigDecimal.valueOf(x).stripTrailingZeros().toPlainString();
    }

    private RHEFStamp() {}
}
