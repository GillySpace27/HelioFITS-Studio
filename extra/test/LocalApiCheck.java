package org.helioviewer.jhv.io.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.helioviewer.jhv.gui.registry.CommandRegistry;

import org.json.JSONObject;

/**
 * The local API: it listens on the loopback address at a port of the system's choosing, writes the
 * endpoint file for this user only, refuses a call without the token or from another host name,
 * answers the registry over JSON-RPC, puts a destructive command to the person first, and leaves
 * no file behind when it stops. Building the registry needs a display; run-checks.sh reruns this
 * with one.
 */
public final class LocalApiCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** One HTTP/1.1 request over a plain socket, so the Host header can be anything. Returns status and body. */
    private record Reply(int status, String body) {}

    private static Reply post(int port, String host, String auth, String body) throws IOException {
        return post(port, host, auth, body, null);
    }

    private static Reply post(int port, String host, String auth, String body, String client) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            String head = "POST /rpc HTTP/1.1\r\nHost: " + host + "\r\n" + (auth == null ? "" : "Authorization: " + auth + "\r\n") + (client == null ? "" : "X-HFS-Client: " + client + "\r\n")
                    + "Content-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
            InputStream in = socket.getInputStream();
            String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!all.startsWith("HTTP/1.1 "))
                throw new IOException("no HTTP reply: " + all);
            int line = all.indexOf("\r\n");
            int status = Integer.parseInt(all.substring(9, line).trim().split(" ")[0]);
            int blank = all.indexOf("\r\n\r\n");
            return new Reply(status, blank < 0 ? "" : all.substring(blank + 4));
        }
    }

    private static String rpc(String method, JSONObject params) {
        return new JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", method).put("params", params).toString();
    }

    public static void main(String[] args) throws Exception {
        // Before the first mention of Directories or Settings: both capture user.home when loaded.
        Path home = Files.createTempDirectory("hfs-local-api");
        System.setProperty("user.home", home.toString());
        org.helioviewer.jhv.app.Platform.init();
        // layerRemove, the destructive command used below, reaches Layers, whose null image layer reaches SPICE.
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        CommandRegistry.all(); // builds the catalog here, on this thread: without a display that is the HeadlessException run-checks.sh looks for

        try {
            run(home);
        } finally {
            LocalApi.stop(); // the server's dispatcher thread is not a daemon; a check that died would otherwise never exit
        }
        System.out.println(failures == 0 ? "LocalApiCheck: ok" : "LocalApiCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run(Path home) throws Exception {
        expect("off by default", !LocalApi.isEnabled());
        expect("asks before a destructive command by default", LocalApi.confirmsDestructive());
        expect("no endpoint file before it starts", !Files.exists(LocalApi.endpointFile()));

        LocalApi.Endpoint ep = LocalApi.start();
        expect("listens on a port the system chose: " + ep.port(), ep.port() > 0);
        expect("the same endpoint when started again", LocalApi.start() == ep);
        expect("the endpoint file is under the HFStudio home: " + ep.file(), ep.file().startsWith(home) && Files.exists(ep.file()));
        JSONObject file = new JSONObject(Files.readString(ep.file()));
        expect("the file names the port, the token and this process", file.getInt("port") == ep.port() && file.getString("token").equals(ep.token())
                && file.getLong("pid") == ProcessHandle.current().pid());
        expect("the token is 64 hex characters", ep.token().matches("[0-9a-f]{64}"));
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(ep.file());
            expect("the file is readable by this user only: " + perms, perms.equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException e) {
            System.out.println("  note  no POSIX permissions on this file system; the owner-only mode is not checked");
        }

        String bearer = "Bearer " + ep.token();
        expect("a call without the token is 401", post(ep.port(), "127.0.0.1", null, rpc("commands.list", new JSONObject())).status() == 401);
        expect("a call with a wrong token is 401", post(ep.port(), "127.0.0.1", "Bearer " + "0".repeat(64), rpc("commands.list", new JSONObject())).status() == 401);
        expect("a call from another host name is 403", post(ep.port(), "evil.example:" + ep.port(), bearer, rpc("commands.list", new JSONObject())).status() == 403);
        expect("Host with the port is fine", post(ep.port(), "127.0.0.1:" + ep.port(), bearer, rpc("commands.list", new JSONObject())).status() == 200);
        expect("localhost is fine", post(ep.port(), "localhost", bearer, rpc("commands.list", new JSONObject())).status() == 200);
        expect("the Host matcher: " + LocalApi.localHost("[::1]:8"), LocalApi.localHost("[::1]:8") && LocalApi.localHost("LOCALHOST") && !LocalApi.localHost("127.0.0.1.evil.example") && !LocalApi.localHost(null));

        Reply list = post(ep.port(), "127.0.0.1", bearer, rpc("commands.list", new JSONObject()));
        JSONObject listed = new JSONObject(list.body());
        expect("commands.list answers every command as a tool: " + list.status(),
                list.status() == 200 && listed.getJSONObject("result").getJSONArray("tools").length() == CommandRegistry.all().size());
        expect("the reply carries the request id", listed.getInt("id") == 7);

        Reply bad = post(ep.port(), "127.0.0.1", bearer, "{not json");
        expect("a body that is not JSON is a parse error, not a dropped connection: " + bad.body(),
                bad.status() == 200 && new JSONObject(bad.body()).getJSONObject("error").getInt("code") == -32700);
        Reply unknownMethod = post(ep.port(), "127.0.0.1", bearer, rpc("commands.fly", new JSONObject()));
        expect("an unknown method is -32601", new JSONObject(unknownMethod.body()).getJSONObject("error").getInt("code") == -32601);

        Reply describe = post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", new JSONObject().put("id", "describeControl").put("args", new JSONObject().put("id", "searchControls"))));
        JSONObject described = new JSONObject(describe.body());
        expect("commands.invoke runs a command and returns its result: " + describe.body(),
                described.has("result") && described.getJSONObject("result").optString("title").equals("Search Controls"));
        // Who is driving: the client header names it, the activity log records command and answer, the glow state follows
        long mark = org.helioviewer.jhv.activity.Activity.nextSeq() - 1;
        post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", new JSONObject().put("id", "listCommands")), "heliofits-mcp/1.0; Claude Desktop");
        org.helioviewer.jhv.activity.Driver.State driver = org.helioviewer.jhv.activity.Driver.current(System.nanoTime());
        expect("after a call the driver is the MCP client from the header: " + driver,
                driver != null && driver.kind() == org.helioviewer.jhv.activity.Driver.Kind.MCP && driver.name().equals("Claude Desktop via heliofits-mcp") && !driver.inFlight());
        java.util.List<org.helioviewer.jhv.activity.Activity.Event> cmds = org.helioviewer.jhv.activity.Activity.since(mark,
                java.util.EnumSet.of(org.helioviewer.jhv.activity.Activity.Kind.CMD), 20);
        expect("the log has the command and its answer with a timing: " + cmds.size(),
                cmds.size() == 2 && cmds.get(0).text().equals("> listCommands") && cmds.get(1).text().startsWith("< listCommands ok in ")
                        && cmds.get(0).source().equals("Claude Desktop via heliofits-mcp"));
        post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", new JSONObject().put("id", "listCommands")));
        expect("a call without the header is still answered, as another program",
                org.helioviewer.jhv.activity.Driver.current(System.nanoTime()).kind() == org.helioviewer.jhv.activity.Driver.Kind.OTHER);
        post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", new JSONObject().put("id", "activityLog").put("args", new JSONObject().put("kinds", new org.json.JSONArray().put("cmd")))));
        Reply seen = post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", new JSONObject().put("id", "activityLog").put("args", new JSONObject().put("since_seq", mark))));
        expect("activityLog over the API returns the calls just made: " + seen.body().length(),
                new JSONObject(seen.body()).getJSONObject("result").getJSONArray("events").length() >= 4);
        Reply unknown = post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", new JSONObject().put("id", "noSuchCommand")));
        expect("an unknown command carries data.kind unknown-command", kind(unknown).equals("unknown-command"));
        Reply missing = post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", new JSONObject().put("id", "seekFrame")));
        expect("missing arguments carry data.kind invalid-arguments", kind(missing).equals("invalid-arguments"));

        // Destructive commands go to the person first
        int[] asked = {0};
        LocalApi.Confirmer dialog = LocalApi.setConfirmer((spec, a) -> {
            asked[0]++;
            return false;
        });
        JSONObject remove = new JSONObject().put("id", "layerRemove").put("args", new JSONObject().put("layer_id", 1));
        Reply declined = post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", remove));
        expect("a destructive command the person refuses is declined, before the handler: " + declined.body(), kind(declined).equals("declined") && asked[0] == 1);
        LocalApi.setConfirmer((spec, a) -> {
            asked[0]++;
            return true;
        });
        Reply allowed = post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", remove));
        expect("one the person allows reaches the handler (no such layer): " + kind(allowed), kind(allowed).equals("invalid-arguments") && asked[0] == 2);
        LocalApi.setConfirmsDestructive(false);
        Reply unasked = post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", remove));
        expect("with the setting off nobody is asked", kind(unasked).equals("invalid-arguments") && asked[0] == 2);
        LocalApi.setConfirmsDestructive(true);
        Reply read = post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", new JSONObject().put("id", "listCommands")));
        expect("a read command is never put to the person", new JSONObject(read.body()).has("result") && asked[0] == 2);
        // Levels: what each one puts to the person
        ApiPermission.endTimedApproval();
        int[] n = {0};
        LocalApi.setConfirmer((spec, a) -> {
            n[0]++;
            return false;
        });
        JSONObject zoom = new JSONObject().put("id", "zoomIn");
        JSONObject export = new JSONObject().put("id", "exportStill").put("args", new JSONObject().put("path", "/nonexistent/never.png"));
        ApiPermission.setLevel(ApiPermission.Level.ALL);
        post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", remove));
        expect("Allow everything asks nothing, a removal included", n[0] == 0);
        ApiPermission.setLevel(ApiPermission.Level.REMOVALS);
        post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", zoom));
        expect("removals level: an ordinary change is not asked", n[0] == 0);
        post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", export));
        expect("removals level: a file write is asked", n[0] == 1);
        ApiPermission.setLevel(ApiPermission.Level.EVERYTHING);
        post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", zoom));
        expect("everything level: an ordinary change is asked", n[0] == 2);
        post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", new JSONObject().put("id", "listCommands")));
        expect("everything level: a read is not", n[0] == 2);

        // Timed approval: offered at the third question in two minutes, covers removals but not a whole scene
        ApiPermission.setLevel(ApiPermission.Level.REMOVALS);
        n[0] = 0;
        org.helioviewer.jhv.gui.registry.CommandSpec rm = org.helioviewer.jhv.gui.registry.CommandRegistry.get("layerRemove");
        long t0 = System.nanoTime();
        ApiPermission.endTimedApproval();
        expect("the offer comes on the third question inside two minutes, not before",
                !ApiPermission.notePrompt(rm, t0) && !ApiPermission.notePrompt(rm, t0 + 1_000_000_000L) && ApiPermission.notePrompt(rm, t0 + 2_000_000_000L));
        ApiPermission.endTimedApproval();
        expect("questions more than two minutes apart do not add up",
                !ApiPermission.notePrompt(rm, t0) && !ApiPermission.notePrompt(rm, t0 + 130_000_000_000L) && !ApiPermission.notePrompt(rm, t0 + 260_000_000_000L));
        ApiPermission.endTimedApproval();
        LocalApi.setConfirmer((spec, a) -> {
            n[0]++;
            if (n[0] == 3)
                LocalApi.grantMinutes.set(5); // the dialog's ticked box
            return true;
        });
        for (int i = 0; i < 3; i++)
            post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", remove));
        expect("the third question in a row is the one that offers it", n[0] == 3);
        post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", remove));
        expect("after it is ticked a removal is not asked again", n[0] == 3 && ApiPermission.timedApprovalActive());
        JSONObject quit = new JSONObject().put("id", "quit");
        LocalApi.setConfirmer((spec, a) -> {
            n[0]++;
            return false;
        });
        Reply scene = post(ep.port(), "127.0.0.1", bearer, rpc("commands.invoke", quit));
        expect("a command that discards the whole scene is still asked, and declined here", kind(scene).equals("declined") && n[0] == 4);
        ApiPermission.setLevel(ApiPermission.Level.REMOVALS);
        expect("changing the level ends the approval", !ApiPermission.timedApprovalActive());
        LocalApi.setConfirmer(dialog);

        LocalApi.stop();
        expect("stopping removes the endpoint file", !Files.exists(ep.file()));
        boolean refused = false;
        try {
            post(ep.port(), "127.0.0.1", bearer, rpc("commands.list", new JSONObject()));
        } catch (java.net.SocketException e) {
            refused = true;
        }
        expect("stopping closes the port", refused);
        expect("the setting switch starts and stops: on", LocalApi.endpoint() == null);
        LocalApi.setEnabled(true);
        expect("setEnabled(true) listens and remembers", LocalApi.endpoint() != null && LocalApi.isEnabled());
        LocalApi.setEnabled(false);
        expect("setEnabled(false) stops and remembers", LocalApi.endpoint() == null && !LocalApi.isEnabled() && !Files.exists(ep.file()));
    }

    private static String kind(Reply r) {
        JSONObject o = new JSONObject(r.body());
        return o.has("error") ? o.getJSONObject("error").getJSONObject("data").optString("kind", "?") : "ok";
    }
}
