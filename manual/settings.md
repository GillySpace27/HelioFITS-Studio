# Settings and keybindings

## Memory and speed

Under **Settings > Network**:

- **Memory** sets how much of the computer's memory the image stream data and the decoded frames may
  use together (50% by default). When the budget is full, the frames farthest from the one on screen
  are dropped and read back from the disk cache when needed.
- **Decoding** sets how many threads decode images, shared by all layers (automatic by default).
- **Long requests** chooses when the next piece of a request over the server's 1000-frame limit is
  asked for.

## Keybindings

**Settings > Keybindings** lists every command with its keybinding. Search by name, change a
keybinding, remove it, or put it back to the default, one command or all at once. A keybinding must
carry Cmd or Ctrl, or Alt, or be a function key, so it cannot shadow the keys the canvas and time
slider use (J, K, L, I, O and the arrows), and one that clashes with another command, the system or
text editing is refused with the reason. Changes apply at once, in the menus too. Every command and
its id is listed in {doc}`generated/commands`. The bare keys the picture answers to (W A S D Q E, Up and
Down) are not commands; **Canvas keys...** on the same tab changes them (see {doc}`views`).

## Moving your configuration to another computer

{menuselection}`File --> Save Global Preferences...` (also **Settings > General > Global preferences**) writes one
readable JSON file with your settings: keybindings and Canvas keys, interface and text scale, the toolbar,
sidebar and palette layout, Identify, the load guard limits, and the other options in the Settings dialog.
It never holds a password, a proxy login, the install id, the local API key (`api.json`) or window
positions. Folder paths (download, session and export folders, recent sessions) are left out unless you
tick **Include folder paths**, because a folder on this computer is rarely one on the next.

{menuselection}`File --> Load Global Preferences...` reads such a file and shows what would change, setting by
setting, before it does anything; you confirm or cancel. A file saved by a newer Studio than this one is
refused, keys this version does not know are ignored, and a value that fails its check (a switch that is
not true or false, a scale out of range, a keybinding that is not a keystroke) is refused and listed.
Nothing is deleted: a setting the file does not mention stays as it is. Restart Studio afterwards so every
panel reads the new values.

Studio also keeps a dated copy of these preferences, folder paths included, in `Backups/` of its data
folder each time it quits (one file a day, none removed). Turn that off with the checkbox beside the
Save and Load buttons.

## Settings kept in a file

Settings are kept in `~/HFStudio/Settings/user.properties`. A few have no control in the app:

| Setting | Effect |
|---|---|
| `display.deepColorCanvas=false` | Use the old 8-bit canvas on macOS instead of the 10-bit one. |
| `display.lascoBackground=false` | Turn off the monthly background subtraction for LASCO layers. |

Quit HelioFITS Studio before editing the file, and relaunch afterwards.
