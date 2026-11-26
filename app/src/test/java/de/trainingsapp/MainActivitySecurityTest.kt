package de.trainingsapp

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** Regression tests of the actual Activity callback and generated export bytes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivitySecurityTest {
    private lateinit var app: Application
    private var controller: ActivityController<MainActivity>? = null
    private var workoutId = 0L

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("training.db")
        app.getSharedPreferences("unfinished_workout", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("software_updates", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("auto_check", false).commit()
        Store(app).use { store ->
            val exercise = store.exercises().first { it.kind == "reps" }
            workoutId = store.saveSession("A", false, "", 0,
                listOf(ExerciseDraft(exercise, mutableListOf(SetDraft("10", "8", "", "2", true, "ONLY_SELECTED_WORKOUT")))),
                timestamp = 1_700_000_000_000L)
            store.saveSession("B", false, "", 0,
                listOf(ExerciseDraft(exercise, mutableListOf(SetDraft("12", "6", "", "1", true, "PRIVATE_OTHER_WORKOUT")))),
                timestamp = 1_700_100_000_000L)
            store.addBodyWeight(78.0, 1_700_100_000_000L)
        }
    }

    @After fun cleanUp() { controller?.close() }

    private fun activity(): MainActivity {
        if (controller == null) controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        return controller!!.get()
    }

    private fun begin(format: String, id: Long? = null) {
        val method = MainActivity::class.java.getDeclaredMethod("saveFile", String::class.java,
            String::class.java, String::class.java, java.lang.Long::class.java)
        method.isAccessible = true
        method.invoke(activity(), format, "application/json", "training.json", id)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, shadowOf(activity()).nextStartedActivityForResult.intent.action)
    }

    private fun result(resultCode: Int = Activity.RESULT_OK): ByteArray {
        val uri = Uri.parse("content://audit.test/selected-document")
        val output = ByteArrayOutputStream()
        shadowOf(activity().contentResolver).registerOutputStream(uri, output)
        val method = MainActivity::class.java.getDeclaredMethod("onActivityResult",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
        method.isAccessible = true
        method.invoke(activity(), 501, resultCode, Intent().setData(uri))
        return output.toByteArray()
    }

    @Test fun singleWorkoutScopeSurvivesActivityRecreation() {
        begin("workout_json", workoutId)
        controller!!.recreate()
        val text = result().toString(Charsets.UTF_8)
        val json = JSONObject(text)
        assertEquals("TrainingsappSingleWorkout", json.getString("format"))
        assertEquals(workoutId, json.getJSONObject("training").getLong("id"))
        assertTrue(text.contains("ONLY_SELECTED_WORKOUT"))
        assertFalse(text.contains("PRIVATE_OTHER_WORKOUT"))
        assertFalse(json.has("body_weights"))
        assertFalse(text.startsWith("PK"))
    }

    @Test fun backupFormatSurvivesActivityRecreation() {
        begin("json")
        controller!!.recreate()
        val json = JSONObject(result().toString(Charsets.UTF_8))
        assertEquals("TrainingsappBackup", json.getString("format"))
        assertEquals(2, json.getJSONArray("sessions").length())
        assertEquals(1, json.getJSONArray("body_weights").length())
    }

    @Test fun savedStateAlsoRestoresAfterProcessStyleRestart() {
        begin("workout_json", workoutId)
        val state = Bundle()
        controller!!.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup(state)
        val json = JSONObject(result().toString(Charsets.UTF_8))
        assertEquals("TrainingsappSingleWorkout", json.getString("format"))
        assertEquals(workoutId, json.getJSONObject("training").getLong("id"))
    }

    @Test fun missingExportRequestNeverWritesFullDatabase() {
        activity()
        assertEquals(0, result().size)
    }

    @Test fun consumedOrCancelledRequestCannotBeReused() {
        begin("workout_json", workoutId)
        assertEquals(0, result(Activity.RESULT_CANCELED).size)
        assertEquals(0, result().size)
        begin("workout_json", workoutId)
        assertTrue(result().isNotEmpty())
        assertEquals(0, result().size)
    }

    @Test fun malformedSavedExportFailsClosed() {
        val state = Bundle().apply {
            putString("pending_export_format", "workout_json")
            putLong("pending_export_workout", -1)
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup(state)
        assertEquals(0, result().size)
    }

    @Test fun clipboardMarksTrainingTextAsSensitive() {
        val method = MainActivity::class.java.getDeclaredMethod("copyToClipboard", String::class.java, String::class.java)
        method.isAccessible = true
        method.invoke(activity(), "Training", "Private training data")
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("Private training data", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
    }

    private fun writeDraft(generation: String? = null) {
        val id = Store(app).use { it.exercises().first().id }
        val draft = JSONObject().put("plan", "A").put("phaseId", 1L)
            .put("entries", JSONArray().put(JSONObject().put("exerciseId", id)
                .put("sets", JSONArray().put(JSONObject().put("weight", "10")
                    .put("reps", "8").put("completed", true)))))
        if (generation != null) draft.put("dataGeneration", generation)
        app.getSharedPreferences("unfinished_workout", Context.MODE_PRIVATE).edit()
            .putString("active", draft.toString()).commit()
    }

    private fun activeDraft(): Any? {
        val field = MainActivity::class.java.getDeclaredField("draft")
        field.isAccessible = true
        return field.get(activity())
    }

    @Test fun legacyDraftSurvivesOrdinaryAppUpdate() {
        writeDraft()
        assertNotNull(activeDraft())
    }

    @Test fun committedRestoreRejectsOldDraftEvenIfProcessDiedBeforePreferenceCleanup() {
        writeDraft("0")
        Store(app).use { store ->
            val backup = store.backupJson()
            store.restoreJson(backup)
            assertNotEquals("0", store.dataGeneration())
        }
        // Simulate a process death immediately after the DB commit: old preferences
        // are still present, and exercise IDs in the restored backup can coincide.
        assertNull(activeDraft())
        assertFalse(app.getSharedPreferences("unfinished_workout", Context.MODE_PRIVATE).contains("active"))
    }

    @Test fun draftCreatedAfterRestoreCanStillResume() {
        val generation = Store(app).use { store ->
            store.restoreJson(store.backupJson())
            store.dataGeneration()
        }
        writeDraft(generation)
        assertNotNull(activeDraft())
    }
}
