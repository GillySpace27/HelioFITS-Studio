package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JComponent;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.gui.UIGlobals;

// This panel consists of a toggle button and one arbitrary component. Clicking
// the toggle button will toggle the visibility of the component.
@SuppressWarnings({"serial", "this-escape"})
public class CollapsiblePane extends JComponent implements ActionListener {

    final CollapsiblePaneButton toggleButton;
    private final JComponent managed;
    private String title;

    public CollapsiblePane(String _title, JComponent _managed, boolean startExpanded) {
        this(_title, _managed, startExpanded, false);
    }

    // child=true renders a subordinate (nested) section: regular weight instead of bold,
    // so it reads as a child of the bold parent header it sits indented beneath.
    public CollapsiblePane(String _title, JComponent _managed, boolean startExpanded, boolean child) {
        setLayout(new BorderLayout());

        managed = _managed;
        title = _title;
        // A section opens the way it was last left. Every launch used to open with every section
        // collapsed, so Layer options had to be clicked open every single time.
        boolean expanded = remembered(startExpanded);
        ComponentUtils.setVisible(managed, expanded);

        toggleButton = new CollapsiblePaneButton();
        toggleButton.setSelected(expanded);
        toggleButton.setFont(child ? UIGlobals.uiFontSmall : UIGlobals.uiFontSmallBold);
        toggleButton.addActionListener(this);
        setTitle(_title);

        add(toggleButton, BorderLayout.PAGE_START);
        add(managed, BorderLayout.CENTER);
    }

    public void setTitle(String _title) {
        title = _title;
        toggleButton.setText((toggleButton.isSelected() ? Buttons.chevronDown : Buttons.chevronRight) + title);
    }

    public void setExpanded(boolean expanded) {
        ComponentUtils.setVisible(managed, expanded);
        toggleButton.setSelected(expanded);
        setTitle(title);
    }

    /** How this section was last left by a click, or the fallback when it never was. */
    public boolean remembered(boolean fallback) {
        String stored = Settings.getProperty(key());
        return stored == null ? fallback : Boolean.parseBoolean(stored);
    }

    private String key() {
        return "ui.section." + title.replace(' ', '_');
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        boolean expanded = !managed.isVisible();
        setExpanded(expanded);
        Settings.setProperty(key(), Boolean.toString(expanded)); // a click is a preference; setExpanded from code is not
    }

}
