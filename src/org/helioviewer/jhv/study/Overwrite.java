package org.helioviewer.jhv.study;

/** What a run does about files that already exist; the job file spells them lowercase with hyphens. */
public enum Overwrite {
    ALWAYS("always"), SKIP_IF_UNCHANGED("skip-if-unchanged"), NEVER("never");

    public final String json;

    Overwrite(String json) {
        this.json = json;
    }

    public static Overwrite parse(String s) {
        for (Overwrite o : values())
            if (o.json.equals(s))
                return o;
        return SKIP_IF_UNCHANGED; // the documented default, also for an absent key
    }
}
