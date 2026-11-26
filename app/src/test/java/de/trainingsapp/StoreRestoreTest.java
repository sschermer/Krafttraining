package de.trainingsapp;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.concurrent.CancellationException;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class StoreRestoreTest {
    private Context context;
    private Store store;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("training.db");
        store = new Store(context);
        store.addBodyWeight(77.2, 1_700_000_000_000L);
    }
    @After public void close() {
        Thread.interrupted();
        store.close();
        context.deleteDatabase("training.db");
    }

    @Test public void restoresEverySupportedFormatAndPreservesOptionalNulls() throws Exception {
        assertEquals("0", store.dataGeneration());
        for (int version : new int[]{1, 2, 3}) {
            String generationBefore = store.dataGeneration();
            store.restoreJson(BackupFixtures.legacy(version).toString());
            assertNotEquals(generationBefore, store.dataGeneration());
            JSONObject restored = new JSONObject(store.backupJson());
            assertFalse(restored.has("app_metadata"));
            assertEquals(1, restored.getJSONArray("sessions").length());
            assertEquals(1, restored.getJSONArray("training_sets").length());
            assertEquals(1, restored.getJSONArray("sessions").getJSONObject(0).getLong("phase_id"));
            assertEquals(78.4, restored.getJSONArray("body_weights").getJSONObject(0).getDouble("kg"), 0);
            assertTrue(restored.getJSONArray("training_sets").getJSONObject(0).isNull("rir"));
            assertEquals(version < 3 ? "" : "Technik gut; = bleibt Text",
                restored.getJSONArray("training_sets").getJSONObject(0).getString("note"));
        }
    }

    @Test public void malformedBackupLeavesAllExistingDataUntouched() throws Exception {
        String before = store.backupJson();
        String generationBefore = store.dataGeneration();
        JSONObject invalid = BackupFixtures.complete();
        invalid.getJSONArray("plans").getJSONObject(0).put("exercise_id", 999);
        try {
            store.restoreJson(invalid.toString());
            fail("Dangling reference accepted");
        } catch (IllegalArgumentException expected) { /* validation happens before DELETE */ }
        assertEquals(before, store.backupJson());
        assertEquals(generationBefore, store.dataGeneration());
    }

    @Test public void databaseFailureAfterDeletesRollsBackTheWholeRestore() throws Exception {
        String before = store.backupJson();
        String generationBefore = store.dataGeneration();
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_weight_import BEFORE INSERT ON body_weights "
            + "BEGIN SELECT RAISE(ABORT, 'test insertion failure'); END");
        try {
            store.restoreJson(BackupFixtures.complete().toString());
            fail("Expected trigger failure");
        } catch (android.database.sqlite.SQLiteException expected) { /* transaction must roll back */ }
        assertEquals(before, store.backupJson());
        assertEquals(generationBefore, store.dataGeneration());
    }

    @Test public void cancellationDoesNotDeleteExistingData() throws Exception {
        String before = store.backupJson();
        String generationBefore = store.dataGeneration();
        String input = BackupFixtures.complete().toString();
        Thread.currentThread().interrupt();
        try {
            store.restoreJson(input);
            fail("Expected cancellation");
        } catch (CancellationException expected) {
            // The worker's interruption must survive so its caller can stop too.
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals(before, store.backupJson());
        assertEquals(generationBefore, store.dataGeneration());
    }

    @Test public void generationUpdateAndDataReplacementAreOneAtomicCommit() throws Exception {
        store.restoreJson(BackupFixtures.complete().toString());
        String before = store.backupJson();
        String generationBefore = store.dataGeneration();
        JSONObject replacement = BackupFixtures.complete();
        replacement.getJSONArray("body_weights").getJSONObject(0).put("kg", 81.0);
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_generation AFTER INSERT ON app_metadata "
            + "BEGIN SELECT RAISE(ABORT, 'test generation failure'); END");
        try {
            store.restoreJson(replacement.toString());
            fail("Expected generation trigger failure");
        } catch (android.database.sqlite.SQLiteException expected) { /* restore must also roll back */ }
        assertEquals(before, store.backupJson());
        assertEquals(generationBefore, store.dataGeneration());
    }

    @Test public void cleanDatabaseEnforcesForeignKeysButLegacyOrphansDoNotBlockOpening() {
        SQLiteDatabase db = store.getWritableDatabase();
        try (Cursor c = db.rawQuery("PRAGMA foreign_keys", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(1, c.getInt(0));
        }
        db.setForeignKeyConstraintsEnabled(false);
        db.execSQL("INSERT INTO plans(plan,exercise_id,position,target_sets,phase_id) VALUES('A',999999,0,2,1)");
        store.close();
        store = new Store(context);
        assertFalse(store.exercises(true).isEmpty());
        assertEquals(1, store.bodyWeights().size());
        try (Cursor c = store.getReadableDatabase().rawQuery("PRAGMA foreign_keys", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
        }
    }

    @Test public void openingReadOnlyLegacyDatabaseDoesNotTryToCreateMetadata() {
        store.getWritableDatabase().execSQL("DROP TABLE app_metadata");
        store.close();
        try (SQLiteDatabase readOnly = SQLiteDatabase.openDatabase(
            context.getDatabasePath("training.db").getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
            assertTrue(readOnly.isReadOnly());
            store.onOpen(readOnly);
            try (Cursor c = readOnly.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='app_metadata'", null)) {
                assertFalse(c.moveToFirst());
            }
        }
        store = new Store(context);
    }
}
