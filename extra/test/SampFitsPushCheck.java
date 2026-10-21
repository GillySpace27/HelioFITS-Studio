package org.helioviewer.jhv.io.samp;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.astrogrid.samp.Message;
import org.astrogrid.samp.Metadata;
import org.astrogrid.samp.client.AbstractMessageHandler;
import org.astrogrid.samp.client.ClientProfile;
import org.astrogrid.samp.client.DefaultClientProfile;
import org.astrogrid.samp.client.HubConnection;
import org.astrogrid.samp.client.HubConnector;
import org.astrogrid.samp.hub.Hub;
import org.astrogrid.samp.hub.HubProfile;
import org.astrogrid.samp.hub.HubServiceMode;
import org.astrogrid.samp.xmlrpc.StandardHubProfile;

/**
 * Send to Python's SAMP push (HS-13) against a stand-in hub in this JVM: with no hub nothing is sent and nothing
 * is thrown; with one, a receiver subscribed to image.load.fits gets one notification per file whose url is the
 * file's file: URI and whose name is the file name. user.home is a temp directory, so the hub's lockfile and any
 * settings stay out of Gilly's home.
 */
public final class SampFitsPushCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        if (System.getenv("SAMP_HUB") != null) {
            System.out.println("SKIP: SAMP_HUB is set, so a stand-in hub cannot own the lockfile here");
            return;
        }
        File home = Files.createTempDirectory("samp-push").toFile();
        System.setProperty("user.home", home.getPath());

        File a = new File(home, "a_phys.fits");
        File b = new File(home, "b c_phys.fits");
        Files.writeString(a.toPath(), "x");
        Files.writeString(b.toPath(), "x");
        List<File> files = List.of(a, b);

        ClientProfile profile = DefaultClientProfile.getProfile();
        HubConnector tx = new HubConnector(profile);
        expect("no hub: nothing sent, nothing thrown", SampClient.sendFitsFiles(tx, files) == 0);

        Hub hub = Hub.runHub(HubServiceMode.NO_GUI, new HubProfile[]{new StandardHubProfile()}, null);
        List<Map<?, ?>> got = new CopyOnWriteArrayList<>();
        HubConnector rx = new HubConnector(profile);
        rx.declareMetadata(Metadata.asMetadata(Map.of("samp.name", "receive_fits stand-in")));
        rx.addMessageHandler(new AbstractMessageHandler("image.load.fits") {
            @Override
            public Map<?, ?> processCall(HubConnection connection, String senderId, Message message) {
                got.add(message.getParams());
                return null;
            }
        });
        rx.declareSubscriptions(rx.computeSubscriptions());
        try {
            expect("the receiver registers with the stand-in hub", rx.getConnection() != null);
            int reached = SampClient.sendFitsFiles(tx, files);
            expect("with a hub, one client is reached (got " + reached + ")", reached == 1);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (got.size() < files.size() && System.nanoTime() < until)
                Thread.sleep(20);
            expect("one notification per file (got " + got.size() + ")", got.size() == files.size());
            boolean urls = got.size() == files.size();
            for (int i = 0; urls && i < files.size(); i++) {
                Map<?, ?> p = got.get(i);
                urls = files.get(i).toURI().toString().equals(p.get("url")) && files.get(i).getName().equals(p.get("name"));
            }
            expect("each url is the file's file: URI and each name its file name", urls);
            expect("a url resolves back to the file, space included",
                    got.size() == files.size() && new File(java.net.URI.create((String) got.get(1).get("url"))).equals(b));
        } finally {
            rx.setActive(false);
            tx.setActive(false);
            hub.shutdown();
        }

        if (failures > 0) {
            System.out.println("SampFitsPushCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("SampFitsPushCheck: all passed");
        System.exit(0); // the SAMP client threads are not daemons
    }
}
