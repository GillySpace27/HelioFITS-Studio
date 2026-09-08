package org.helioviewer.jhv.gui.component;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.HashMap;

import javax.annotation.Nullable;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;

// Panel managing multiple CollapsiblePanes
// This panel hides the use of the CollapsiblePane and allows accessing
// the children of the CollapsiblePane directly.
@SuppressWarnings("serial")
public final class SideContentPane extends JComponent {

    private final HashMap<JComponent, CollapsiblePane> map = new HashMap<>();
    private final JPanel dummy = new JPanel();

    public SideContentPane() {
        setLayout(new GridBagLayout());
        dummy.setOpaque(false);
        add(dummy);
    }

    public void add(String title, JComponent managed, boolean startExpanded) {
        add(title, managed, startExpanded, null);
    }

    /** @param sectionIcon a glyph for what the section holds, or null where no existing icon says it */
    public void add(String title, JComponent managed, boolean startExpanded, @Nullable Icon sectionIcon) {
        add(title, managed, startExpanded, sectionIcon, null);
    }

    /** @param prefKey what to remember expansion under, where the title is not unique in the window */
    public void add(String title, JComponent managed, boolean startExpanded, @Nullable Icon sectionIcon, @Nullable String prefKey) {
        remove(dummy);

        CollapsiblePane newPane = new CollapsiblePane(title, managed, startExpanded, false, sectionIcon, prefKey);
        map.put(managed, newPane);

        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.weighty = 0;
        c.gridx = 0;
        c.gridwidth = 1;
        c.anchor = GridBagConstraints.PAGE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        add(newPane, c);

        c.weighty = 1;
        add(dummy, c);
    }

    /** Expand or collapse one section, addressed by the component that was added. */
    public void setExpanded(JComponent managed, boolean expanded) {
        CollapsiblePane pane = map.get(managed);
        if (pane != null)
            pane.setExpanded(expanded);
    }

    public void expandAll() {
        for (CollapsiblePane pane : map.values())
            pane.setExpanded(true);
        revalidate();
        repaint();
    }

    /** Each section back to how it was left, collapsed when it never was. */
    public void restoreExpansion() {
        for (CollapsiblePane pane : map.values())
            pane.setExpanded(pane.remembered(false));
        revalidate();
        repaint();
    }

    public void collapseAll() {
        for (CollapsiblePane pane : map.values())
            pane.setExpanded(false);
        revalidate();
        repaint();
    }

    public void remove(JComponent component) {
        CollapsiblePane pane = map.remove(component);
        if (pane != null)
            super.remove(pane);
        else
            super.remove(component);
    }

}
