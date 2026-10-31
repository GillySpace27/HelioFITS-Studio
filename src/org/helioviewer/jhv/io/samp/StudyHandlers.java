package org.helioviewer.jhv.io.samp;

import java.awt.EventQueue;

import org.helioviewer.jhv.app.Commands;

final class StudyHandlers {

    static void register(SampClient client) {
        client.addMessageHandler(SampHandlers.create("jhv.study.run", (senderId, sender, msg) -> {
            Commands.OperationContext context = SampClient.operationContext(senderId, msg, "jhv.study.run", "jhv.study.run.completed");
            String job = SampHandlers.optionalString(msg, "job");
            if (job == null)
                context.complete(false, "Missing jhv.study.run job (the text of a .hfstudy.json file).", null);
            else
                EventQueue.invokeLater(() -> Commands.studyRun(context, job));
        }));
        client.addMessageHandler(SampHandlers.create("jhv.study.status", (senderId, sender, msg) -> {
            Commands.OperationContext context = SampClient.operationContext(senderId, msg, "jhv.study.status", "jhv.study.status.completed");
            EventQueue.invokeLater(() -> context.complete(true, "ok", Commands.studyStatus().toString()));
        }));
    }

    private StudyHandlers() {}
}
