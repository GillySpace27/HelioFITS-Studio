package org.helioviewer.jhv.timelines;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;

import org.helioviewer.jhv.event.EventCache;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.timelines.band.BandReaderHapi;
import org.helioviewer.jhv.timelines.chart.PlotPanel;
import org.helioviewer.jhv.timelines.draw.DrawController;
import org.helioviewer.jhv.timelines.gui.TimelineDialog;
import org.helioviewer.jhv.timelines.gui.TimelinePanel;
import org.helioviewer.jhv.timelines.radio.RadioData;

import org.json.JSONObject;

public class Timelines implements Interfaces.MainContentPanelPlugin {

    private static final TimelineLayers layers = new TimelineLayers();
    public static final DrawController dc = new DrawController(); // sucks
    public static final TimelineDialog td = new TimelineDialog(layers);
    private final List<JComponent> pluginPanes = new ArrayList<>();
    private final PlotPanel plotOne = new PlotPanel();
    private static final TimelinePanel timelinePanel = new TimelinePanel(layers);

    public Timelines() {
        layers.add(new RadioData(null));
        layers.add(new CoverageTimelineLayer()); // per-image-layer frame coverage track
    }

    public static TimelineLayers getLayers() {
        return layers;
    }

    public static void requestCatalog() {
        BandReaderHapi.requestCatalog(Timelines::catalogsLoaded);
    }

    private static void catalogsLoaded(BandReaderHapi.CatalogData catalogData) {
        td.setCatalogs(catalogData.datasets());
        TimelineLayers.fetchBands();
        timelinePanel.setPredefinedGroups(catalogData.predefinedGroups());
    }

    public void installTimelines() {
        pluginPanes.add(plotOne);
        // As SWEKPlugin: registered rather than added, so it can be reordered, sent to the other
        // sidebar and popped out like every other section.
        org.helioviewer.jhv.gui.component.LeftSidebar.register("Timeline Layers", Buttons.timeline, timelinePanel);
        MainFrame.getLeftContentPane().revalidate();
        MainFrame.getMainContentPanel().addPlugin(this);

        Player.addTimeListener(dc);
        EventCache.addHighlightListener(dc);
    }

    public void uninstallTimelines() {
        EventCache.removeHighlightListener(dc);
        Player.removeTimeListener(dc);

        MainFrame.getMainContentPanel().removePlugin(this);
        MainFrame.getLeftContentPane().remove(timelinePanel);
        MainFrame.getLeftContentPane().revalidate();
        pluginPanes.remove(plotOne);
    }

    @Override
    public String getTabName() {
        return "Timelines";
    }

    @Override
    public List<JComponent> getVisualInterfaces() {
        return pluginPanes;
    }

    public static void saveState(JSONObject jo) {
        DrawController.saveState(jo);
    }

    public static void loadState(JSONObject jo) {
        DrawController.loadState(jo);
    }

}
