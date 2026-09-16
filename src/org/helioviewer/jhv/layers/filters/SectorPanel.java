package org.helioviewer.jhv.layers.filters;

import org.helioviewer.jhv.image.ImageDisplaySettings;
import org.helioviewer.jhv.layers.ImageLayer;

public final class SectorPanel {

    private final FilterDetails directionDetails;
    private final FilterDetails widthDetails;
    private int direction;
    private int width;

    public SectorPanel(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        direction = (int) Math.round(settings.getSectorCenter());
        width = (int) Math.round(settings.getSectorWidth());
        directionDetails = SliderFilterPanel.create("Sector", -180, 180, direction, SectorPanel::formatDegree, value -> {
            direction = value;
            settings.setSector(direction, width);
        });
        widthDetails = SliderFilterPanel.create("Opening", 0, 360, width, SectorPanel::formatDegree, value -> {
            width = value;
            settings.setSector(direction, width);
        });
    }

    public FilterDetails getDirectionDetails() {
        return directionDetails;
    }

    public FilterDetails getWidthDetails() {
        return widthDetails;
    }

    public void setVisible(boolean visible) {
        directionDetails.setVisible(visible);
        widthDetails.setVisible(visible);
    }

    private static String formatDegree(int angle) {
        return angle + "°";
    }

}
