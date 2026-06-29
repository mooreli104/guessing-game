package org.aniguessr;

public interface SessionSender {
    void send(String sessionId, Object payload);
}
