package org.helioviewer.jhv.identify;

import java.awt.event.ActionEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.Action;

/**
 * The identifier's three switches as actions, for the View menu (under Show Status Bar), help
 * search and the command registry. Each is a toggle whose check mark follows the setting, however
 * it was changed: the menu, a command, or Settings &gt; Identify.
 */
public final class IdentifyActions {

    public static final String UNDER_CURSOR = "identifyUnderCursor";
    public static final String TOOLTIP = "identifyTooltip";
    public static final String STARS = "identifyStars";

    @SuppressWarnings("serial")
    private static final class Toggle extends AbstractAction {
        private final BooleanSupplier get;
        private final Consumer<Boolean> set;

        Toggle(String name, String tip, BooleanSupplier _get, Consumer<Boolean> _set) {
            super(name);
            get = _get;
            set = _set;
            putValue(SHORT_DESCRIPTION, tip);
            putValue(SELECTED_KEY, get.getAsBoolean());
            IdentifySettings.addListener(() -> putValue(SELECTED_KEY, get.getAsBoolean()));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            // A menu check box has already changed its own state; anything else (search, a command) toggles
            set.accept(e.getSource() instanceof javax.swing.AbstractButton b ? b.isSelected() : !get.getAsBoolean());
        }
    }

    public static Action underCursor() {
        return new Toggle("Identify Under Cursor",
                "Name the Moon, planet or Sun under the pointer in the footer, as each image layer's observer saw it",
                IdentifySettings::enabled, IdentifySettings::setEnabled);
    }

    public static Action tooltip() {
        return new Toggle("Identify Tooltip",
                "Also show the name beside the pointer when it rests (needs Identify Under Cursor)",
                IdentifySettings::tooltip, IdentifySettings::setTooltip);
    }

    public static Action stars() {
        return new Toggle("Identify Stars",
                "Include stars (needs the star catalog; names and magnitude limit in Settings > Identify)",
                IdentifySettings::stars, IdentifySettings::setStars);
    }

    private IdentifyActions() {}
}
