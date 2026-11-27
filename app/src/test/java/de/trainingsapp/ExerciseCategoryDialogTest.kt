package de.trainingsapp

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.EditText
import android.widget.Spinner
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
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExerciseCategoryDialogTest {
    private lateinit var app: Application
    private lateinit var controller: ActivityController<MainActivity>

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("training.db")
        app.getSharedPreferences("unfinished_workout", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("software_updates", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("auto_check", false).commit()
        Store(app).use { store ->
            store.upsertExercise(Exercise(0L, "Archived example", "Eigene Kategorie", "reps", "", false, true))
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    @After fun cleanUp() { controller.close() }

    private fun open(old: Exercise? = null): AlertDialog {
        val method = MainActivity::class.java.getDeclaredMethod("exerciseDialog", Exercise::class.java)
        method.isAccessible = true
        method.invoke(controller.get(), old)
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun category(dialog: AlertDialog): Spinner =
        dialog.window!!.decorView.findViewWithTag<Spinner>("exercise_category")

    private fun select(dialog: AlertDialog, label: String) {
        val spinner = category(dialog)
        val position = (0 until spinner.count).first { spinner.getItemAtPosition(it).toString() == label }
        spinner.setSelection(position)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(label, spinner.selectedItem.toString())
    }

    private fun name(dialog: AlertDialog, value: String) {
        dialog.window!!.decorView.findViewWithTag<EditText>("exercise_name").setText(value)
    }

    private fun newName(dialog: AlertDialog, value: String) {
        dialog.window!!.decorView.findViewWithTag<EditText>("exercise_new_category").setText(value)
    }

    private fun save(dialog: AlertDialog) {
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun saved(name: String): Exercise? = Store(app).use { store ->
        store.exercises(includeArchived = true).firstOrNull { it.name == name }
    }

    @Test fun selectsExistingCategoryWithoutTyping() {
        val dialog = open()
        name(dialog, "TYI Test")
        select(dialog, "Beweglichkeit & Stabilität")
        save(dialog)
        assertEquals("Beweglichkeit & Stabilität", saved("TYI Test")?.category)
        assertFalse(dialog.isShowing)
    }

    @Test fun includesCategoriesWithOnlyArchivedExercises() {
        val dialog = open()
        name(dialog, "New archived-category exercise")
        select(dialog, "Eigene Kategorie")
        save(dialog)
        assertEquals("Eigene Kategorie", saved("New archived-category exercise")?.category)
    }

    @Test fun createsNewCategoryAndReusesItInLaterDialog() {
        val first = open()
        name(first, "Test exercise one")
        select(first, "+ Neue Kategorie anlegen")
        newName(first, "Schulterstabilität")
        save(first)
        assertEquals("Schulterstabilität", saved("Test exercise one")?.category)

        val second = open()
        name(second, "Test exercise two")
        select(second, "Schulterstabilität")
        save(second)
        assertEquals("Schulterstabilität", saved("Test exercise two")?.category)
    }

    @Test fun noSelectionOrBlankNewCategoryDoesNotSave() {
        val dialog = open()
        name(dialog, "Invalid example")
        save(dialog)
        assertNull(saved("Invalid example"))
        assertTrue(dialog.isShowing)
        select(dialog, "+ Neue Kategorie anlegen")
        newName(dialog, "  ")
        save(dialog)
        assertNull(saved("Invalid example"))
        assertTrue(dialog.isShowing)
        dialog.dismiss()
    }

    @Test fun editingPreselectsExistingCategoryAndKeepsExerciseId() {
        val old = saved("Archived example")!!
        val dialog = open(old)
        assertEquals("Eigene Kategorie", category(dialog).selectedItem.toString())
        name(dialog, "Edited example")
        select(dialog, "Rumpf")
        save(dialog)
        val edited = saved("Edited example")!!
        assertEquals(old.id, edited.id)
        assertEquals("Rumpf", edited.category)
        assertTrue(edited.archived)
    }

    @Test fun duplicateNewCategoryUsesExistingSpelling() {
        val dialog = open()
        name(dialog, "Duplicate example")
        select(dialog, "+ Neue Kategorie anlegen")
        newName(dialog, " rumpf ")
        save(dialog)
        assertEquals("Rumpf", saved("Duplicate example")?.category)
    }
}
