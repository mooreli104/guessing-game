import { useState } from "react";
import { API_URL } from "./GameContext";

// Matches Feedback.MAX_MESSAGE / MAX_CONTACT on the server, which caps them again --
// this is only here so the counter is honest and a long paste is not silently truncated
// after the fact.
const MAX_MESSAGE = 2000;
const MAX_CONTACT = 200;

type Kind = "BUG" | "FEEDBACK";
type Status = "editing" | "sending" | "sent" | "failed";

export default function FeedbackModal({ onClose }: { onClose: () => void }) {
  const [kind, setKind] = useState<Kind>("FEEDBACK");
  const [message, setMessage] = useState("");
  const [contact, setContact] = useState("");
  const [status, setStatus] = useState<Status>("editing");

  async function submit() {
    if (!message.trim() || status === "sending") return;
    setStatus("sending");
    try {
      const res = await fetch(API_URL + "/feedback", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ kind, message, contact }),
      });
      setStatus(res.ok ? "sent" : "failed");
    } catch {
      // Offline, or the server is down -- the same thing from here.
      setStatus("failed");
    }
  }

  return (
    // Clicking the backdrop closes; clicking the card must not, hence stopPropagation.
    <div className="backdrop" onClick={onClose}>
      <div className="panel modal" onClick={(e) => e.stopPropagation()}>
        {status === "sent" ? (
          <div className="stack">
            <div className="section-title">Thanks — that's been sent.</div>
            <button className="go wide" onClick={onClose}>
              Close
            </button>
          </div>
        ) : (
          <div className="stack">
            <div className="section-title">Tell us what's up</div>

            <div className="difficulty">
              <button
                className={kind === "FEEDBACK" ? "pick on" : "pick"}
                onClick={() => setKind("FEEDBACK")}
                aria-pressed={kind === "FEEDBACK"}
              >
                Feedback
              </button>
              <button
                className={kind === "BUG" ? "pick on" : "pick"}
                onClick={() => setKind("BUG")}
                aria-pressed={kind === "BUG"}
              >
                Bug report
              </button>
            </div>

            <div>
              <div className="field-label">
                {kind === "BUG" ? "What went wrong?" : "What would make this better?"}
              </div>
              <textarea
                rows={5}
                maxLength={MAX_MESSAGE}
                value={message}
                placeholder={
                  kind === "BUG"
                    ? "What you did, and what happened instead"
                    : "Anything at all"
                }
                onChange={(e) => setMessage(e.target.value)}
              />
              <div className="counter">
                {message.length}/{MAX_MESSAGE}
              </div>
            </div>

            <div>
              <div className="field-label">Contact (optional)</div>
              <input
                placeholder="Email or Discord, if you want a reply"
                maxLength={MAX_CONTACT}
                value={contact}
                onChange={(e) => setContact(e.target.value)}
              />
            </div>

            <button
              className="go wide"
              onClick={submit}
              disabled={!message.trim() || status === "sending"}
            >
              {status === "sending" ? "Sending…" : "Send"}
            </button>

            {status === "failed" && (
              <div className="hint">That didn't go through. Try again in a moment.</div>
            )}

            <button className="quiet" onClick={onClose}>
              Cancel
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
