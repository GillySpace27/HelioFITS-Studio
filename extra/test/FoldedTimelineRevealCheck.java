package org.helioviewer.jhv.gui.component;

import java.util.List;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.Interfaces;

/**
 * A timeline pane that was folded at launch draws when it is unfolded.
 *
 * <p>addPlugin hides the plot when the pane starts folded. Unfolding then made the pane's contents
 * visible before updateLayout put the plot into it, so the plot went in still hidden: an empty
 * panel, with every timeline layer added afterwards listed in the sidebar and drawn nowhere. The
 * plot's own setVisible(false) also stops DrawController, so nothing redrew it either.
 *
 * <p>Both ways in are checked: revealPlugins (the toolbar's Timelines button and Animate), which
 * had the bug, and a click on the pane's own header, which did not (Swing runs the later-added
 * updateLayout listener first) and is the order revealPlugins now follows.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.FoldedTimelineRevealCheck
 */
public final class FoldedTimelineRevealCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static JPanel launchFolded(MainContentPanel[] out) {
        // In memory only: Settings never writes from a process that has not loaded the file.
        Settings.setProperty("ui.section.Plugins", "false");
        Settings.setProperty("display.plugins", "false");
        MainContentPanel panel = new MainContentPanel(new JPanel());
        JPanel plot = new JPanel();
        JLabel inner = new JLabel("plot");
        plot.add(inner);
        panel.addPlugin(new Interfaces.MainContentPanelPlugin() {
            @Override
            public String getTabName() {
                return "Timelines";
            }

            @Override
            public List<JComponent> getVisualInterfaces() {
                return List.of(plot);
            }
        });
        out[0] = panel;
        return plot;
    }

    private static CollapsiblePane header(MainContentPanel panel) throws ReflectiveOperationException {
        java.lang.reflect.Field f = MainContentPanel.class.getDeclaredField("collapsiblePane");
        f.setAccessible(true);
        return (CollapsiblePane) f.get(panel);
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        MainContentPanel[] panel = new MainContentPanel[1];

        JPanel plot = launchFolded(panel);
        expect("folded at launch, the plot is hidden", !plot.isVisible());
        panel[0].revealPlugins();
        expect("revealPlugins shows the plot", panel[0].isPluginsShowing() && plot.isVisible());
        expect("and what is inside it", plot.getComponent(0).isVisible());

        plot = launchFolded(panel);
        panel[0].setPluginsShowing(true);
        expect("the toolbar's Timelines toggle shows the plot", plot.isVisible());

        plot = launchFolded(panel);
        header(panel[0]).toggleButton.doClick(0);
        expect("a click on the folded header shows the plot", panel[0].isPluginsShowing() && plot.isVisible());
        header(panel[0]).toggleButton.doClick(0);
        expect("and a second click folds it again", !panel[0].isPluginsShowing() && !plot.isVisible());

        System.out.println(failures == 0 ? "FoldedTimelineRevealCheck: all ok" : "FoldedTimelineRevealCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

}
