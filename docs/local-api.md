# The local API

How a script, the `heliofits` Python package or the `heliofits-mcp` server reaches a running
HelioFITS Studio. The verbs are the command registry's (`docs/commands.md`, `docs/commands.json`);
this page is the transport. Code: `io/api/LocalApi.java`; check: `LocalApiCheck`.

## Switching it on

Settings > General > Local API. Off by default: nothing listens until the person says so. The
setting is `api.enabled`; `api.confirmDestructive` (on by default) puts every `destructive` command
to the person in a dialog before it runs. Both apply at once, no restart.

## Finding Studio

When on, Studio listens on 127.0.0.1 at a port the system picks and writes `~/HFStudio/api.json`:

```json
{"port": 41983, "token": "<64 hex characters>", "pid": 12345, "protocol": "hfstudio-api/1"}
```

The file is readable by this user only where the file system can say so (POSIX mode 600); on
Windows the home folder's own permissions apply. The token is new each run and the file is removed
when the API is switched off or Studio quits, so a client reads the file each time it is refused.
The `heliofits` package also honours `HFSTUDIO_API=<path>` for a file elsewhere.

## Calling

POST a JSON-RPC 2.0 request to `http://127.0.0.1:<port>/rpc` with `Authorization: Bearer <token>`.
The `Host` header must be `127.0.0.1`, `localhost` or `[::1]` (with or without the port), so a web
page cannot reach the port through a DNS name it controls. Without the token: 401; from another
host name: 403; a body over 1 MB: 413.

| Method | Params | Result |
|---|---|---|
| `commands.list` | none | `{"tools": [...]}`: the registry as an MCP tools list, as `docs/commands.json` |
| `commands.invoke` | `{"id": "<command>", "args": {...}}` | the command's result object |

An error reply carries `error.code`, `error.message` and `error.data.kind`:

| kind | code | when |
|---|---|---|
| `unknown-method` | -32601 | a JSON-RPC method other than the two |
| `unknown-command` | -32601 | no command with that id |
| `invalid-arguments` | -32602 | the schema refused the arguments, or the handler refused a value in words |
| `disabled` | -32001 | the command is not available now (its menu item would be grey) |
| `declined` | -32002 | a destructive command the person refused in the dialog |
| `failed` | -32000 | the handler failed; the message says what |

A command that loads or exports answers when it is done, so a call can take minutes; a client sets
its timeout accordingly (the Python client's default is ten minutes). Calls run one at a time on the
event thread while they need it; the wait for a load or an export does not hold it.

## What is not here

No SAMP (the Core and the Swift app still use it), no TLS (loopback only), no remote access, no
MCP server inside Studio (the `heliofits-mcp` process is that, and talks to this API).
