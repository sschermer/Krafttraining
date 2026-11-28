package de.trainingsapp

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FlexiblePlansTest {
    @Test fun renameAndRestorePreservePlanHistoryAndSetTargets() {
        val context=RuntimeEnvironment.getApplication()
        context.deleteDatabase("training.db")
        Store(context).use { store ->
            val phase=store.activePhase()!!
            val plan=store.createPlan(phase.id,"Kraft C")
            val ex=store.exercises().first { it.kind=="time" }
            store.savePlanEntries(plan.key,phase.id,listOf(ex.id to 2))
            val start=1_700_000_000_000L
            fun record(offset:Long,vararg seconds:Int):Long {
                val entry=ExerciseDraft(ex,seconds.map { SetDraft(seconds=it.toString(),completed=true) }.toMutableList())
                return store.saveSession(plan.key,false,"",0,listOf(entry),phase.id,start+offset)
            }
            val first=record(0,40,40)
            val second=record(1000,45,45,30)
            store.renamePlan(phase.id,plan.key,"Neuer Name")
            assertEquals("Neuer Name",store.planName(plan.key,phase.id))
            assertEquals(2,store.sessionPlan(first).single().target)
            assertEquals(125.0,store.performanceHistory(plan.key,phase.id)[second]!!.result.index!!,0.00001)
            assertEquals(3,store.performanceHistory(plan.key,phase.id)[second]!!.result.completedSets)
            val backup=store.backupJson()
            store.restoreJson(backup)
            assertEquals("Neuer Name",store.planName(plan.key,phase.id))
            assertEquals(2,store.sessions(plan.key,phase.id).size)
            assertEquals(125.0,store.performanceHistory(plan.key,phase.id)[second]!!.result.index!!,0.00001)
            val next=store.createPhase("Neue Phase",phase.id)
            assertEquals("Neuer Name",store.planName(plan.key,next.id))
            assertEquals(ex.id,store.planEntries(plan.key,next.id).single().first.id)
            assertTrue(store.sessions(plan.key,next.id).isEmpty())
        }
        context.deleteDatabase("training.db")
    }
}
