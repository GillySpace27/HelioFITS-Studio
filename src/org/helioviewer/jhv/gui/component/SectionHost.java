package org.helioviewer.jhv.gui.component;

import java.awt.Component;

import javax.annotation.Nullable;
import javax.swing.Icon;

/**
 * A sidebar that can hold a {@link Palette} as one of its sections.
 *
 * <p>Both sidebars answer the same four questions, so a palette does not have to know which one it
 * is living in: it holds a host, or null for a window of its own. That is what lets the Grid
 * palette be dragged into either bar, and what lets the four panes the left bar starts with be
 * popped out into windows without any of it being written twice.
 *
 * <p>{@link #hostName()} is persisted, so it is API: change one and every saved palette that lived
 * there comes back as a window.
 */
public interface SectionHost {

    /**
     * Take a palette in as a section.
     *
     * @param onFloat run when the user asks for this section to become a floating palette again
     */
    void addSection(String title, @Nullable Icon icon, Component content, Runnable onFloat);

    /** Give the section up. The content component goes with whoever asks for it next. */
    void removeSection(String title);

    boolean hasSection(String title);

    /** Make a section visible: open the sidebar if it is folded away, and expand the section. */
    void reveal(String title);

    /** Stable name used to remember where a palette lives, across launches. */
    String hostName();

}
