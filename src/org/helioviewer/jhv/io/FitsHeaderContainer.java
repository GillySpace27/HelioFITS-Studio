package org.helioviewer.jhv.io;

import java.util.Optional;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.metadata.MetaDataContainer;

import nom.tam.fits.Header;

/**
 * A nom-tam FITS header, as something {@link org.helioviewer.jhv.metadata.FitsMetaData} can read.
 *
 * <p>Twenty lines that buy the whole of the application's header knowledge. The alternative is a
 * second reader, and the second reader is always wrong in the same way: my first throwaway scan of
 * the cache reported no observation time for either LASCO dataset, because SOHO writes
 * {@code DATE-OBS = '2025/09/19'} and puts the time in a separate {@code TIME-OBS} card. FitsMetaData
 * has known that for years, along with whatever the next instrument does differently.
 *
 * <p>Blank is absent. FITS pads string values to eight characters, so a card written as {@code ' '}
 * comes back as spaces rather than as nothing, and a caller asking Optional-shaped questions wants
 * those to be empty rather than to be a value made of whitespace.
 */
public final class FitsHeaderContainer implements MetaDataContainer {

    private final Header header;

    public FitsHeaderContainer(Header _header) {
        header = _header;
    }

    @Nonnull
    @Override
    public Optional<String> getString(String key) {
        String value = header.getStringValue(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    @Nonnull
    @Override
    public Optional<Long> getLong(String key) {
        return header.containsKey(key) ? Optional.of(header.getLongValue(key)) : Optional.empty();
    }

    @Nonnull
    @Override
    public Optional<Double> getDouble(String key) {
        return header.containsKey(key) ? Optional.of(header.getDoubleValue(key)) : Optional.empty();
    }

    @Nonnull
    @Override
    public String getRequiredString(String key) {
        return getString(key).orElseThrow(() -> new IllegalArgumentException("Missing required key: " + key));
    }

    @Override
    public long getRequiredLong(String key) {
        return getLong(key).orElseThrow(() -> new IllegalArgumentException("Missing required key: " + key));
    }

    @Override
    public double getRequiredDouble(String key) {
        return getDouble(key).orElseThrow(() -> new IllegalArgumentException("Missing required key: " + key));
    }

}
