package org.helioviewer.jhv.gui.component;

import java.awt.Component;
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
        add(title, managed, startExpanded, sectionIcon, prefKey, -1);
    }

    /**
     * @param index where to put it among the sections already here, or -1 for the end.
     *
     * <p>Sections are laid out in the order they were added (the constraints set no gridy), so an
     * index is simply a child index and putting one back where it came from is an insert rather
     * than a rebuild. That matters because this pane is shared: the plugins add their own sections
     * to it, and a palette popping back in must not disturb them or be moved below them.
     */
    public void add(String title, JComponent managed, boolean startExpanded, @Nullable Icon sectionIcon, @Nullable String prefKey, int index) {
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
        if (index < 0 || index > getComponentCount())
            add(newPane, c);
        else
            add(newPane, c, index);

        c.weighty = 1;
        add(dummy, c);
    }

    /** Where a section currently sits, or -1 if it is not here. The number to hand back to add(). */
    public int indexOf(JComponent managed) {
        CollapsiblePane pane = map.get(managed);
        if (pane == null)
            return -1;
        Component[] children = getComponents();
        for (int i = 0; i < children.length; i++)
            if (children[i] == pane)
                return i;
        return -1;
    }

    /** Put controls in line with a section's title, at the trailing end of its header band. */
    public void setAccessory(JComponent managed, @Nullable JComponent accessory) {
        CollapsiblePane pane = map.get(managed);
        if (pane != null)
            pane.setAccessory(accessory);
    }

    /**
     * Move a section one place up or down among the sections here.
     *
     * <p>Past whatever is next to it, a plugin's section included. This pane is shared and the
     * plugins add to it, so "up" cannot mean "up among the palettes only" without leaving a section
     * that refuses to pass the one above it for reasons nothing on screen explains.
     */
    public void move(JComponent managed, int delta) {
        CollapsiblePane pane = map.get(managed);
        int from = indexOf(managed);
        if (pane == null || from < 0)
            return;
        int sections = getComponentCount() - 1; // the trailing strut is not one of them
        int to = Math.clamp(from + delta, 0, sections - 1);
        if (to == from)
            return;
        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.weighty = 0;
        c.gridx = 0;
        c.gridwidth = 1;
        c.anchor = GridBagConstraints.PAGE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        super.remove(pane);
        add(pane, c, to);
        revalidate();
        repaint();
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
