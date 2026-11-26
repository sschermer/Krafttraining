package de.trainingsapp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class BackupFixtures {
    private BackupFixtures() {}
    static JSONObject complete() throws JSONException {
        return new JSONObject().put("format", "TrainingsappBackup").put("version", 3)
            .put("training_phases", new JSONArray().put(new JSONObject()
                .put("id", 1).put("name", "Meine Phase").put("started_at", 1_700_000_000_000L)
                .put("ended_at", JSONObject.NULL)))
            .put("exercises", new JSONArray().put(new JSONObject()
                .put("id", 10).put("name", "Rudern").put("category", "Oberkörper")
                .put("kind", "reps").put("equipment", "Kabelturm").put("per_side", 0)
                .put("archived", 0)))
            .put("plans", new JSONArray().put(new JSONObject().put("id", 20)
                .put("plan", "A").put("exercise_id", 10).put("position", 0)
                .put("target_sets", 2).put("phase_id", 1)))
            .put("sessions", new JSONArray().put(new JSONObject().put("id", 30)
                .put("timestamp", 1_700_000_050_000L).put("plan", "A").put("phase_id", 1)
                .put("cardio", 1).put("cardio_type", "Laufen").put("cardio_minutes", 12)))
            .put("training_sets", new JSONArray().put(new JSONObject().put("id", 40)
                .put("session_id", 30).put("exercise_id", 10).put("set_number", 1)
                .put("weight", 30.5).put("reps", 12).put("seconds", JSONObject.NULL)
                .put("rir", JSONObject.NULL).put("note", "Technik gut; = bleibt Text")))
            .put("exercise_swaps", new JSONArray())
            .put("body_weights", new JSONArray().put(new JSONObject().put("id", 50)
                .put("timestamp", 1_700_000_020_000L).put("kg", 78.4)));
    }

    static JSONObject legacy(int version) throws JSONException {
        JSONObject root = complete().put("version", version);
        if (version < 3) {
            root.remove("exercise_swaps");
            root.getJSONArray("training_sets").getJSONObject(0).remove("note");
        }
        if (version == 1) {
            root.remove("training_phases");
            root.getJSONArray("plans").getJSONObject(0).remove("phase_id");
            root.getJSONArray("sessions").getJSONObject(0).remove("phase_id");
        }
        return root;
    }
}
