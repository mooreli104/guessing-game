package org.aniguessr;

/**
 * One submitted bug report or piece of feedback.
 *
 * Bounds are enforced here rather than in the browser, in the same spirit as
 * {@code GameManager.displayName}: the {@code maxLength} on the form is a convenience,
 * and POST /feedback is an open endpoint that anything can call directly.
 */
public record Feedback(String kind, String message, String contact) {

    public static final String BUG = "BUG";
    public static final String FEEDBACK = "FEEDBACK";

    public static final int MAX_MESSAGE = 2000;
    public static final int MAX_CONTACT = 200;

    /**
     * Build a submission from raw request fields, or null when there is nothing worth
     * storing. Only an empty message is refused — everything else is coerced, so a
     * caller cannot turn a typo into a 500.
     */
    public static Feedback from(String kind, String message, String contact) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty()) return null;

        // Unknown kinds become FEEDBACK rather than an error, the same way an unknown
        // difficulty falls back to normal. The form only ever sends the two.
        String normalised = BUG.equalsIgnoreCase(kind == null ? "" : kind.trim())
            ? BUG
            : FEEDBACK;

        return new Feedback(normalised, cap(trimmed, MAX_MESSAGE),
            cap(contact == null ? "" : contact.trim(), MAX_CONTACT));
    }

    private static String cap(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
