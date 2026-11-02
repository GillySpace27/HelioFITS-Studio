# The command registry

One list of what HelioFITS Studio can do, written once and read by everything that needs it: the
menus, Help > Search Controls (Cmd-K), the guided tours, the Tip of the Day, and, later, an MCP
server, an API or a command line. Nothing else keeps a list of its own.

## The pieces

| Piece | Where | What it holds |
|---|---|---|
| `ActionCatalog` | `gui/ActionCatalog.java` | one Swing Action per menu item or toolbar command, under a stable id (`docs/actions.md`) |
| `CommandRegistry` | `gui/registry/` | a `CommandSpec` per command: id, description, effect, input and output JSON Schema, handler. Built from every catalogued action (no arguments) plus the commands that take arguments |
| `Dispatch.invoke(id, args)` | `gui/registry/Dispatch.java` | the one way to run a command by id: checks the id, the schema and that the command is enabled, runs the handler on the event thread, answers with a `Result` and never throws |
| `ControlRegistry` | `gui/registry/ControlRegistry.java` | for each command and control: title, place, tooltip, shortcut, doc link, the tours and tips that mention it |
| `resources/data/controls.json` | controls that are not commands (a toolbar toggle, a sidebar section) | id, title, where, words, reveal, doc |
| `docs/commands.json` | generated | the registry as an MCP tools list (`name`, `description`, `inputSchema`, `outputSchema`, `annotations`, `x-effect`) |

The tooltips, tours and tips stay where they are (`docs/tips-and-tooltips.md`); the registry derives
from them and does not copy them.

## Effects

`read` changes nothing. `write` changes the scene or a setting and can be repeated or undone.
`destructive` discards work or data, so a front end asks first. `ui` only opens or shows part of
the interface. Every catalogued action is named in `CommandRegistry.EFFECTS` or `CommandRegistry.UI_ONLY`;
`RegistryCheck` fails on one that is in neither, and at run time an unclassified one is treated as
`write`, never as harmless. The tables are a judgement, made once; review them when a command is added.

## Adding a command

- A menu item or toolbar command with no arguments: add its `put(...)` line in `ActionCatalog` as
  before. It is a command automatically; add it to `EFFECTS` if it is not `ui`.
- A command with arguments, or one that reads: add one `add(new CommandSpec(...))` in
  `CommandRegistry`. Call `Commands`, the layer the SAMP handlers use, so a bad value is rejected the
  same way on every path. Throw `IllegalArgumentException` for a value the schema cannot express.
- A control that is not a command: add it to `controls.json` and give the component a literal
  `setName` (ControlSearchCheck requires both).

Then regenerate the manifest and commit it:

```
xvfb-run -a java --enable-preview -cp "bin:resources:$(find lib -name '*.jar' | tr '\n' ':')" \
    org.helioviewer.jhv.gui.registry.CommandRegistry docs/commands.json
```

`RegistryCheck` fails when `docs/commands.json` is out of date, when a catalogued action is not a
command, and when a call is not refused for a bad argument. Ids are API: never rename one.

## Toward an MCP server, API or command line

The front ends are meant to be thin: read `docs/commands.json` for the tool list and call
`Dispatch.invoke` for each call. They add no verbs. The shape of a `CommandSpec` follows an MCP tool
so `tools/list` is the manifest and `tools/call` is `Dispatch.invoke`. The transport into a running
Studio (SAMP or a localhost socket with a token), the confirmation rule for `destructive` commands,
and an opt-in setting to switch it all on are not built yet; none is on by default.

The menu items and toolbar buttons still call their Swing Action directly, because Swing owns that
call. They run the same Action object the registry's handler runs.

## Keybindings

`KeyBindings` (`gui/registry/`) keeps the keybinding of every catalogued command: the default is the
`ACCELERATOR_KEY` its Action states, the person's own is the setting `keys.<command id>`. Empty is
the default, `none` is deliberately unbound, anything else is a keystroke as
`KeyStroke.getKeyStroke(String)` reads it with `menu` for the platform's menu key (Cmd on a Mac,
Ctrl elsewhere), so a settings file moved between computers keeps its meaning. A change is applied by
setting the Action's accelerator, which the menu items follow, so nothing restarts.

Rules, so a keybinding cannot break something else: it must carry Ctrl, Alt or Cmd/Meta, or be a
function key (a bare letter would shadow J, K, L, I, O and the arrow keys the canvas and time slider
handle themselves); it cannot be another command's; it cannot be one the system or text editing owns
(`KeyBindings.RESERVED_*`). Settings > Keybindings (`KeybindingsPanel`) is the table. The keys a focused
control handles itself are not commands and stay fixed.

Not built: pressing the new shortcut to record it (on a Mac the menu bar can take the combination
before the dialog sees it, so recording Cmd+Q would quit; the dialog uses modifier boxes and a key
list instead), import and export of a bindings file, and a build failure when an Action is added
with an accelerator but no catalog id (not checked today).
