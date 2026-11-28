package de.trainingsapp

/** Pure, plan-specific heuristic; no RIR, no cross-plan comparisons. */
object PerformanceIndex {
    enum class Kind { WEIGHTED, REPS, TIME }
    data class SetValue(val number: Int, val weight: Double? = null,
                        val reps: Int? = null, val seconds: Int? = null)
    data class ExerciseValue(val exerciseId: Long, val kind: Kind,
                             val plannedSets: Int, val completed: List<SetValue>)
    data class Result(val index: Double?, val completedSets: Int,
                      val comparableExercises: Int, val referenceExercises: Int)

    private fun score(e: ExerciseValue, plannedSets: Int): Double? {
        if (plannedSets <= 0 || e.completed.any { it.number <= 0 } ||
            e.completed.map { it.number }.distinct().size != e.completed.size) return null
        var total = 0.0
        for (s in e.completed) {
            val value = when (e.kind) {
                Kind.WEIGHTED -> {
                    val w = s.weight ?: return null
                    val r = s.reps ?: return null
                    if (!w.isFinite() || w <= 0 || r <= 0) return null
                    w * (1.0 + r / 30.0)
                }
                Kind.REPS -> (s.reps ?: return null).toDouble()
                Kind.TIME -> (s.seconds ?: return null).toDouble()
            }
            if (!value.isFinite() || value <= 0) return null
            total += value * if (s.number <= plannedSets) 1.0 else 1.0 / 3.0
        }
        return total.takeIf { it.isFinite() }
    }

    /**
     * Caller supplies snapshots from the same plan and phase. Missing exercises
     * are not treated as zero: a swap or incomplete snapshot cannot be inferred.
     * An explicitly present exercise with no completed sets contributes zero.
     * Changed exercise types or planned set counts are excluded.
     */
    fun compare(reference: List<ExerciseValue>, current: List<ExerciseValue>): Result {
        val count = current.sumOf { it.completed.size }
        require(reference.map { it.exerciseId }.distinct().size == reference.size)
        require(current.map { it.exerciseId }.distinct().size == current.size)
        val byId = current.associateBy { it.exerciseId }
        val ratios = reference.mapNotNull { base ->
            val now = byId[base.exerciseId] ?: return@mapNotNull null
            if (base.kind != now.kind || base.plannedSets != now.plannedSets) return@mapNotNull null
            val before = score(base, base.plannedSets) ?: return@mapNotNull null
            val after = score(now, base.plannedSets) ?: return@mapNotNull null
            if (before <= 0) return@mapNotNull null
            (after / before).takeIf { it.isFinite() }
        }
        return Result(if (ratios.isEmpty()) null else ratios.average() * 100.0,
            count, ratios.size, reference.size)
    }
}
