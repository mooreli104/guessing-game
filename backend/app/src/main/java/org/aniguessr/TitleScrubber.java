package org.aniguessr;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Removes the title printed into a cover image.
 *
 * The detection itself runs in a Python sidecar ({@code scripts/scrub_service.py})
 * because it needs a scene-text detector. Tesseract was tried first and could not read
 * anime title logotypes at all -- stylised display type, often light-on-light over busy
 * artwork -- so it blanked random artwork instead and passed the leak through.
 *
 * The sidecar is long-lived: loading the detection model takes several seconds, and
 * ingest scrubs hundreds of covers. Images travel as files rather than over the pipe,
 * so the protocol stays plain text.
 *
 * Not thread-safe -- one request is in flight at a time -- which is fine because ingest
 * is a single-threaded batch job.
 */
public class TitleScrubber implements AutoCloseable {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Process process;
    private final BufferedWriter toPython;
    private final BufferedReader fromPython;

    public record ScrubResult(boolean accepted, String rejectReason, double boxedFraction) {}

    public TitleScrubber(File scriptPath) {
        String python = System.getenv("SCRUB_PYTHON");
        if (python == null || python.isBlank()) python = "python";
        try {
            ProcessBuilder builder = new ProcessBuilder(python, scriptPath.getAbsolutePath());
            // Model-loading chatter and warnings go to stderr; leave them on the console
            // so a broken environment is visible rather than silent.
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            this.process = builder.start();
            this.toPython = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.fromPython = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            String ready = fromPython.readLine();
            if (ready == null || !objectMapper.readTree(ready).path("ready").asBoolean()) {
                throw new IllegalStateException(
                    "scrub service did not start; check that easyocr is installed. Got: " + ready);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not start the scrub service with '" + python + "'", e);
        }
    }

    /**
     * Scrubs {@code cover} and, when accepted, writes the result to {@code destination}
     * as JPEG. When rejected, {@code destination} is left untouched.
     */
    public ScrubResult scrub(File cover, File destination) {
        try {
            String request = objectMapper.writeValueAsString(java.util.Map.of(
                "in", cover.getAbsolutePath(),
                "out", destination.getAbsolutePath()));
            toPython.write(request);
            toPython.newLine();
            toPython.flush();

            String line = fromPython.readLine();
            if (line == null) throw new IllegalStateException("scrub service stopped unexpectedly");

            JsonNode reply = objectMapper.readTree(line);
            boolean accepted = reply.path("accepted").asBoolean();
            JsonNode reason = reply.get("reason");
            return new ScrubResult(
                accepted,
                (reason == null || reason.isNull()) ? null : reason.asText(),
                reply.path("boxedPct").asDouble());
        } catch (IOException e) {
            throw new RuntimeException("Scrub request failed for " + cover.getName(), e);
        }
    }

    @Override
    public void close() {
        try {
            toPython.write("\n");   // blank line asks the service to stop
            toPython.flush();
            toPython.close();
        } catch (IOException ignored) {
            // Shutting down anyway.
        }
        process.destroy();
    }
}
