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
its id is listed in {doc}`generated/commands`.

## Settings kept in a file

Settings are kept in `~/HFStudio/Settings/user.properties`. A few have no control in the app:

| Setting | Effect |
|---|---|
| `display.deepColorCanvas=false` | Use the old 8-bit canvas on macOS instead of the 10-bit one. |
| `display.lascoBackground=false` | Turn off the monthly background subtraction for LASCO layers. |

Quit HelioFITS Studio before editing the file, and relaunch afterwards.
