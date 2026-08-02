package org.aniguessr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FeedbackTest {

    @Test
    void from_keepsAMessageAndTrimsIt() {
        Feedback f = Feedback.from("FEEDBACK", "  the timer is too fast  ", "");
        assertEquals("the timer is too fast", f.message());
        assertEquals(Feedback.FEEDBACK, f.kind());
        assertEquals("", f.contact());
    }

    @Test
    void from_withoutAUsableMessage_isRejected() {
        assertNull(Feedback.from("BUG", null, ""));
        assertNull(Feedback.from("BUG", "", ""));
        assertNull(Feedback.from("BUG", "   \n  ", ""));
    }

    @Test
    void from_recognisesBugRegardlessOfCase() {
        assertEquals(Feedback.BUG, Feedback.from("BUG", "broke", "").kind());
        assertEquals(Feedback.BUG, Feedback.from("bug", "broke", "").kind());
        assertEquals(Feedback.BUG, Feedback.from(" Bug ", "broke", "").kind());
    }

    // Unknown kinds are coerced rather than refused, so a caller cannot turn a typo into
    // a rejected submission -- the message is the part worth keeping.
    @Test
    void from_withUnknownKind_fallsBackToFeedback() {
        assertEquals(Feedback.FEEDBACK, Feedback.from(null, "hi", "").kind());
        assertEquals(Feedback.FEEDBACK, Feedback.from("WHATEVER", "hi", "").kind());
    }

    // The form's maxLength is a convenience; POST /feedback is open, so the bound that
    // matters is this one.
    @Test
    void from_capsMessageAndContact() {
        String longMessage = "x".repeat(Feedback.MAX_MESSAGE + 500);
        String longContact = "y".repeat(Feedback.MAX_CONTACT + 500);

        Feedback f = Feedback.from("BUG", longMessage, longContact);

        assertEquals(Feedback.MAX_MESSAGE, f.message().length());
        assertEquals(Feedback.MAX_CONTACT, f.contact().length());
    }

    @Test
    void from_keepsContactWhenGiven() {
        Feedback f = Feedback.from("BUG", "broke", "  me@example.com ");
        assertEquals("me@example.com", f.contact());
        assertTrue(f.message().length() > 0);
    }
}
