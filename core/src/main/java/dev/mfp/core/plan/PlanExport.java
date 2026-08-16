package dev.mfp.core.plan;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A whole plan as one paste-able string — the sharing story {@code PLAN.md} §11 always wanted.
 *
 * <pre>
 *   MFP1:H4sIAAAAAAAA_6tWKkstKs7Mz1OyUvJIzcnJVwjPL8pJUbJSKijKz0pNLlGqBQD...
 * </pre>
 *
 * <p><b>The same JSON the file holds</b>, through {@link PlanCodec}, then gzipped and base64'd. Not
 * a second format: an export that opened as a subtly different plan from the one saved beside it
 * would be the worst kind of bug in a tool whose whole product is numbers.
 *
 * <p><b>URL-safe base64, unpadded.</b> The string is going into chat, a wiki, a Discord message and
 * occasionally a URL; {@code +}, {@code /} and {@code =} survive none of those reliably. Whitespace
 * anywhere in it is stripped on the way back in, because a string that has been through a chat
 * window has usually been wrapped.
 *
 * <p><b>The prefix is a version, and it is checked.</b> {@code MFP1:} says which shape follows, so a
 * later format can be told apart from a corrupted one and an old export stays readable — the failure
 * message is the difference between "this is not an MFP plan" and "this is a newer MFP plan than
 * this build understands", and those want opposite responses from the user.
 */
public final class PlanExport {

    /** The magic prefix and its format number. Bump the number, never the letters. */
    public static final String MAGIC = "MFP1:";

    private PlanExport() {}

    /** What an import produced: the plan, and everything this world could not honour. */
    public record Imported(Plan plan, List<String> problems) {

        public Imported {
            problems = List.copyOf(problems);
        }

        public boolean isClean() {
            return problems.isEmpty();
        }
    }

    /** A string that is not an MFP plan, or is one this build cannot read. */
    public static final class PlanFormatException extends RuntimeException {
        public PlanFormatException(String message) {
            super(message);
        }

        public PlanFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** This plan as a string, ready to paste. */
    public static String export(Plan plan) {
        JsonObject json = PlanCodec.write(plan);
        byte[] raw = json.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(raw);
        } catch (IOException e) {
            // Nothing here touches a device; an in-memory stream failing is a bug, not a condition.
            throw new IllegalStateException("could not compress a plan", e);
        }
        return MAGIC + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed.toByteArray());
    }

    /** The plan a string names, with anything this world does not have accepted as it is. */
    public static Imported parse(String text) {
        return parse(text, recipeId -> true);
    }

    /**
     * The plan a string names, dropping what this world cannot honour and saying what it dropped.
     *
     * <p><b>An unknown recipe is a report, not a failure.</b> A plan built in another pack — or in
     * this one before an update — names recipes that are not here, and it is still worth ninety per
     * cent of what it was: the targets, the rates, the machines and every other pin. So the pin goes,
     * the scorer chooses for that item again, and the name of the missing recipe comes back in
     * {@link Imported#problems()} for the user to see. What does throw is a string that is not a plan
     * at all, because there is nothing to salvage and no honest half-answer to give.
     *
     * @param knownRecipe whether a recipe id exists in this world, usually
     *                    {@code index::hasRecipe}
     */
    public static Imported parse(String text, Predicate<String> knownRecipe) {
        if (text == null || text.isBlank()) {
            throw new PlanFormatException("there is no plan string here");
        }
        String cleaned = text.replaceAll("\\s+", "");
        if (!cleaned.startsWith(MAGIC)) {
            if (cleaned.regionMatches(true, 0, "MFP", 0, 3)) {
                throw new PlanFormatException("that is an MFP plan in a format this build does not "
                        + "read; this one understands " + MAGIC.substring(0, MAGIC.length() - 1));
            }
            throw new PlanFormatException("that is not an MFP plan string - they start with " + MAGIC);
        }

        byte[] compressed;
        try {
            compressed = Base64.getUrlDecoder().decode(cleaned.substring(MAGIC.length()));
        } catch (IllegalArgumentException e) {
            throw new PlanFormatException("that plan string is damaged - it is not valid base64, so "
                    + "some of it was probably lost on the way here", e);
        }

        String json;
        try (InputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            json = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PlanFormatException("that plan string is damaged - it did not decompress", e);
        }

        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new PlanFormatException("that plan string does not contain a plan", e);
        }
        if (!root.isJsonObject()) {
            throw new PlanFormatException("that plan string does not contain a plan");
        }

        List<String> problems = new ArrayList<>();
        Plan plan;
        try {
            plan = PlanCodec.read(root.getAsJsonObject(), knownRecipe,
                    PlanCodec.Problems.collectingInto(problems));
        } catch (RuntimeException e) {
            throw new PlanFormatException("that plan string could not be read: " + e.getMessage(), e);
        }
        return new Imported(plan, problems);
    }
}
