package de.trainingsapp

import android.os.Bundle

/** One explicit export. Missing or invalid saved state never becomes a full export. */
internal data class ExportRequest(val format: String, val workoutId: Long? = null) {
    init {
        require(format in FORMATS) { "Unbekanntes Exportformat." }
        require(if (format == "workout_json") workoutId != null && workoutId > 0L else workoutId == null) {
            "Ungültiger Exportauftrag."
        }
    }

    fun save(out: Bundle) {
        out.putString(FORMAT_KEY, format)
        workoutId?.let { out.putLong(WORKOUT_KEY, it) }
    }

    companion object {
        private val FORMATS = setOf("xlsx", "csv", "json", "workout_json")
        private const val FORMAT_KEY = "pending_export_format"
        private const val WORKOUT_KEY = "pending_export_workout"

        fun restore(state: Bundle?): ExportRequest? {
            val format = state?.getString(FORMAT_KEY) ?: return null
            val id = if (state.containsKey(WORKOUT_KEY)) state.getLong(WORKOUT_KEY) else null
            return runCatching { ExportRequest(format, id) }.getOrNull()
        }
    }
}
