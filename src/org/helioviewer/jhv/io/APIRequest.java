package org.helioviewer.jhv.io;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.time.TimeUtils;

import org.json.JSONObject;

public record APIRequest(@Nonnull String server, int sourceId, long startTime, long endTime, int cadence) {

    private static final long RANGE_EXPAND = 15 * TimeUtils.MINUTE_IN_MILLIS;
    public static final int CADENCE_ALL = -100;
    public static final int CADENCE_DEFAULT = 1800;
    public static final int CallistoID = 5000;

    public APIRequest {
        if (endTime < startTime)
            endTime = startTime;

        long expand = (RANGE_EXPAND - (endTime - startTime)) / 2;
        if (startTime != endTime && expand > 0) {
            startTime = startTime - expand;
            endTime = endTime + expand;
        }
    }

    public String toFileRequest() throws Exception {
        DataSources.Server source = DataSources.getServer(server);
        if (source == null)
            throw new Exception("Unknown server: " + server);

        String fileReq;
        if (startTime == endTime) {
            fileReq = source.jp2URL() + "sourceId=" + sourceId + "&date=" + TimeUtils.formatZ(startTime);
        } else {
            fileReq = source.jpxURL() + "sourceId=" + sourceId + "&startTime=" + TimeUtils.formatZ(startTime) + "&endTime=" + TimeUtils.formatZ(endTime);
            if (cadence != CADENCE_ALL)
                fileReq += "&cadence=" + cadence;
        }
        return fileReq;
    }

    public String toJpipRequest() throws Exception {
        String jsonReq = startTime == endTime ? "&json=true" : "&verbose=true&linked=true";
        return toFileRequest() + jsonReq + "&jpip=true";
    }

    // Helioviewer builds at most 1000 frames per movie and thins the cadence past that ("the
    // maximum of 1000 frames allowed per request", getJPX, 2026-10-02). ponytail: 900 leaves room
    // for the server's rounding at the ends; it is not a measured margin.
    private static final int FRAMES_PER_REQUEST = 900;

    /**
     * This request as consecutive pieces that each stay under the server's frame cap, split
     * evenly so no piece is a sliver (a short one would be widened by the constructor). "Get all"
     * stays whole: its frame count is the server's to know.
     */
    public java.util.List<APIRequest> chunks() {
        long span = endTime - startTime;
        long step = (long) cadence * 1000 * FRAMES_PER_REQUEST;
        if (cadence <= 0 || span <= step)
            return java.util.List.of(this);
        int n = (int) ((span + step - 1) / step);
        java.util.List<APIRequest> parts = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++)
            parts.add(new APIRequest(server, sourceId, startTime + span * i / n, startTime + span * (i + 1) / n, cadence));
        return parts;
    }

    public JSONObject toJson() {
        JSONObject jo = new JSONObject();
        jo.put("server", server);
        jo.put("sourceId", sourceId);
        jo.put("startTime", TimeUtils.format(startTime));
        jo.put("endTime", TimeUtils.format(endTime));
        jo.put("cadence", cadence);
        return jo;
    }

    public static APIRequest fromJson(JSONObject jo) {
        String _server = jo.optString("server", "");
        if (DataSources.getServer(_server) == null)
            _server = Settings.getProperty("dataSources.defaultServer");

        int _sourceId = jo.optInt("sourceId", 10);

        long t = System.currentTimeMillis();
        long _startTime = TimeUtils.optParse(jo.optString("startTime"), t - 2 * TimeUtils.DAY_IN_MILLIS);
        long _endTime = TimeUtils.optParse(jo.optString("endTime"), t);

        int _cadence = jo.optInt("cadence", TimeUtils.defaultCadence(_startTime, _endTime));
        return new APIRequest(_server, _sourceId, _startTime, _endTime, _cadence);
    }

    public static APIRequest fromRequestJson(JSONObject jo) throws Exception {
        long t = System.currentTimeMillis();
        long _startTime = TimeUtils.optParse(jo.optString("startTime"), t - 2 * TimeUtils.DAY_IN_MILLIS);
        long _endTime = TimeUtils.optParse(jo.optString("endTime"), t);
        int _cadence = jo.optInt("cadence", TimeUtils.defaultCadence(_startTime, _endTime));

        String observatory = jo.optString("observatory", "");
        String dataset = jo.getString("dataset");

        String _server = jo.optString("server", "");
        if (DataSources.getServer(_server) == null)
            _server = Settings.getProperty("dataSources.defaultServer");
        if (DataSources.getServer(_server) == null) // very unlikely
            throw new Exception("Unknown server");

        int _sourceId = DataSources.selectDataset(_server, observatory, dataset);
        if (_sourceId < 0)
            throw new Exception("Empty request result");

        return new APIRequest(_server, _sourceId, _startTime, _endTime, _cadence);
    }

}
