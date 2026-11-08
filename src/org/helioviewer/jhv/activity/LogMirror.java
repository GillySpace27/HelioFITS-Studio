package org.helioviewer.jhv.activity;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Copies each line of Studio's log (INFO and above, what the log file gets) into the activity
 * log, so the Activity window shows what a terminal running Studio would print, beside the
 * commands and transfers. Holds nothing; the ring caps what is kept.
 */
public final class LogMirror extends Handler {

    public LogMirror() {
        setLevel(Level.INFO);
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record))
            return;
        Throwable thrown = record.getThrown();
        String message = record.getMessage() == null ? "" : record.getMessage();
        String level = record.getLevel() == Level.INFO ? "" : record.getLevel().getName() + " ";
        Activity.event(Activity.Kind.LOG, shortName(record.getLoggerName()), level + message + (thrown == null ? "" : ": " + thrown));
    }

    private static String shortName(String logger) {
        if (logger == null || logger.isEmpty())
            return "log";
        int dot = logger.lastIndexOf('.');
        return dot < 0 ? logger : logger.substring(dot + 1);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

}
