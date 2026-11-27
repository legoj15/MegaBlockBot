package com.legoj15.megablockbot.core.lang.model;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Collects every log record under one logger name at every level, with its parameters and stack trace written out,
 * so a test can scan everything the code under test logged. {@link System.Logger} backs onto
 * {@code java.util.logging} in a plain JVM, which is what this hooks.
 */
final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final Level previousLevel;
    private final Handler handler;
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    LogCapture(String loggerName) {
        logger = Logger.getLogger(loggerName);
        previousLevel = logger.getLevel();
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                StringBuilder line = new StringBuilder(String.valueOf(record.getMessage()));
                if (record.getParameters() != null) {
                    for (Object parameter : record.getParameters()) line.append(' ').append(parameter);
                }
                if (record.getThrown() != null) {
                    StringWriter trace = new StringWriter();
                    record.getThrown().printStackTrace(new PrintWriter(trace));
                    line.append('\n').append(trace);
                }
                lines.add(line.toString());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    List<String> lines() {
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setLevel(previousLevel);
    }
}
