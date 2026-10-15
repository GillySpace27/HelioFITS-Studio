package org.helioviewer.jhv.gui.dialog;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Plain-language names for the variants an archive returns under one dataset, for the SOAR and
 * ASPIICS dialogs. Pure string work, so ProductVariantsCheck can pin it without a window.
 */
final class ProductVariants {

    private ProductVariants() {
    }

    // SOAR (Solar Orbiter EUI FSI). Meanings from the EUI Data Manual (EDAM, draft of 2026-06-10,
    // https://www.sidc.be/EUI/data/documents/EUI_Data_Manual.pdf), section 1.4.1, file names:
    // "image" is "a regular image of the Sun"; "image-short" has "a typically 50x reduced exposure
    // time"; "image-occulter" are "FSI images with the FSI door in coronagraph mode such that the
    // solar disc is occulted". Section 1.5: since July 2022 short exposure (0.2 s) images are added
    // to the synoptics "to better capture saturated areas". The Metadata Standard
    // (SP_ROB_SOEUI_19002 2.0) lists the same file-name types without defining them.
    private static final Pattern FSI = Pattern.compile("eui-fsi\\d+-image(-short|-occulter)?");

    /** The SOAR descriptor of an id such as solo_L2_eui-fsi174-image_20211227T173845330, or "". */
    static String soarDescriptor(String id) {
        String[] parts = id.split("_");
        return parts.length > 3 ? parts[2] : "";
    }

    /** The FSI regular image, preselected over the short-exposure and occulted variants. */
    static boolean soarIsStandard(String descriptor) {
        return descriptor.startsWith("eui-fsi") && descriptor.endsWith("-image");
    }

    static String soarLabel(String descriptor) {
        if (!FSI.matcher(descriptor).matches())
            return descriptor;
        String kind = descriptor.endsWith("-short") ? "Short exposure"
                : descriptor.endsWith("-occulter") ? "Disc occulted (coronagraph mode)"
                : "Standard image";
        return kind + " (" + descriptor + ')';
    }

    static String soarTip(String descriptor) {
        if (!FSI.matcher(descriptor).matches())
            return null;
        if (descriptor.endsWith("-short"))
            return "About 50 times shorter exposure than the regular image, to capture areas that saturate it (EUI Data Manual 1.4.1, 1.5)";
        if (descriptor.endsWith("-occulter"))
            return "The FSI door in coronagraph mode blocks the solar disc, so only the corona is seen (EUI Data Manual 1.4.1)";
        return "A regular full-disc image of the Sun; the usual choice for a movie (EUI Data Manual 1.4.1)";
    }

    // ASPIICS L3. Product meanings from the ASPIICS data page (https://www.sidc.be/proba-3/aspiics-data,
    // read 2026-10-10), which lists the L3 products; the two-letter codes are the archive's file-name
    // prefixes, matched to those products by their initials.
    static String aspiicsProductTip(String code) {
        return switch (code) {
            case "bt" -> "Total brightness of the K corona, F corona removed";
            case "pb" -> "Polarised brightness";
            case "pa" -> "Polarisation angle";
            case "fe" -> "Fe XIV green line image, K and F corona continuum removed";
            case "he" -> "He I D3 line image, K and F corona continuum removed";
            default -> null;
        };
    }

    // Lower-case 't': aspiicsKind lowercases the name before matching.
    private static final Pattern STAMP = Pattern.compile("\\d{8}t\\d{6}\\d*");

    /**
     * What tells files of one product apart: the file name without directory, the
     * aspiics_&lt;product&gt;_l3_ prefix, time stamps and extension. Frames of one kind share it.
     */
    static String aspiicsKind(String datalocation) {
        String name = datalocation.substring(datalocation.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int l3 = name.indexOf("_l3_");
        if (l3 >= 0)
            name = name.substring(l3 + 4);
        if (name.endsWith(".gz"))
            name = name.substring(0, name.length() - 3);
        int dot = name.lastIndexOf('.');
        if (dot >= 0)
            name = name.substring(0, dot);
        StringBuilder kind = new StringBuilder();
        for (String token : name.split("_")) {
            if (token.isEmpty() || STAMP.matcher(token).matches())
                continue;
            if (!kind.isEmpty())
                kind.append('_');
            kind.append(token);
        }
        return kind.toString();
    }

    /** Frames per kind, in name order. */
    static Map<String, Integer> countKinds(List<String> datalocations) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String datalocation : datalocations)
            counts.merge(aspiicsKind(datalocation), 1, Integer::sum);
        return counts;
    }

    /**
     * The kind with the most frames, the continuous sequence a movie wants; a tie goes to the first
     * name in order. Null when there is none.
     */
    static String preferredKind(Map<String, Integer> counts) {
        String best = null;
        int most = 0;
        for (Map.Entry<String, Integer> e : new TreeMap<>(counts).entrySet()) {
            if (e.getValue() > most) {
                best = e.getKey();
                most = e.getValue();
            }
        }
        return best;
    }
}
