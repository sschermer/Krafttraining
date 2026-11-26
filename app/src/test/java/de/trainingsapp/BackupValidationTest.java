package de.trainingsapp;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.CancellationException;

public class BackupValidationTest {
    private static JSONObject row(JSONObject root, String table) throws JSONException {
        return root.getJSONArray(table).getJSONObject(0);
    }
    private static void rejects(JSONObject root) throws JSONException { rejects(root.toString()); }
    private static void rejects(String text) {
        try {
            BackupValidation.validateAndNormalize(text);
            fail("Malformed or unsafe backup accepted");
        } catch (IllegalArgumentException | JSONException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test public void preservesVersion3OptionalNullsAndUserText() throws Exception {
        JSONObject valid = BackupValidation.validateAndNormalize(BackupFixtures.complete().toString());
        assertEquals("Technik gut; = bleibt Text", row(valid, "training_sets").getString("note"));
        assertTrue(row(valid, "training_sets").isNull("seconds"));
        assertTrue(row(valid, "training_sets").isNull("rir"));
        assertEquals(78.4, row(valid, "body_weights").getDouble("kg"), 0.0);
    }

    @Test public void normalizesBothHistoricalFormatsWithoutLosingRecords() throws Exception {
        for (int version : new int[]{1, 2}) {
            JSONObject valid = BackupValidation.validateAndNormalize(BackupFixtures.legacy(version).toString());
            assertEquals(3, valid.getInt("version"));
            assertEquals(1, row(valid, "plans").getLong("phase_id"));
            assertEquals(1, row(valid, "sessions").getLong("phase_id"));
            assertEquals("", row(valid, "training_sets").getString("note"));
            assertEquals(30.5, row(valid, "training_sets").getDouble("weight"), 0.0);
            assertEquals(0, valid.getJSONArray("exercise_swaps").length());
        }
    }

    @Test public void acceptsDefaultableNullColumnsAndBooleanFlags() throws Exception {
        JSONObject root = BackupFixtures.complete();
        row(root, "exercises").put("equipment", JSONObject.NULL).put("archived", false).put("per_side", true);
        row(root, "training_sets").put("note", JSONObject.NULL);
        JSONObject valid = BackupValidation.validateAndNormalize(root.toString());
        assertEquals("", row(valid, "exercises").getString("equipment"));
        assertEquals(1, row(valid, "exercises").getInt("per_side"));
        assertEquals("", row(valid, "training_sets").getString("note"));
    }

    @Test public void preservesArchivedExercisesAndRecordedSwaps() throws Exception {
        JSONObject root = BackupFixtures.complete();
        root.getJSONArray("exercises").put(new JSONObject().put("id", 11).put("name", "Ersatzübung")
            .put("category", "Eigene").put("kind", "time").put("archived", true));
        root.getJSONArray("exercise_swaps").put(new JSONObject().put("id", 60).put("session_id", 30)
            .put("original_exercise_id", 10).put("replacement_exercise_id", 11)
            .put("position", 0).put("reason", "Knie geschont"));
        JSONObject valid = BackupValidation.validateAndNormalize(root.toString());
        assertEquals(1, valid.getJSONArray("exercises").getJSONObject(1).getInt("archived"));
        assertEquals("Knie geschont", valid.getJSONArray("exercise_swaps").getJSONObject(0).getString("reason"));
    }

    @Test public void boundsTheWholePlanNotOnlyOneExercisesSetCount() throws Exception {
        JSONObject root = BackupFixtures.complete();
        JSONArray plans = root.getJSONArray("plans");
        plans.getJSONObject(0).put("target_sets", 100);
        for (int i = 1; i < 11; i++) {
            plans.put(new JSONObject(plans.getJSONObject(0).toString()).put("id", 20 + i).put("position", i));
        }
        rejects(root);
    }

    @Test public void rejectsMissingReferencesInEveryRelationshipFamily() throws Exception {
        String[][] fields = {{"plans", "exercise_id"}, {"plans", "phase_id"},
            {"sessions", "phase_id"}, {"training_sets", "session_id"}, {"training_sets", "exercise_id"}};
        for (String[] field : fields) {
            JSONObject root = BackupFixtures.complete();
            row(root, field[0]).put(field[1], 999);
            rejects(root);
        }
        JSONObject root = BackupFixtures.complete();
        root.getJSONArray("exercise_swaps").put(new JSONObject().put("id", 60).put("session_id", 30)
            .put("original_exercise_id", 10).put("replacement_exercise_id", 999).put("position", 0));
        rejects(root);
    }

    @Test public void rejectsDuplicateIdsWrongTypesAndInvalidRanges() throws Exception {
        JSONObject duplicate = BackupFixtures.complete();
        duplicate.getJSONArray("sessions").put(new JSONObject(row(duplicate, "sessions").toString()));
        rejects(duplicate);
        JSONObject type = BackupFixtures.complete();
        row(type, "body_weights").put("kg", "78.4");
        rejects(type);
        JSONObject fraction = BackupFixtures.complete();
        row(fraction, "training_sets").put("reps", 12.5);
        rejects(fraction);
        JSONObject count = BackupFixtures.complete();
        row(count, "plans").put("target_sets", Integer.MAX_VALUE);
        rejects(count);
        JSONObject phase = BackupFixtures.complete();
        row(phase, "sessions").put("plan", "C");
        rejects(phase);
        JSONObject nested = BackupFixtures.complete();
        row(nested, "exercises").put("name", new JSONObject().put("text", "Rudern"));
        rejects(nested);
    }

    @Test public void rejectsFutureVersionsMissingTablesAndTrailingDocuments() throws Exception {
        rejects(BackupFixtures.complete().put("version", 4));
        JSONObject missing = BackupFixtures.complete();
        missing.remove("sessions");
        rejects(missing);
        rejects(BackupFixtures.complete().put("sessions", "wrong type"));
        rejects(BackupFixtures.complete().toString() + " {}");
    }

    @Test public void rejectsExcessiveNestingBeforeTheJsonParserRuns() {
        rejects("{\"extra\":" + "[".repeat(20_000) + "0" + "]".repeat(20_000) + "}");
        // Lenient JSONTokener comments or unquoted text must not hide nesting.
        rejects("{/*\"*/\"extra\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}");
        rejects("{\"extra\":abc\", \"other\":[[[0]]]}");
    }

    @Test public void boundsUtf8BytesRatherThanOnlyJavaCharacterCount() {
        String multibyte = "\"" + "€".repeat(BackupValidation.MAX_BACKUP_BYTES / 3 + 1) + "\"";
        rejects(multibyte);
    }

    @Test public void boundsHugeArraysAndObjectsBeforeAllocatingJsonContainers() {
        rejects("{\"extra\":[" + "0,".repeat(BackupValidation.MAX_TOTAL_ROWS + 1) + "0]}");
        StringBuilder object = new StringBuilder("{");
        for (int i = 0; i < 40; i++) object.append("\"field").append(i).append("\":0,");
        object.append("\"last\":0}");
        rejects(object.toString());
    }

    @Test public void stopsBeforeParsingWhenImportWasCancelled() throws Exception {
        String text = BackupFixtures.complete().toString();
        Thread.currentThread().interrupt();
        try {
            BackupValidation.validateAndNormalize(text);
            fail("Expected cancellation");
        } catch (CancellationException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void acceptsBomAndEscapedQuotesWithoutMiscountingBrackets() throws Exception {
        JSONObject root = BackupFixtures.complete();
        row(root, "training_sets").put("note", "Text \\\" [[[ ]]] mit Emoji 🏋 und Umlauten äöü");
        JSONObject valid = BackupValidation.validateAndNormalize("\uFEFF" + root.toString());
        assertEquals(row(root, "training_sets").getString("note"), row(valid, "training_sets").getString("note"));
    }
}
