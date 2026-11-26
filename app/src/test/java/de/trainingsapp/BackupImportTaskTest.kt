package de.trainingsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class BackupImportTaskTest {
    private lateinit var app: Context
    private lateinit var backup: File
    private val tasks = mutableListOf<BackupImportTask>()
    private lateinit var before: String
    private val originalDraft = "{\"activePlan\":\"A\",\"unsaved\":true}"

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("training.db")
        app.getSharedPreferences("unfinished_workout", Context.MODE_PRIVATE).edit()
            .clear().putString("active", originalDraft).commit()
        Store(app).use {
            it.addBodyWeight(77.2, 1_700_000_000_000L)
            before = it.backupJson()
        }
        backup = File(app.cacheDir, "import-task-test.json")
        backup.writeText(BackupFixtures.complete().toString(), Charsets.UTF_8)
        GatedBackupProvider.file = backup
        GatedBackupProvider.entered = CountDownLatch(1)
        GatedBackupProvider.release = CountDownLatch(1)
    }

    @After fun cleanup() {
        GatedBackupProvider.release.countDown()
        for (task in tasks) {
            var completed = false
            task.attach { completed = true }
            task.cancel()
            await { completed }
            task.detach()
        }
        backup.delete()
        app.deleteDatabase("training.db")
        app.getSharedPreferences("unfinished_workout", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun successfulFileImportCommitsDataChangesGenerationAndClearsDraft() {
        val task = task(Uri.fromFile(backup))
        val results = mutableListOf<BackupImportTask.Result>()
        task.attach { results.add(it) }
        task.start()
        await { results.isNotEmpty() }
        assertNull(results.single().error)
        Store(app).use {
            assertEquals(78.4, it.bodyWeights().single().kg, 0.0)
            assertEquals(1, it.sessions().size)
            assertNotEquals("0", it.dataGeneration())
        }
        assertFalse(app.getSharedPreferences("unfinished_workout", Context.MODE_PRIVATE).contains("active"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, results.size)
    }

    @Test fun cancellationWhileDocumentProviderIsOpeningPreservesDatabaseAndDraft() {
        val task = task(gatedUri())
        val results = mutableListOf<BackupImportTask.Result>()
        task.attach { results.add(it) }
        task.start()
        assertTrue("Worker never opened the provider", GatedBackupProvider.entered.await(5, TimeUnit.SECONDS))
        task.cancel()
        await { results.isNotEmpty() }
        assertTrue(results.single().error?.contains("abgebrochen") == true)
        assertUnchanged()
    }

    @Test fun cancellationBeforeWorkerStartsAlsoPreservesDatabaseAndDraft() {
        val task = task(Uri.fromFile(backup))
        val results = mutableListOf<BackupImportTask.Result>()
        task.attach { results.add(it) }
        task.cancel()
        task.start()
        await { results.isNotEmpty() }
        assertNotNull(results.single().error)
        assertUnchanged()
    }

    @Test fun validationFailureDoesNotClearAnUnfinishedWorkout() {
        val invalid = BackupFixtures.complete()
        invalid.getJSONArray("training_sets").getJSONObject(0).put("exercise_id", 999_999)
        backup.writeText(invalid.toString(), Charsets.UTF_8)
        val task = task(Uri.fromFile(backup))
        val results = mutableListOf<BackupImportTask.Result>()
        task.attach { results.add(it) }
        task.start()
        await { results.isNotEmpty() }
        assertNotNull(results.single().error)
        assertUnchanged()
    }

    @Test fun detachAndAttachDeliverOnlyToCurrentListenerIncludingLateReattachment() {
        val task = task(gatedUri())
        var oldCalls = 0
        val currentResults = mutableListOf<BackupImportTask.Result>()
        task.attach { oldCalls++ }
        task.start()
        assertTrue(GatedBackupProvider.entered.await(5, TimeUnit.SECONDS))
        task.detach()
        task.attach { currentResults.add(it) }
        GatedBackupProvider.release.countDown()
        await { currentResults.isNotEmpty() }
        assertEquals(0, oldCalls)
        assertNull(currentResults.single().error)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, currentResults.size)

        task.detach()
        val afterRotation = mutableListOf<BackupImportTask.Result>()
        task.attach { afterRotation.add(it) }
        await { afterRotation.isNotEmpty() }
        assertNull(afterRotation.single().error)
        assertEquals(1, currentResults.size)
        assertEquals(0, oldCalls)
    }

    private fun task(uri: Uri) = BackupImportTask(app, uri).also { tasks.add(it) }

    private fun assertUnchanged() {
        Store(app).use {
            assertEquals(before, it.backupJson())
            assertEquals("0", it.dataGeneration())
        }
        assertEquals(originalDraft, app.getSharedPreferences("unfinished_workout", Context.MODE_PRIVATE)
            .getString("active", null))
    }

    private fun gatedUri(): Uri {
        val provider = GatedBackupProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = AUTHORITY; exported = false })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        return Uri.parse("content://$AUTHORITY/backup.json")
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!condition()) Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Import worker did not complete", condition())
    }

    /** A real file descriptor with a deterministic gate before the import reads it. */
    class GatedBackupProvider : ContentProvider() {
        companion object {
            lateinit var file: File
            lateinit var entered: CountDownLatch
            lateinit var release: CountDownLatch
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/json"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor = openGated(null)
        override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor =
            openGated(signal)
        // A read-only content:// open is dispatched through typed assets by the
        // Android framework. Its default implementation otherwise falls back to
        // the two-argument openAssetFile and discards the cancellation signal.
        override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor =
            openGated(null)
        override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?,
            signal: CancellationSignal?): AssetFileDescriptor = openGated(signal)

        private fun openGated(signal: CancellationSignal?): AssetFileDescriptor {
            signal?.setOnCancelListener { release.countDown() }
            entered.countDown()
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw FileNotFoundException("Test provider timed out")
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw FileNotFoundException("Test provider interrupted")
            }
            signal?.throwIfCanceled()
            return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
    }

    companion object { private const val AUTHORITY = "de.trainingsapp.test.backup" }
}
