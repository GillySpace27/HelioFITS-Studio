package org.helioviewer.jhv.layers.filters;

import org.helioviewer.jhv.image.ImageDisplaySettings;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;

public final class SectorPanel {

    private final FilterDetails directionDetails;
    private final FilterDetails widthDetails;

    // Through Layers.applyToSelected like every other row, which this one alone did not do: it
    // wrote to its own layer's settings, so with several layers selected the sector moved on the
    // one whose panel was showing and on none of the others. Each slider sets only its own half,
    // so turning the direction does not also stamp the shown layer's opening onto its peers.
    public SectorPanel(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        int direction = (int) Math.round(settings.getSectorCenter());
        int width = (int) Math.round(settings.getSectorWidth());
        directionDetails = SliderFilterPanel.create("Sector", -180, 180, direction, SectorPanel::formatDegree,
                value -> Layers.applyToSelected(layer, s -> s.setSector(value, s.getSectorWidth())));
        widthDetails = SliderFilterPanel.create("Opening", 0, 360, width, SectorPanel::formatDegree,
                value -> Layers.applyToSelected(layer, s -> s.setSector(s.getSectorCenter(), value)));
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
