package com.legoj15.megablockbot.game.body;

/** The manager refused a request (bad name, name in use, no such body). The message is plain text for the caller. */
public final class BodyException extends Exception {
    public BodyException(String message) {
        super(message);
    }
}
