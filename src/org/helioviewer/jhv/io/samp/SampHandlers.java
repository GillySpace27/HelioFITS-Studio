package org.helioviewer.jhv.io.samp;

import java.awt.EventQueue;
import java.util.Collections;
import java.util.Map;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;

import org.astrogrid.samp.Message;
import org.astrogrid.samp.client.AbstractMessageHandler;
import org.astrogrid.samp.client.HubConnection;

final class SampHandlers {

    private static final Map<String, String> harmless = Collections.singletonMap("x-samp.mostly-harmless", "1");

    @FunctionalInterface
    interface CheckedHandler {
        void accept(String senderId, String senderName, Message msg) throws Exception;
    }

    static AbstractMessageHandler create(String type, CheckedHandler consumer) {
        return new AbstractMessageHandler(Collections.singletonMap(type, harmless)) {
            @Nullable
            @Override
            public Map<?, ?> processCall(HubConnection c, String senderId, Message msg) {
                String who = "SAMP";
                try {
                    String sender = c.getMetadata(senderId).getName();
                    who = sender == null || sender.isBlank() ? "SAMP" : "SAMP: " + sender;
                    org.helioviewer.jhv.activity.Activity.event(org.helioviewer.jhv.activity.Activity.Kind.CMD, who, "> " + type);
                    org.helioviewer.jhv.activity.Driver.begin(org.helioviewer.jhv.activity.Driver.Kind.OTHER, who, type);
                    try {
                        consumer.accept(senderId, sender, msg);
                    } finally {
                        org.helioviewer.jhv.activity.Driver.end();
                    }
                } catch (Exception e) {
                    Log.warn(type, e);
                }
                return null;
            }
        };
    }

    static AbstractMessageHandler command(String type, Runnable command) {
        return create(type, (senderId, sender, msg) -> EventQueue.invokeLater(command));
    }

    static @Nullable String optionalString(Message msg, String key) {
        Object value = msg.getParam(key);
        return value == null ? null : value.toString();
    }

    private SampHandlers() {}
}
