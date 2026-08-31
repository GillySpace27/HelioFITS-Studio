package org.helioviewer.jhv.io;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.time.TimeUtils;

import org.json.JSONObject;

/**
 * A re-issuable query for native FITS, and the FITS counterpart to {@link APIRequest}.
 *
 * <p>This exists because of what a FITS layer used to be: a frozen {@code List<URI>}. Both
 * {@code ImageLayers.syncLayersSpan} and {@code refreshLayersSpan} begin by asking a layer for its
 * APIRequest and skipping it when there is none, so every FITS layer was silently passed over and
 * never followed the date. The URIs were the only record of where they came from, and a list of
 * files cannot be asked for a different time range.
 *
 * <p>PunchClient already had this record in all but name, as a {@code QueryState} in a WeakHashMap
 * keyed by layer, used solely for its own "check missing frames" action. It was never serialized
 * and nothing else could see it. Promoting it to a real field on the layer is what lets a FITS
 * layer behave like a JP2 one.
 *
 * @param archive which archive answers this query
 * @param level   product level, archive-specific ("3" for PUNCH, "LL" / "L1" elsewhere)
 * @param product product or detector code within the level ("CAM", "PTM", "C2", "C3")
 * @param version pipeline version, or {@link PunchClient#LATEST_VERSION} for newest present
 * @param cadence minimum spacing in milliseconds, 0 meaning every frame in range
 */
public record FitsRequest(@Nonnull Archive archive, @Nonnull String level, @Nonnull String product,
                          @Nonnull String version, long cadence, long startTime, long endTime) {

    /**
     * Where the FITS comes from. VSO is the federated route and covers most missions through one
     * query; a native archive is for the ones VSO serves badly or not at all, PUNCH being the
     * case in hand. The distinction is user-visible on purpose: they are different products with
     * different latency and different calibration, not two spellings of one thing.
     */
    public enum Archive {
        PUNCH("PUNCH (SDAC)"),
        VSO("VSO");

        private final String label;

        Archive(String _label) {
            label = _label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public FitsRequest {
        if (endTime < startTime)
            endTime = startTime;
    }

    /** The same query over a new span, which is the whole reason this is a record and not a list. */
    public FitsRequest withSpan(long start, long end) {
        return new FitsRequest(archive, level, product, version, cadence, start, end);
    }

    public JSONObject toJson() {
        JSONObject jo = new JSONObject();
        jo.put("archive", archive.name());
        jo.put("level", level);
        jo.put("product", product);
        jo.put("version", version);
        jo.put("cadence", cadence);
        jo.put("startTime", TimeUtils.format(startTime));
        jo.put("endTime", TimeUtils.format(endTime));
        return jo;
    }

    public static FitsRequest fromJson(JSONObject jo) {
        Archive _archive;
        try {
            _archive = Archive.valueOf(jo.optString("archive", Archive.PUNCH.name()));
        } catch (RuntimeException ignore) {
            _archive = Archive.PUNCH;
        }
        long now = System.currentTimeMillis();
        return new FitsRequest(_archive,
                jo.optString("level", "3"),
                jo.optString("product", ""),
                jo.optString("version", PunchClient.LATEST_VERSION),
                jo.optLong("cadence", 0),
                TimeUtils.optParse(jo.optString("startTime"), now - TimeUtils.DAY_IN_MILLIS),
                TimeUtils.optParse(jo.optString("endTime"), now));
    }

}
