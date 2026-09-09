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
 * <p>The reorder arrows move a section past whatever is next to it, a plugin's section included.
 * Confining them to the palettes was the first instinct and it is the wrong one: it would leave a
 * section that refuses to pass the one above it for reasons nothing on screen explains. What they
 * must not do is rebuild the stack, and they do not: SideContentPane moves the one pane and leaves
 * every other child of the shared pane exactly where it was.
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
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(content, BorderLayout.CENTER);
        sections.put(title, new Section(title, icon, content, holder));
        pane.add(title, holder, true, icon, title, insertIndex(lastIndex.get(title), pane.getComponentCount()));
        pane.setAccessory(holder, buildControls(title, holder, onFloat));
        pane.revalidate();
    }

    /**
     * Pop out, and move up or down: in line with the title, at the trailing end of its band.
     *
     * <p>They were a row of their own under the header, which cost a line of sidebar height per
     * section and read as content rather than as chrome. CollapsiblePane carries the band's fill
     * behind them now, which is what used to make a trailing button leave a notch of window
     * background in the header.
     *
     * <p>The arrows are arrows and not chevrons on purpose. A chevron here means disclosure: the
     * one on this very header opens the section. Reordering is a different verb, and while the two
     * shared a glyph they sat a few pixels apart looking identical and doing unrelated things.
     */
    private static JComponent buildControls(String title, JComponent holder, Runnable onFloat) {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        bar.setOpaque(false);
        JButton up = Buttons.flat(Buttons.moveUp);
        up.setToolTipText("Move " + title + " up");
        up.addActionListener(e -> move(holder, -1));
        JButton down = Buttons.flat(Buttons.moveDown);
        down.setToolTipText("Move " + title + " down");
        down.addActionListener(e -> move(holder, 1));
        JButton floatOut = Buttons.flat(Buttons.popOut);
        floatOut.setToolTipText("Pop " + title + " out into a floating palette");
        floatOut.addActionListener(e -> onFloat.run());
        bar.add(up);
        bar.add(down);
        bar.add(floatOut);
        return bar;
    }

    private static void move(JComponent holder, int delta) {
        SideContentPane pane = MainFrame.getLeftContentPane();
        if (pane != null)
            pane.move(holder, delta);
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
