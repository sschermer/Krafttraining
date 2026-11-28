package de.trainingsapp

import org.junit.Assert.*
import org.junit.Test

class PerformanceIndexTest {
    private fun hold(id: Long, vararg times: Int) =
        PerformanceIndex.ExerciseValue(id, PerformanceIndex.Kind.TIME, 2,
            times.mapIndexed { i, t -> PerformanceIndex.SetValue(i + 1, seconds = t) })

    @Test fun referenceIs100() {
        val a = listOf(hold(1, 40, 40))
        assertEquals(100.0, PerformanceIndex.compare(a, a).index!!, 0.00001)
    }

    @Test fun extraSetCountsOneThird() {
        val r = PerformanceIndex.compare(listOf(hold(1, 40, 40)), listOf(hold(1, 45, 45, 30)))
        assertEquals(125.0, r.index!!, 0.00001)
        assertEquals(3, r.completedSets)
    }

    @Test fun missingRegularSetReducesIndex() {
        assertEquals(50.0, PerformanceIndex.compare(listOf(hold(1,40,40)),
            listOf(hold(1,40))).index!!, 0.00001)
    }

    @Test fun exercisesAreEquallyWeighted() {
        val r = PerformanceIndex.compare(listOf(hold(1,40,40),hold(2,10,10)),
            listOf(hold(1,40,40),hold(2,20,20)))
        assertEquals(150.0,r.index!!,0.00001)
    }

    @Test fun changedExerciseIsExcluded() {
        val r = PerformanceIndex.compare(listOf(hold(1,40,40),hold(2,20,20)),
            listOf(hold(1,40,40),hold(3,60,60)))
        assertEquals(100.0,r.index!!,0.00001)
        assertEquals(1,r.comparableExercises)
        assertEquals(2,r.referenceExercises)
        assertEquals(4,r.completedSets)
    }

    @Test fun noComparableExerciseHasNoIndex() {
        assertNull(PerformanceIndex.compare(listOf(hold(1,40,40)),listOf(hold(2,40,40))).index)
    }

    @Test fun weightedEpleyWithoutRir() {
        fun weighted(w: Double, r: Int) = PerformanceIndex.ExerciseValue(1,
            PerformanceIndex.Kind.WEIGHTED, 2, (1..2).map {
                PerformanceIndex.SetValue(it,weight=w,reps=r)
            })
        assertEquals(106.6666666667, PerformanceIndex.compare(listOf(weighted(7.0,15)),
            listOf(weighted(8.0,12))).index!!, 0.00001)
    }
}
