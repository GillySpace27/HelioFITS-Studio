package org.helioviewer.jhv.metadata;

import java.util.Map;
import java.util.Optional;

// Minimal MetaDataContainer backed by a plain Map, for standalone self-checks that construct
// synthetic FITS headers without opening a real file.
final class MapMetaDataContainer implements MetaDataContainer {

    private final Map<String, String> headers;

    MapMetaDataContainer(Map<String, String> _headers) {
        headers = _headers;
    }

    @Override
    public Optional<String> getString(String key) {
        return Optional.ofNullable(headers.get(key));
    }

    @Override
    public Optional<Double> getDouble(String key) {
        return getString(key).map(Double::parseDouble);
    }

    @Override
    public Optional<Long> getLong(String key) {
        return getString(key).map(Long::parseLong);
    }

    @Override
    public String getRequiredString(String key) {
        return getString(key).orElseThrow(() -> new RuntimeException("Missing required key: " + key));
    }

    @Override
    public double getRequiredDouble(String key) {
        return getDouble(key).orElseThrow(() -> new RuntimeException("Missing required key: " + key));
    }

    @Override
    public long getRequiredLong(String key) {
        return getLong(key).orElseThrow(() -> new RuntimeException("Missing required key: " + key));
    }
}
