package org.helioviewer.jhv.gui.dialog;

import java.util.List;

import javax.annotation.Nullable;

/**
 * What the Welcome window says and offers, apart from how it is drawn, so WelcomeCheck can read it
 * without a display. The window is a launcher first: one primary way to start, then a signpost to
 * the help, with the long text kept in each item's tooltip and screen-reader description rather
 * than on screen.
 */
final class WelcomeModel {

    /** Which window to show: the first one ever, one after an update, or the everyday one. */
    enum State { FIRST_RUN, UPDATED, RETURNING }

    /** The loud button, the one Return presses. */
    enum Primary { TOUR, CONTINUE, REOPEN, OPEN_SESSION }

    /** HELP is always on screen; MORE sits behind the More help disclosure. */
    enum Tier { HELP, MORE }

    /** Opens the tour list instead of running a catalog command. */
    static final String TOURS = "tours";
    static final String MCP_URL = "https://github.com/GillySpace27/pyHelioFITS/blob/main/docs/mcp.md";
    static final String LOCAL_API_PATH = "/blob/master/docs/local-api.md"; // after AppInfo.sourceURL

    /**
     * One help item.
     *
     * @param id          an ActionCatalog id, TOURS, or a URL to open in the browser
     * @param name        the visible name, also the accessible name: plain text, no markup
     * @param caption     a few words under the name; visible on the first run and in More help
     * @param description the full sentence: tooltip and accessible description
     * @param closes      whether pressing it closes the Welcome window first (it needs the main window)
     */
    record Item(String id, String name, String caption, String description, Tier tier, boolean closes) {
        boolean isLink() {
            return id.startsWith("https://") || id.startsWith("/");
        }
    }

    static final List<Item> ITEMS = List.of(
            new Item("searchControls", "Search Controls", "Find or run anything by name",
                    "Type what you want to do or the name of any control. It runs the command, or points at the control and shows where it is.",
                    Tier.HELP, true),
            new Item("keyboardShortcuts", "Keyboard Shortcuts", "Every key, searchable",
                    "Every keyboard shortcut in force, grouped, with a search box.", Tier.HELP, true),
            new Item(TOURS, "Tours", "Guided walkthroughs",
                    "Short guided tours that point at each control in turn. Pick one from the list.", Tier.HELP, true),
            new Item("openUserManual", "User Manual", "The full guide, as a PDF",
                    "The full guide to every part of Studio, as a PDF.", Tier.HELP, false),
            new Item("tipOfTheDay", "Tip of the Day", "One short tip at a time",
                    "One short tip at a time, each with Show Me to point at the control it is about.", Tier.MORE, true),
            new Item("sendFeedback", "Send Feedback", "No account needed",
                    "A problem, a request or a question for the developer; no account needed.", Tier.MORE, false),
            new Item(MCP_URL, "MCP Server Docs", "Let an AI assistant drive Studio",
                    "heliofits-mcp lets an AI assistant such as Claude Desktop drive Studio and the cache. It needs the Local API. Opens the docs in your browser.",
                    Tier.MORE, false),
            new Item(LOCAL_API_PATH, "Local API Docs", "Call Studio from your scripts",
                    "Lets scripts and heliofits-mcp on this computer call Studio. Off by default: Settings > General > Local API. Opens the docs in your browser.",
                    Tier.MORE, false));

    /** Shown under the help, so the two kinds of help with nothing to press are still named. */
    static final String FOOTNOTE = "Hover any control for a tooltip. All of this is also in the Help menu.";

    static final int CAPTION_MAX = 40;
    static final int RECENTS_SHOWN = 3;

    /**
     * @param seen           startup.welcomeSeen: null until the window has opened once
     * @param versionSeen    startup.welcomeVersion: the version the window last opened in
     * @param runningVersion the running version, without the commits-since-tag suffix
     */
    static State state(@Nullable String seen, @Nullable String versionSeen, String runningVersion) {
        if (seen == null)
            return State.FIRST_RUN;
        return runningVersion.equals(versionSeen) ? State.RETURNING : State.UPDATED;
    }

    /**
     * The first window leads with the Getting Started tour. After that, Continue when there is
     * something on the canvas, else the last session when there is one, else Open Session.
     */
    static Primary primary(State state, boolean sceneEmpty, boolean hasRecent) {
        if (state == State.FIRST_RUN)
            return Primary.TOUR;
        if (!sceneEmpty)
            return Primary.CONTINUE;
        return hasRecent ? Primary.REOPEN : Primary.OPEN_SESSION;
    }

    /** The first run shows each tile's caption; later windows show the names only, in one row. */
    static boolean showCaptions(State state) {
        return state == State.FIRST_RUN;
    }

    private WelcomeModel() {}

}
