package de.trainingsapp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/** Validates an entire backup before any live database rows are replaced. */
public final class BackupValidation {
    public static final int MAX_BACKUP_BYTES = 32 * 1024 * 1024;
    public static final int MAX_TARGET_SETS = 100;
    public static final int MAX_TOTAL_ROWS = 250_000;
    private static final long MAX_ID = 1_000_000_000_000L;
    private static final long MIN_TIMESTAMP = -2_208_988_800_000L; // 1900-01-01
    private static final long MAX_TIMESTAMP = 253_402_300_799_999L; // 9999-12-31
    private static final String[] TABLES = {"training_phases", "exercises", "plans",
        "sessions", "training_sets", "exercise_swaps", "body_weights"};
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private BackupValidation() {}

    public static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Wiederherstellung abgebrochen. Vorhandene Daten bleiben erhalten.");
        }
    }

    /** Returns a complete version-3 structure; v1/v2 defaults never discard supplied data. */
    public static JSONObject validateAndNormalize(String text) throws JSONException {
        checkInterrupted();
        checkDocumentBounds(text);
        JSONTokener tokener = new JSONTokener(text.startsWith("\uFEFF") ? text.substring(1) : text);
        Object value = tokener.nextValue();
        require(value instanceof JSONObject && tokener.nextClean() == 0,
            "Keine gültige JSON-Sicherung.");
        JSONObject root = (JSONObject) value;
        require("TrainingsappBackup".equals(root.opt("format")), "Keine gültige Trainingsapp-Sicherung.");
        int version = (int) integer(root, "version", 1, 3, null, "Sicherung");
        if (version == 1 && !root.has("training_phases")) {
            root.put("training_phases", new JSONArray().put(new JSONObject()
                .put("id", 1).put("name", "Trainingsphase 1")
                .put("started_at", System.currentTimeMillis()).put("ended_at", JSONObject.NULL)));
        }
        if (version < 3 && !root.has("exercise_swaps")) root.put("exercise_swaps", new JSONArray());

        Map<String, Set<Long>> ids = new HashMap<>();
        int total = 0;
        for (String table : TABLES) {
            checkInterrupted();
            Object raw = root.opt(table);
            require(raw instanceof JSONArray, "Tabelle fehlt oder ist ungültig: " + table);
            JSONArray rows = (JSONArray) raw;
            total += rows.length();
            require(total <= MAX_TOTAL_ROWS, "Die Sicherung enthält zu viele Datensätze.");
            Set<Long> tableIds = new HashSet<>();
            ids.put(table, tableIds);
            for (int i = 0; i < rows.length(); i++) {
                checkInterrupted();
                require(rows.opt(i) instanceof JSONObject, "Ungültiger Datensatz in " + table + ".");
                JSONObject row = rows.getJSONObject(i);
                long id = integer(row, "id", 1, MAX_ID, null, table);
                require(tableIds.add(id), "Doppelte Datensatz-ID in " + table + ".");
                validateRow(table, row, version);
            }
        }

        Map<String, Integer> planSizes = new HashMap<>();
        JSONArray plans = root.getJSONArray("plans");
        for (int i = 0; i < plans.length(); i++) {
            checkInterrupted();
            JSONObject row = plans.getJSONObject(i);
            reference(row, "exercise_id", ids.get("exercises"), "plans");
            reference(row, "phase_id", ids.get("training_phases"), "plans");
            String key = row.getLong("phase_id") + ":" + row.getString("plan");
            int size = planSizes.getOrDefault(key, 0) + Math.max(2, row.getInt("target_sets"));
            require(size <= 1_000, "Ein Trainingsplan enthält zu viele vorgesehene Sätze.");
            planSizes.put(key, size);
        }
        JSONArray sessions = root.getJSONArray("sessions");
        for (int i = 0; i < sessions.length(); i++) {
            checkInterrupted();
            reference(sessions.getJSONObject(i), "phase_id", ids.get("training_phases"), "sessions");
        }
        JSONArray sets = root.getJSONArray("training_sets");
        Map<Long, Integer> sessionSizes = new HashMap<>();
        for (int i = 0; i < sets.length(); i++) {
            checkInterrupted();
            JSONObject row = sets.getJSONObject(i);
            reference(row, "session_id", ids.get("sessions"), "training_sets");
            reference(row, "exercise_id", ids.get("exercises"), "training_sets");
            long sessionId = row.getLong("session_id");
            int size = sessionSizes.getOrDefault(sessionId, 0) + 1;
            require(size <= 5_000, "Eine Trainingseinheit enthält zu viele Sätze.");
            sessionSizes.put(sessionId, size);
        }
        JSONArray swaps = root.getJSONArray("exercise_swaps");
        for (int i = 0; i < swaps.length(); i++) {
            checkInterrupted();
            JSONObject row = swaps.getJSONObject(i);
            reference(row, "session_id", ids.get("sessions"), "exercise_swaps");
            reference(row, "original_exercise_id", ids.get("exercises"), "exercise_swaps");
            reference(row, "replacement_exercise_id", ids.get("exercises"), "exercise_swaps");
        }
        root.put("version", 3);
        checkInterrupted();
        return root;
    }

    private static void validateRow(String table, JSONObject row, int version) throws JSONException {
        switch (table) {
            case "training_phases":
                string(row, "name", 1_024, null, table);
                timestamp(row, "started_at", false, table);
                timestamp(row, "ended_at", true, table);
                break;
            case "exercises":
                string(row, "name", 1_024, null, table);
                string(row, "category", 1_024, null, table);
                String kind = string(row, "kind", 16, null, table);
                require("reps".equals(kind) || "time".equals(kind), "Ungültige Übungsart.");
                string(row, "equipment", 2_048, "", table);
                bool(row, "per_side", 1, table);
                bool(row, "archived", 0, table);
                break;
            case "plans":
                plan(row, table);
                integer(row, "exercise_id", 1, MAX_ID, null, table);
                integer(row, "phase_id", 1, MAX_ID, version == 1 ? 1L : null, table);
                integer(row, "position", 0, MAX_TOTAL_ROWS, null, table);
                integer(row, "target_sets", 1, MAX_TARGET_SETS, 2L, table);
                break;
            case "sessions":
                timestamp(row, "timestamp", false, table);
                plan(row, table);
                integer(row, "phase_id", 1, MAX_ID, version == 1 ? 1L : null, table);
                bool(row, "cardio", 0, table);
                string(row, "cardio_type", 2_048, "", table);
                integer(row, "cardio_minutes", 0, 1_000_000, 0L, table);
                break;
            case "training_sets":
                integer(row, "session_id", 1, MAX_ID, null, table);
                integer(row, "exercise_id", 1, MAX_ID, null, table);
                integer(row, "set_number", 1, 100_000, null, table);
                optionalReal(row, "weight", 0, 100_000, table);
                optionalInteger(row, "reps", 0, 1_000_000, table);
                optionalInteger(row, "seconds", 0, 31_536_000, table);
                optionalReal(row, "rir", 0, 10_000, table);
                string(row, "note", 16_384, "", table);
                break;
            case "exercise_swaps":
                integer(row, "session_id", 1, MAX_ID, null, table);
                integer(row, "original_exercise_id", 1, MAX_ID, null, table);
                integer(row, "replacement_exercise_id", 1, MAX_ID, null, table);
                string(row, "reason", 16_384, "", table);
                integer(row, "position", 0, MAX_TOTAL_ROWS, null, table);
                break;
            case "body_weights":
                timestamp(row, "timestamp", false, table);
                real(row, "kg", Double.MIN_VALUE, 500, table);
                break;
            default: throw new IllegalArgumentException("Unbekannte Tabelle.");
        }
    }

    private static void plan(JSONObject row, String table) throws JSONException {
        String plan = string(row, "plan", 1, null, table);
        require("A".equals(plan) || "B".equals(plan), "Ungültiger Trainingsplan.");
    }

    private static void timestamp(JSONObject row, String field, boolean nullable, String table) throws JSONException {
        if (nullable && row.isNull(field)) { row.put(field, JSONObject.NULL); return; }
        integer(row, field, MIN_TIMESTAMP, MAX_TIMESTAMP, null, table);
    }

    private static String string(JSONObject row, String field, int maxLength, String defaultValue,
                                 String table) throws JSONException {
        Object value = row.opt(field);
        if (value == null || value == JSONObject.NULL) {
            require(defaultValue != null, "Textfeld fehlt: " + table + "." + field);
            value = defaultValue;
        }
        require(value instanceof String, "Ungültiges Textfeld: " + table + "." + field);
        String result = (String) value;
        require(result.length() <= maxLength, "Textfeld ist zu lang: " + table + "." + field);
        require(result.indexOf('\0') < 0, "Textfeld enthält ein ungültiges Zeichen: " + table + "." + field);
        row.put(field, result);
        return result;
    }

    private static long integer(JSONObject row, String field, long minimum, long maximum,
                                Long defaultValue, String table) throws JSONException {
        Object value = row.opt(field);
        if (value == null || value == JSONObject.NULL) {
            require(defaultValue != null, "Zahlenfeld fehlt: " + table + "." + field);
            value = defaultValue;
        }
        require(value instanceof Number, "Ungültige Ganzzahl: " + table + "." + field);
        double d = ((Number) value).doubleValue();
        long result = ((Number) value).longValue();
        require(Double.isFinite(d) && d == (double) result && d >= minimum && d <= maximum,
            "Zahlenwert außerhalb des zulässigen Bereichs: " + table + "." + field);
        row.put(field, result);
        return result;
    }

    private static void bool(JSONObject row, String field, int defaultValue, String table) throws JSONException {
        Object value = row.opt(field);
        if (value instanceof Boolean) row.put(field, (Boolean) value ? 1 : 0);
        integer(row, field, 0, 1, (long) defaultValue, table);
    }

    private static void optionalInteger(JSONObject row, String field, long min, long max, String table) throws JSONException {
        if (row.isNull(field)) row.put(field, JSONObject.NULL);
        else integer(row, field, min, max, null, table);
    }

    private static void optionalReal(JSONObject row, String field, double min, double max, String table) throws JSONException {
        if (row.isNull(field)) row.put(field, JSONObject.NULL);
        else real(row, field, min, max, table);
    }

    private static void real(JSONObject row, String field, double min, double max, String table) throws JSONException {
        Object value = row.opt(field);
        require(value instanceof Number, "Ungültige Zahl: " + table + "." + field);
        double number = ((Number) value).doubleValue();
        require(Double.isFinite(number) && number >= min && number <= max,
            "Zahlenwert außerhalb des zulässigen Bereichs: " + table + "." + field);
        row.put(field, number);
    }

    private static void reference(JSONObject row, String field, Set<Long> ids, String table) throws JSONException {
        require(ids.contains(row.getLong(field)), "Fehlender verknüpfter Datensatz: " + table + "." + field);
    }

    /** Bound bytes and nesting before JSONTokener can allocate recursively. */
    private static void checkDocumentBounds(String text) {
        require(text != null && !text.isEmpty(), "Die Sicherung ist leer.");
        require(text.length() <= MAX_BACKUP_BYTES, "Die Sicherung ist größer als 32 MiB.");
        long bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            if ((i & 4095) == 0) checkInterrupted();
            char c = text.charAt(i);
            if (c <= 0x7f) bytes++;
            else if (c <= 0x7ff) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                     && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4; i++;
            } else bytes += 3;
            require(bytes <= MAX_BACKUP_BYTES, "Die Sicherung ist größer als 32 MiB.");
        }
        int depth = 0;
        int objects = 0;
        int arraySeparators = 0;
        char[] containers = new char[16];
        int[] properties = new int[16];
        boolean quoted = false;
        boolean escaped = false;
        int literalStart = -1;
        for (int i = text.startsWith("\uFEFF") ? 1 : 0; i < text.length(); i++) {
            if ((i & 4095) == 0) checkInterrupted();
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') {
                require(literalStart == -1, "Ungültige JSON-Sicherung.");
                quoted = true;
            } else if (c == '{' || c == '[' || c == '}' || c == ']' || c == ',' || c == ':'
                       || c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                if (literalStart != -1) {
                    checkLiteral(text, literalStart, i);
                    literalStart = -1;
                }
                if (c == '{' || c == '[') {
                    require(depth < containers.length, "Die Sicherung enthält zu tief verschachtelte Daten.");
                    containers[depth] = c;
                    properties[depth] = 0;
                    depth++;
                    if (c == '{') require(++objects <= MAX_TOTAL_ROWS + 1,
                        "Die Sicherung enthält zu viele Datensätze.");
                } else if (c == '}' || c == ']') {
                    require(depth > 0 && containers[depth - 1] == (c == '}' ? '{' : '['),
                        "Ungültige JSON-Struktur.");
                    depth--;
                } else if (c == ',' && depth > 0 && containers[depth - 1] == '[') {
                    require(++arraySeparators <= MAX_TOTAL_ROWS,
                        "Die Sicherung enthält zu viele Datensätze.");
                } else if (c == ':' && depth > 0 && containers[depth - 1] == '{') {
                    require(++properties[depth - 1] <= 32,
                        "Ein Datensatz enthält zu viele Felder.");
                }
            } else if (literalStart == -1) {
                literalStart = i;
            }
        }
        if (literalStart != -1) checkLiteral(text, literalStart, text.length());
        require(depth == 0 && !quoted, "Unvollständige JSON-Sicherung.");
    }

    private static void checkLiteral(String text, int start, int end) {
        // Backups produced by the app are standard JSON. Reject JSONTokener's
        // comments/unquoted strings so they cannot hide nesting from this guard.
        require(end - start <= 64, "Ungültiger JSON-Zahlenwert.");
        String token = text.substring(start, end);
        require("true".equals(token) || "false".equals(token) || "null".equals(token)
            || JSON_NUMBER.matcher(token).matches(),
            "Ungültige JSON-Sicherung.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
