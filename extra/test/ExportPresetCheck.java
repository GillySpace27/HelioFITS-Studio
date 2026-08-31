package org.helioviewer.jhv.movie;

import java.util.List;

/**
 * The preset ladder's invariants. Two of them are the kind that only bite later:
 *
 * <p>Every rung must name a combination its own codec actually supports, or picking that preset
 * hands ffmpeg a pixel format its encoder rejects at the very end of a long recording, which is
 * the worst possible moment to find out.
 *
 * <p>And no two rungs may describe the same settings. ExportPreset.matching maps settings back to
 * a name so the combo reads as a readout rather than a separate thing that can disagree with the
 * controls; with a duplicate, two names collapse to whichever comes first and one rung becomes
 * unreachable in the UI while still appearing in the list.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.movie.ExportPresetCheck
 */
public final class ExportPresetCheck {

    private static int failures;

    public static void main(String[] args) {
        List<ExportPreset> all = ExportPreset.all();
        expect(!all.isEmpty(), "there are built-in presets");

        for (ExportPreset p : all) {
            expect(p.format().supports(p.chroma(), p.depth()),
                    p.name() + ": " + p.format() + " cannot carry " + p.chroma() + " at " + p.depth());
            expect(!p.name().isBlank(), "a preset has no name");
            expect(!ExportPreset.CUSTOM.equals(p.name()), "a preset is named " + ExportPreset.CUSTOM);
            // The name says the destination, the description says the cost. A rung without the
            // second half is the one nobody can choose between.
            expect(p.description().length() > 40, p.name() + ": description too thin to distinguish it");

            ExportPreset found = ExportPreset.byName(p.name());
            expect(found != null && found.equals(p), p.name() + ": does not round-trip through byName");

            ExportPreset match = ExportPreset.matching(p.format(), p.chroma(), p.depth(), p.allIntra());
            expect(match != null, p.name() + ": its own settings match no preset");
        }

        for (int i = 0; i < all.size(); i++)
            for (int j = i + 1; j < all.size(); j++) {
                ExportPreset a = all.get(i), b = all.get(j);
                expect(!a.name().equals(b.name()), "duplicate preset name: " + a.name());
                expect(!(a.format() == b.format() && a.chroma() == b.chroma()
                                && a.depth() == b.depth() && a.allIntra() == b.allIntra()),
                        "\"" + a.name() + "\" and \"" + b.name() + "\" are the same settings");
            }

        // The ladder's top rung is the only one that can claim to be unaltered, so it has to be
        // the lossless codec in RGB at full depth. Anything else silently weakens the claim.
        ExportPreset top = all.getFirst();
        expect(top.format() == ExportFormat.FFV1 && top.chroma() == ExportFormat.Chroma.RGB
                        && top.depth() == ExportFormat.Depth.SIXTEEN,
                "the first rung must be the exact one, found " + top.name());

        expect(ExportPreset.isBuiltInName(top.name()), top.name() + " should be built in");
        expect(!ExportPreset.isBuiltInName("no such preset"), "an unknown name is not built in");

        if (failures != 0)
            throw new AssertionError(failures + " preset failure(s)");
        System.out.println("ExportPresetCheck: PASS");
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
