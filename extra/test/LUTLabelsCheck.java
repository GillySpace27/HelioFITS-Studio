package org.helioviewer.jhv.metadata;

import java.util.List;

import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.image.lut.LUTLabels;

// Standalone self-check (no test framework in this repo -- see extra/test/JHVMetadataDump.java for
// the pattern). Confirms the categorical-legend sidecar parses, that a LUT named in it is treated
// as categorical, that every label's indices exist in the colour table, and -- the property the
// whole design rests on -- that a LUT absent from the sidecar stays continuous.
public final class LUTLabelsCheck {

    private static final String CATEGORICAL = "CH/Polarity Legend";

    public static void main(String[] args) {
        List<LUTLabels.Group> groups = LUTLabels.get(CATEGORICAL);
        assertTrue(groups != null, "'" + CATEGORICAL + "' should be categorical (present in lut-labels.json)");
        assertTrue(groups.size() == 8, "expected 8 legend groups (traced solar features only), got " + groups.size());

        // Prose keys are documentation, not data: they must never surface as a legend.
        assertTrue(LUTLabels.get("_about") == null, "keys starting with '_' must be ignored");

        // A LUT with no sidecar entry must stay continuous, or every existing colour table would
        // suddenly render as a categorical legend.
        assertTrue(LUTLabels.get(LUT.gray().name()) == null,
                "'" + LUT.gray().name() + "' has no sidecar entry and must stay continuous");
        assertTrue(LUTLabels.get("no such LUT") == null, "unknown LUT must stay continuous");

        // isCategorical() is the gate the rendering panel (Levels/Sharpen/Filter/...) and the
        // shader (dither, texture filter) key off -- it must track the LUT actually in use, not
        // the FITS product, so switching a layer's colour table away from a categorical one hands
        // those controls back.
        assertTrue(LUTLabels.isCategorical(LUT.get(CATEGORICAL)), "'" + CATEGORICAL + "' LUT should read as categorical");
        assertTrue(!LUTLabels.isCategorical(LUT.gray()), "'" + LUT.gray().name() + "' LUT should read as continuous");

        // Every referenced index must be addressable in the 256-entry table, else the legend would
        // read colours that do not exist.
        int entries = LUT.get(CATEGORICAL).lut8().length;
        for (LUTLabels.Group g : groups) {
            assertTrue(!g.label().isBlank(), "group has a blank label");
            assertTrue(g.indices().length > 0, "group '" + g.label() + "' has no indices");
            for (int i : g.indices())
                assertTrue(i >= 0 && i < entries,
                        "group '" + g.label() + "' index " + i + " outside 0.." + (entries - 1));
        }

        // Spot-check the transcription against "20 in Color Table.docx".
        assertLabel(groups, 0, "+ CH", new int[]{2, 3});
        assertLabel(groups, 2, "- CH", new int[]{5, 6});
        assertLabel(groups, 4, "Neutral line", new int[]{8});
        assertLabel(groups, 6, "Sun Spots", new int[]{10});
        assertLabel(groups, 7, "Plage centers", new int[]{13});

        System.out.println("LUTLabelsCheck: PASS");
    }

    private static void assertLabel(List<LUTLabels.Group> groups, int pos, String label, int[] indices) {
        LUTLabels.Group g = groups.get(pos);
        assertTrue(label.equals(g.label()), "group " + pos + ": expected label '" + label + "', got '" + g.label() + "'");
        assertTrue(java.util.Arrays.equals(indices, g.indices()),
                "group '" + label + "': expected indices " + java.util.Arrays.toString(indices)
                        + ", got " + java.util.Arrays.toString(g.indices()));
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond)
            throw new AssertionError(message);
    }
}
