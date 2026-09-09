package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;

import org.helioviewer.jhv.gui.MainFrame;

/**
 * The left sidebar as a home for palettes: the four panes it starts with can be popped out into
 * windows, and any palette can be dropped back into it.
 *
 * <p>The right sidebar owns its own scroller, handle and width. This one does not own anything: it
 * is a thin cover over the pane MainFrame already built, which is why it is a separate class rather
 * than a second instance of {@link RightSidebar}. The difference that follows is worth stating.
 * That pane is SHARED. The plugins add their own sections to it directly (Timeline Layers, the
 * Space Weather Event Knowledgebase), so this must never rebuild the stack: a section pops out with
 * its position remembered and is inserted back at that index, leaving everything around it alone.
 *
 * <p>No reorder arrows here, for the same reason. Moving one of these would mean deciding where the
 * plugins' sections go, which is a question the plugins currently answer by adding last.
 */
public final class LeftSidebar implements SectionHost {

    @Nullable
    private static LeftSidebar instance;

    public static LeftSidebar getInstance() {
        if (instance == null)
            instance = new LeftSidebar();
        return instance;
    }

    private record Section(String title, @Nullable Icon icon, Component content, JComponent holder) {}

    private final Map<String, Section> sections = new LinkedHashMap<>();
    /** Where each section sat when it was last taken out, so it goes back there and not to the end. */
    private final Map<String, Integer> lastIndex = new LinkedHashMap<>();

    private LeftSidebar() {}

    @Override
    public String hostName() {
        return "left";
    }

    /**
     * Put a pane in the left sidebar and give it a palette, so it can be popped out later.
     *
     * <p>Called instead of adding to the pane directly. The palette is what carries the pop-out and
     * remembers where the pane lives across launches; a pane registered here that the user last
     * left floating comes back floating, which is why this defers to the stored home rather than
     * simply docking.
     */
    public static Palette register(String title, @Nullable Icon icon, Component content) {
        Palette palette = new Palette(title, () -> content, () -> {});
        palette.setIcon(icon);
        palette.restoreHome(getInstance());
        return palette;
    }

    @Override
    public void addSection(String title, @Nullable Icon icon, Component content, Runnable onFloat) {
        SideContentPane pane = MainFrame.getLeftContentPane();
        if (pane == null)
            return;
        if (sections.containsKey(title))
            removeSection(title);
        JComponent holder = buildSection(title, content, onFloat);
        sections.put(title, new Section(title, icon, content, holder));
        pane.add(title, holder, true, icon, title, insertIndex(lastIndex.get(title), pane.getComponentCount()));
        pane.revalidate();
    }

    /**
     * The pop-out control, above the section's own content.
     *
     * <p>Above rather than in the header, and at the leading edge, for the two reasons the right
     * sidebar's row carries: a trailing button inside a CollapsiblePane's header sits outside the
     * toggle that paints the header's coloured bar and leaves a notch of window background in it,
     * and anything right-aligned in a row as wide as the content is the first thing pushed out of
     * a narrow sidebar.
     */
    private static JComponent buildSection(String title, Component content, Runnable onFloat) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        bar.setOpaque(false);
        JButton floatOut = Buttons.flat(Buttons.popOut);
        floatOut.setToolTipText("Pop " + title + " out into a floating palette");
        floatOut.addActionListener(e -> onFloat.run());
        bar.add(floatOut);
        holder.add(bar, BorderLayout.PAGE_START);
        holder.add(content, BorderLayout.CENTER);
        return holder;
    }

    @Override
    public void removeSection(String title) {
        Section section = sections.remove(title);
        if (section == null)
            return;
        SideContentPane pane = MainFrame.getLeftContentPane();
        if (pane == null)
            return;
        int index = pane.indexOf(section.holder());
        if (index >= 0)
            lastIndex.put(title, index);
        pane.remove(section.holder());
        pane.revalidate();
        pane.repaint();
    }

    @Override
    public boolean hasSection(String title) {
        return sections.containsKey(title);
    }

    @Override
    public void reveal(String title) {
        Section section = sections.get(title);
        if (section == null)
            return;
        SideContentPane pane = MainFrame.getLeftContentPane();
        if (pane == null)
            return;
        // Asked for only when it is actually folded away. Docking reveals, and docking happens
        // while MainFrame is still assembling itself, before the parts setSidebarCollapsed writes
        // to exist.
        if (MainFrame.isSidebarCollapsed())
            MainFrame.setSidebarCollapsed(false);
        pane.setExpanded(section.holder(), true);
        pane.revalidate();
    }

    /** The sections this holds, top to bottom, for a check and for anything reporting the state. */
    public java.util.List<String> sectionTitles() {
        return java.util.List.copyOf(sections.keySet());
    }

    /**
     * Where a section popped out of, so it can be put back there. Pure, for LeftSidebarIndexCheck.
     *
     * @param remembered the index it was last seen at, or null if it has never been here
     * @param count      how many children the pane has now
     */
    static int insertIndex(@Nullable Integer remembered, int count) {
        return remembered == null || remembered > count ? -1 : remembered;
    }

}
