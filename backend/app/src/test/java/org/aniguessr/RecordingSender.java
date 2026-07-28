package org.aniguessr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test double for {@link SessionSender} that records every payload sent, so tests
 * can assert on what the server broadcast without needing a real WebSocket.
 */
class RecordingSender implements SessionSender {

    record Sent(String sessionId, Map<String, Object> payload) {}

    final List<Sent> sent = new ArrayList<>();

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void send(String sessionId, Object payload) {
        sent.add(new Sent(sessionId, (Map<String, Object>) payload));
    }

    /** All recorded payloads with the given "type". */
    synchronized List<Map<String, Object>> ofType(String type) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Sent s : sent) {
            if (type.equals(s.payload().get("type"))) out.add(s.payload());
        }
        return out;
    }

    synchronized boolean has(String type) {
        return !ofType(type).isEmpty();
    }

    /** The most recent payload of the given type, or null. */
    synchronized Map<String, Object> last(String type) {
        List<Map<String, Object>> all = ofType(type);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /** The most recent payload sent to a specific session, or null. */
    synchronized Map<String, Object> lastTo(String sessionId) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).sessionId().equals(sessionId)) return sent.get(i).payload();
        }
        return null;
    }

    synchronized void clear() {
        sent.clear();
    }
}
