package de.trainingsapp

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class Exercise(
    val id: Long, val name: String, val category: String,
    val kind: String, val equipment: String, val perSide: Boolean,
    val archived: Boolean = false
)
data class TrainingSession(
    val id: Long, val timestamp: Long, val plan: String,
    val cardio: Boolean, val cardioType: String, val cardioMinutes: Int,
    val phaseId: Long = 1L
)
data class TrainingPhase(val id:Long, val name:String, val startedAt:Long, val endedAt:Long?) {
    val active:Boolean get() = endedAt==null
}
data class TrainingSet(
    val id: Long, val sessionId: Long, val exerciseId: Long, val number: Int,
    val weight: Double?, val reps: Int?, val seconds: Int?, val rir: Double?,
    val note:String = ""
)
data class ExerciseSwap(val id:Long,val sessionId:Long,val originalExerciseId:Long,
    val replacementExerciseId:Long,val reason:String,val position:Int)
data class BodyWeight(val id: Long, val timestamp: Long, val kg: Double)
data class SetDraft(
    var weight: String = "", var reps: String = "", var seconds: String = "",
    var rir: String = "", var completed: Boolean = false, var note:String = ""
)
data class ExerciseDraft(val exercise:Exercise,val sets:MutableList<SetDraft>,
    var replacedById:Long?=null,var replacementReason:String="",var replacementForId:Long?=null,
    var setNumberOffset:Int=0)

class Store(context: Context) : SQLiteOpenHelper(context, "training.db", null, 5) {
    companion object {
        val CATEGORIES = listOf(
            "Beine – kniedominant", "Hüfte & hintere Muskelkette",
            "Oberkörper – Drücken", "Oberkörper – Ziehen",
            "Rumpf", "Isolationsübungen", "Beweglichkeit & Stabilität",
            "Ausdauer & Kondition"
        )
        val TABLES = listOf("training_phases", "exercises", "plans", "sessions", "training_sets", "exercise_swaps", "body_weights", "plan_identities", "session_plan_exercises")
    }

    override fun onConfigure(db:SQLiteDatabase) {
        super.onConfigure(db)
        // Do not make an old, inconsistent database unusable during an update.
        // Clean databases (including new ones) can safely enforce their declared
        // constraints. Backup imports always validate every reference separately.
        val consistent=db.rawQuery("PRAGMA foreign_key_check",null).use { !it.moveToFirst() }
        if(consistent) db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db:SQLiteDatabase) {
        super.onOpen(db)
        // Local restore bookkeeping is deliberately excluded from exported data.
        // No training-table migration or existing record rewrite is required.
        if(!db.isReadOnly) db.execSQL("CREATE TABLE IF NOT EXISTS app_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    fun dataGeneration():String {
        val db=readableDatabase
        if(db.isReadOnly) {
            val exists=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='app_metadata'",null)
                .use {it.moveToFirst()}
            if(!exists)return "0"
        }
        return db.rawQuery("SELECT value FROM app_metadata WHERE key=?",arrayOf("generation"))
            .use {if(it.moveToFirst())it.getString(0) else "0"}
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE exercises (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
            category TEXT NOT NULL, kind TEXT NOT NULL, equipment TEXT NOT NULL DEFAULT '',
            per_side INTEGER NOT NULL DEFAULT 1, archived INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE plans (
            id INTEGER PRIMARY KEY AUTOINCREMENT, plan TEXT NOT NULL,
            exercise_id INTEGER NOT NULL, position INTEGER NOT NULL,
            target_sets INTEGER NOT NULL DEFAULT 2,
            FOREIGN KEY(exercise_id) REFERENCES exercises(id))""")
        db.execSQL("""CREATE TABLE sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL,
            plan TEXT NOT NULL, cardio INTEGER NOT NULL DEFAULT 0,
            cardio_type TEXT NOT NULL DEFAULT '', cardio_minutes INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE training_sets (
            id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL,
            exercise_id INTEGER NOT NULL, set_number INTEGER NOT NULL,
            weight REAL, reps INTEGER, seconds INTEGER, rir REAL,
            FOREIGN KEY(session_id) REFERENCES sessions(id),
            FOREIGN KEY(exercise_id) REFERENCES exercises(id))""")
        db.execSQL("""CREATE TABLE body_weights (
            id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, kg REAL NOT NULL)""")
        db.execSQL("CREATE INDEX idx_sessions_plan_time ON sessions(plan,timestamp)")
        db.execSQL("CREATE INDEX idx_sets_session ON training_sets(session_id,exercise_id)")
        val seeds = listOf(
            listOf("Kniebeugen mit Langhantel","Goblet Squats mit Kurzhantel","Ausfallschritte mit Kurzhanteln","Step-ups auf die Stufe","Bulgarian Split Squats","Wandsitz"),
            listOf("Kreuzheben mit Langhantel","Rumänisches Kreuzheben","Hip Thrusts mit Langhantel","Glute Bridges","Einbeiniges rumänisches Kreuzheben"),
            listOf("Bankdrücken mit Langhantel","Bankdrücken mit Kurzhanteln","Schulterdrücken mit Kurzhanteln","Liegestütze","Brustdrücken am Kabelzug"),
            listOf("Klimmzüge – breiter Griff","Klimmzüge – enger Griff","Langhantelrudern","Einarmiges Kurzhantelrudern","Rudern am unteren Kabelzug","Latzug am oberen Kabelzug"),
            listOf("Crunches","Russian Twists","Unterarmstütz (Plank)","Seitstütz (Side Plank)","Beinheben auf der Matte","Dead Bugs"),
            listOf("Bizepscurls mit Kurzhanteln","Bizepscurls am unteren Kabelzug","Trizepsdrücken am oberen Kabelzug","Seitheben mit Kurzhanteln","Reverse Flys","Wadenheben mit Gewichten"),
            listOf("Hüftmobilisation","Schulterbeweglichkeit","Einbeinstand","Bird Dogs","Mobilisation der Brustwirbelsäule"),
            listOf("Treppensteigen bzw. Step-ups","Mountain Climbers","Hampelmänner","Gehen oder Laufen auf der Stelle")
        )
        for ((categoryIndex, names) in seeds.withIndex()) for (name in names) {
            val time = name in setOf("Wandsitz","Unterarmstütz (Plank)","Seitstütz (Side Plank)","Einbeinstand")
            val equipment = when {
                name.contains("Kabelzug") || name.contains("Latzug") -> "Kabelturm"
                name.contains("Klimmzüge") -> "Klimmzugstange"
                name.contains("Langhantel") || name.contains("Kreuzheben") -> "Langhantel"
                name.contains("Kurzhantel") || name.contains("Curls") -> "Kurzhanteln"
                name.contains("Bankdrücken") -> "Bank, Kurzhanteln"
                name.contains("Stufe") || name.contains("Step-ups") -> "Stufe"
                name.contains("Plank") || name.contains("Matte") -> "Matte"
                else -> "Ohne Geräte"
            }
            val v = ContentValues().apply {
                put("name", name); put("category", CATEGORIES[categoryIndex])
                put("kind", if (time) "time" else "reps")
                put("equipment", equipment)
                put("per_side", if (equipment.contains("Langhantel") || equipment.contains("Kurzhanteln")) 1 else 0)
            }
            db.insertOrThrow("exercises", null, v)
        }
        // Fresh installations start with EMPTY A/B training plans.
        // Catalog exercises are still available to choose from.
        // Existing installations retain their saved plans through upgrades.
        installPhases(db)
        installSwapNotes(db)
        installPlanIdentities(db)
        installSessionSnapshots(db)
    }

    private fun installPhases(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS training_phases (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
            started_at INTEGER NOT NULL, ended_at INTEGER)""")
        db.execSQL("INSERT OR IGNORE INTO training_phases (id,name,started_at,ended_at) VALUES (1,'Trainingsphase 1',?,NULL)",
            arrayOf(System.currentTimeMillis()))
        db.execSQL("ALTER TABLE plans ADD COLUMN phase_id INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE sessions ADD COLUMN phase_id INTEGER NOT NULL DEFAULT 1")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_phase_sessions ON sessions(phase_id,timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_phase_plans ON plans(phase_id,plan,position)")
    }
    private fun installSwapNotes(db:SQLiteDatabase) {
        db.execSQL("ALTER TABLE training_sets ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        db.execSQL("""CREATE TABLE exercise_swaps (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER NOT NULL, original_exercise_id INTEGER NOT NULL,
            replacement_exercise_id INTEGER NOT NULL,
            reason TEXT NOT NULL DEFAULT '', position INTEGER NOT NULL,
            FOREIGN KEY(session_id) REFERENCES sessions(id),
            FOREIGN KEY(original_exercise_id) REFERENCES exercises(id),
            FOREIGN KEY(replacement_exercise_id) REFERENCES exercises(id))""")
        db.execSQL("CREATE INDEX idx_exercise_swaps_session ON exercise_swaps(session_id,position)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion < 2) installPhases(db)
        if(oldVersion < 3) installSwapNotes(db)
        if(oldVersion < 4) installPlanIdentities(db)
        if(oldVersion < 5) installSessionSnapshots(db)
    }

    private fun <T> query(sql: String, args: Array<String> = emptyArray(), map: (Cursor) -> T): List<T> {
        val out = mutableListOf<T>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(map(c)) }
        return out
    }
    private fun Cursor.optDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)
    private fun Cursor.optInt(index: Int): Int? = if (isNull(index)) null else getInt(index)
    private fun Cursor.asExercise() = Exercise(getLong(0),getString(1),getString(2),getString(3),getString(4),getInt(5)!=0,getInt(6)!=0)
    private val exColumns = "id,name,category,kind,equipment,per_side,archived"

    fun exercises(includeArchived: Boolean = false): List<Exercise> =
        query("SELECT $exColumns FROM exercises " + (if (includeArchived) "" else "WHERE archived=0 ") + "ORDER BY category,name") { it.asExercise() }
    fun exercise(id: Long): Exercise? =
        query("SELECT $exColumns FROM exercises WHERE id=?", arrayOf(id.toString())) { it.asExercise() }.firstOrNull()

    fun upsertExercise(item: Exercise): Long {
        val v = ContentValues().apply {
            put("name",item.name.trim());put("category",item.category);put("kind",item.kind)
            put("equipment",item.equipment);put("per_side",if(item.perSide)1 else 0)
            put("archived",if(item.archived)1 else 0)
        }
        return if(item.id<=0) writableDatabase.insertOrThrow("exercises",null,v)
            else { writableDatabase.update("exercises",v,"id=?",arrayOf(item.id.toString()));item.id }
    }

    fun phases():List<TrainingPhase> =
        query("SELECT id,name,started_at,ended_at FROM training_phases ORDER BY id DESC") {
            TrainingPhase(it.getLong(0),it.getString(1),it.getLong(2),
                if(it.isNull(3)) null else it.getLong(3))
        }
    fun activePhase():TrainingPhase? = phases().firstOrNull { it.active }
    fun phase(id:Long):TrainingPhase? = phases().firstOrNull { it.id==id }

    fun finishPhase(id:Long) {
        val active=activePhase() ?: return
        require(active.id==id) { "Nur die aktuelle Phase kann abgeschlossen werden." }
        writableDatabase.execSQL("UPDATE training_phases SET ended_at=? WHERE id=? AND ended_at IS NULL",
            arrayOf(System.currentTimeMillis(),id))
    }

    // A new plan is an independent snapshot. Past training data is immutable until
    // the user explicitly edits an individual saved workout.
    fun createPhase(name:String,copyFromId:Long?=null):TrainingPhase {
        require(name.trim().isNotEmpty()) { "Bitte einen Namen eingeben." }
        val db=writableDatabase
        db.beginTransaction()
        try {
            val active=activePhase()
            val source=copyFromId?.let { id -> phase(id) ?: error("Vorlage nicht gefunden.") }
            active?.let {
                db.execSQL("UPDATE training_phases SET ended_at=? WHERE id=? AND ended_at IS NULL",
                    arrayOf(System.currentTimeMillis(),it.id))
            }
            val now=System.currentTimeMillis()
            val id=db.insertOrThrow("training_phases",null,ContentValues().apply {
                put("name",name.trim());put("started_at",now)
            })
            if(source!=null) db.execSQL("""INSERT INTO plans(plan,exercise_id,position,target_sets,phase_id)
                SELECT plan,exercise_id,position,target_sets,? FROM plans WHERE phase_id=?""",
                arrayOf(id,source.id))
            installPlanIdentities(db)
            if(source!=null) {
                db.delete("plan_identities","phase_id=?",arrayOf(id.toString()))
                db.execSQL("""INSERT INTO plan_identities(phase_id,plan_key,name,position)
                    SELECT ?,plan_key,name,position FROM plan_identities WHERE phase_id=?""",
                    arrayOf(id,source.id))
            }
            db.setTransactionSuccessful()
            return TrainingPhase(id,name.trim(),now,null)
        } finally {db.endTransaction()}
    }

    /** Stable storage keys remain unchanged when a plan is renamed.
     * Legacy A/B keys are preserved; new identities are UUIDs.
     */
    data class PlanIdentity(val key:String, val name:String)

    fun planIdentities(phaseId:Long):List<PlanIdentity> =
        query("""SELECT plan_key, name FROM plan_identities
            WHERE phase_id=? ORDER BY position, id""",arrayOf(phaseId.toString())) {
            PlanIdentity(it.getString(0),it.getString(1))
        }

    private fun installPlanIdentities(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS plan_identities (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            phase_id INTEGER NOT NULL,
            plan_key TEXT NOT NULL,
            name TEXT NOT NULL,
            position INTEGER NOT NULL,
            UNIQUE(phase_id,plan_key),
            FOREIGN KEY(phase_id) REFERENCES training_phases(id))""")
        db.execSQL("""INSERT OR IGNORE INTO plan_identities(phase_id,plan_key,name,position)
            SELECT id,'A','Training A',0 FROM training_phases""")
        db.execSQL("""INSERT OR IGNORE INTO plan_identities(phase_id,plan_key,name,position)
            SELECT id,'B','Training B',1 FROM training_phases""")
    }

    fun planName(plan:String,phaseId:Long):String =
        planIdentities(phaseId).firstOrNull { it.key==plan }?.name ?: plan

    fun createPlan(phaseId:Long,name:String,copyKey:String?=null):PlanIdentity {
        val cleaned=name.trim()
        require(cleaned.isNotEmpty() && cleaned.length<=128 && cleaned.indexOf('\u0000')<0) { "Bitte gültigen Plannamen eingeben." }
        val db=writableDatabase
        db.beginTransaction()
        try {
            require(activePhase()?.id==phaseId) { "Nur die aktive Phase ist bearbeitbar." }
            val existing=planIdentities(phaseId)
            require(existing.none { it.name.equals(cleaned,true) }) { "Der Name ist schon vergeben." }
            require(copyKey==null || existing.any {it.key==copyKey}) { "Vorlage nicht gefunden." }
            val key=java.util.UUID.randomUUID().toString()
            db.insertOrThrow("plan_identities",null,ContentValues().apply {
                put("phase_id",phaseId);put("plan_key",key);put("name",cleaned);put("position",existing.size)
            })
            if(copyKey!=null) db.execSQL("""INSERT INTO plans(plan,exercise_id,position,target_sets,phase_id)
                SELECT ?,exercise_id,position,target_sets,phase_id FROM plans WHERE phase_id=? AND plan=?""",
                arrayOf(key,phaseId,copyKey))
            db.setTransactionSuccessful()
            return PlanIdentity(key,cleaned)
        } finally { db.endTransaction() }
    }

    fun renamePlan(phaseId:Long,key:String,name:String) {
        val cleaned=name.trim()
        require(cleaned.isNotEmpty() && cleaned.length<=128 && cleaned.indexOf('\u0000')<0) { "Bitte gültigen Plannamen eingeben." }
        val db=writableDatabase
        db.beginTransaction()
        try {
            require(activePhase()?.id==phaseId) { "Nur die aktive Phase ist bearbeitbar." }
            require(planIdentities(phaseId).none { it.key!=key && it.name.equals(cleaned,true) }) { "Der Name ist schon vergeben." }
            check(db.update("plan_identities",ContentValues().apply {put("name",cleaned)},
                "phase_id=? AND plan_key=?",arrayOf(phaseId.toString(),key))==1) { "Plan nicht gefunden." }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun installSessionSnapshots(db:SQLiteDatabase) {
        // Old workouts lack reliable historical target sets. Leave snapshots
        // empty for those workouts instead of fabricating historical plan data.
        db.execSQL("""CREATE TABLE IF NOT EXISTS session_plan_exercises (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER NOT NULL,
            exercise_id INTEGER NOT NULL,
            target_sets INTEGER NOT NULL,
            kind TEXT NOT NULL,
            per_side INTEGER NOT NULL,
            UNIQUE(session_id,exercise_id),
            FOREIGN KEY(session_id) REFERENCES sessions(id),
            FOREIGN KEY(exercise_id) REFERENCES exercises(id))""")
    }

    data class PlannedExercise(val id:Long,val target:Int,val kind:String,val perSide:Boolean)
    fun sessionPlan(sessionId:Long):List<PlannedExercise> =
        query("""SELECT exercise_id,target_sets,kind,per_side FROM session_plan_exercises
            WHERE session_id=? ORDER BY id""",arrayOf(sessionId.toString())) {
            PlannedExercise(it.getLong(0),it.getInt(1),it.getString(2),it.getInt(3)!=0)
        }

    data class SessionPerformance(val result:PerformanceIndex.Result,val estimated:Boolean,val referenceId:Long?)

    fun performanceHistory(plan:String,phaseId:Long):Map<Long,SessionPerformance> {
        val workouts=sessions(plan,phaseId)
        if(workouts.isEmpty()) return emptyMap()
        data class Snapshot(val session:TrainingSession,val rows:List<TrainingSet>,
                            val planned:List<PlannedExercise>,val estimated:Boolean,val swapped:Set<Long>)
        val snapshots=workouts.map { workout ->
            val rows=sets(workout.id)
            val actual=sessionPlan(workout.id)
            val planned=actual.ifEmpty {
                rows.map {it.exerciseId}.distinct().mapNotNull { id ->
                    exercise(id)?.let { PlannedExercise(id,2,it.kind,it.perSide) }
                }
            }
            val swaps=exerciseSwaps(workout.id).flatMap { listOf(it.originalExerciseId,it.replacementExerciseId) }.toSet()
            Snapshot(workout,rows,planned,actual.isEmpty(),swaps)
        }
        val baseline=snapshots.first()
        fun mode(p:PlannedExercise, rows:List<TrainingSet>):PerformanceIndex.Kind? {
            if(p.kind=="time") return PerformanceIndex.Kind.TIME
            if(rows.isEmpty())return null
            if(rows.all {(it.weight ?: 0.0)>0.0}) return PerformanceIndex.Kind.WEIGHTED
            if(rows.all {(it.weight ?: 0.0)==0.0}) return PerformanceIndex.Kind.REPS
            return null
        }
        fun value(p:PlannedExercise,rows:List<TrainingSet>,kind:PerformanceIndex.Kind) =
            PerformanceIndex.ExerciseValue(p.id,kind,p.target,rows.map {
                PerformanceIndex.SetValue(it.number,it.weight,it.reps,it.seconds)
            })
        return snapshots.associate { now ->
            val baseValues=mutableListOf<PerformanceIndex.ExerciseValue>()
            val currentValues=mutableListOf<PerformanceIndex.ExerciseValue>()
            for(base in baseline.planned) {
                if(base.id in baseline.swapped || base.id in now.swapped)continue
                val current=now.planned.firstOrNull {it.id==base.id} ?: continue
                if(current.kind!=base.kind || current.perSide!=base.perSide || current.target!=base.target)continue
                val beforeRows=baseline.rows.filter {it.exerciseId==base.id}
                val afterRows=now.rows.filter {it.exerciseId==base.id}
                val beforeKind=mode(base,beforeRows) ?: continue
                val afterKind=if(afterRows.isEmpty())beforeKind else mode(current,afterRows) ?: continue
                if(beforeKind!=afterKind)continue
                baseValues.add(value(base,beforeRows,beforeKind))
                currentValues.add(value(current,afterRows,afterKind))
            }
            val r=PerformanceIndex.compare(baseValues,currentValues).copy(
                completedSets=now.rows.size,referenceExercises=baseline.planned.size)
            now.session.id to SessionPerformance(r,baseline.estimated || now.estimated,baseline.session.id)
        }
    }

    fun planEntries(plan:String,phaseId:Long=activePhase()?.id ?: 1L):List<Pair<Exercise,Int>> =
        query("""SELECT e.id,e.name,e.category,e.kind,e.equipment,e.per_side,e.archived,p.target_sets
            FROM plans p JOIN exercises e ON e.id=p.exercise_id
            WHERE p.plan=? AND p.phase_id=? ORDER BY p.position""",
            arrayOf(plan,phaseId.toString())) { c -> Pair(c.asExercise(),c.getInt(7)) }

    fun addToPlan(plan:String,exerciseId:Long,phaseId:Long=activePhase()?.id ?: 1L) {
        require(activePhase()?.id==phaseId) { "Abgeschlossene Trainingsphasen sind schreibgeschützt." }
        if(planEntries(plan,phaseId).any { it.first.id==exerciseId })return
        writableDatabase.insertOrThrow("plans",null,ContentValues().apply {
            put("plan",plan);put("exercise_id",exerciseId)
            put("position",planEntries(plan,phaseId).size);put("target_sets",2);put("phase_id",phaseId)
        })
    }
    /** Replace a plan in one transaction, only after the editor's explicit Save.
     * The current legacy storage still accepts A/B only; the v4 migration will
     * replace this restriction with stable plan identities.
     */
    fun savePlanEntries(plan:String, phaseId:Long, entries:List<Pair<Long,Int>>) {
        require(entries.map { it.first }.distinct().size == entries.size) { "Doppelte Übung." }
        require(entries.all { it.second in 1..BackupValidation.MAX_TARGET_SETS }) { "Ungültige Satzanzahl." }
        require(entries.sumOf { it.second.coerceAtLeast(2) } <= 1000) { "Zu viele Sätze." }
        val db=writableDatabase
        db.beginTransaction()
        try {
            require(activePhase()?.id==phaseId) { "Abgeschlossene Trainingsphasen sind schreibgeschützt." }
            require(planIdentities(phaseId).any { it.key==plan }) { "Unbekannter Trainingsplan." }
            val existing=planEntries(plan,phaseId).map { it.first.id }.toSet()
            for ((id,_) in entries) {
                val ex=exercise(id) ?: error("Übung nicht gefunden.")
                require(!ex.archived || id in existing) { "Archivierte Übung." }
            }
            db.delete("plans","plan=? AND phase_id=?",arrayOf(plan,phaseId.toString()))
            entries.forEachIndexed { position, (id, count) ->
                db.insertOrThrow("plans",null,ContentValues().apply {
                    put("plan",plan);put("phase_id",phaseId);put("exercise_id",id)
                    put("position",position);put("target_sets",count)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Most recent recorded values of this exercise in the selected plan and phase.
     * Strict timestamp bound prevents future workouts leaking into backdated drafts.
     */
    fun previousPlanSets(exerciseId:Long, plan:String, phaseId:Long, before:Long):List<TrainingSet> {
        val id=query("""SELECT t.session_id FROM training_sets t
            JOIN sessions s ON s.id=t.session_id
            WHERE t.exercise_id=? AND s.plan=? AND s.phase_id=? AND s.timestamp<?
            ORDER BY s.timestamp DESC,s.id DESC LIMIT 1""",
            arrayOf(exerciseId.toString(),plan,phaseId.toString(),before.toString())) { it.getLong(0) }
            .firstOrNull() ?: return emptyList()
        return sets(id).filter { it.exerciseId==exerciseId }.sortedBy { it.number }
    }

    fun removeFromPlan(plan:String,exerciseId:Long,phaseId:Long=activePhase()?.id ?: 1L) {
        require(activePhase()?.id==phaseId) { "Abgeschlossene Trainingsphasen sind schreibgeschützt." }
        writableDatabase.delete("plans","plan=? AND exercise_id=? AND phase_id=?",
            arrayOf(plan,exerciseId.toString(),phaseId.toString()))
    }
    // Optional reset for existing installations: do not touch recorded
    // sessions, individual sets or archived phase plans.
    fun clearCurrentPlans(phaseId:Long) {
        require(activePhase()?.id==phaseId) { "Nur aktive Trainingspläne können geleert werden." }
        writableDatabase.delete("plans","phase_id=?",arrayOf(phaseId.toString()))
    }

    fun sessions(plan:String?=null,phaseId:Long?=null):List<TrainingSession> {
        val conditions=mutableListOf<String>()
        val args=mutableListOf<String>()
        if(plan!=null) {conditions.add("plan=?");args.add(plan)}
        if(phaseId!=null) {conditions.add("phase_id=?");args.add(phaseId.toString())}
        val where=if(conditions.isEmpty()) "" else " WHERE "+conditions.joinToString(" AND ")
        return query("""SELECT id,timestamp,plan,cardio,cardio_type,cardio_minutes,phase_id
            FROM sessions$where ORDER BY timestamp ASC,id ASC""",args.toTypedArray()) { c ->
            TrainingSession(c.getLong(0),c.getLong(1),c.getString(2),
                c.getInt(3)!=0,c.getString(4),c.getInt(5),c.getLong(6))
        }
    }
    fun session(id:Long):TrainingSession? =
        query("""SELECT id,timestamp,plan,cardio,cardio_type,cardio_minutes,phase_id
            FROM sessions WHERE id=?""",arrayOf(id.toString())) { c ->
            TrainingSession(c.getLong(0),c.getLong(1),c.getString(2),
                c.getInt(3)!=0,c.getString(4),c.getInt(5),c.getLong(6))
        }.firstOrNull()

    fun sets(sessionId:Long):List<TrainingSet> = query(
        "SELECT id,session_id,exercise_id,set_number,weight,reps,seconds,rir,note FROM training_sets WHERE session_id=? ORDER BY set_number",
        arrayOf(sessionId.toString())) { c ->
        TrainingSet(c.getLong(0),c.getLong(1),c.getLong(2),c.getInt(3),
            c.optDouble(4),c.optInt(5),c.optInt(6),c.optDouble(7),c.getString(8) ?: "")
    }
    fun exerciseSwaps(sessionId:Long):List<ExerciseSwap> =
        query("""SELECT id,session_id,original_exercise_id,replacement_exercise_id,reason,position
            FROM exercise_swaps WHERE session_id=? ORDER BY position,id""",
            arrayOf(sessionId.toString())) { c ->
            ExerciseSwap(c.getLong(0),c.getLong(1),c.getLong(2),c.getLong(3),
                c.getString(4),c.getInt(5))
        }
    // +1 = unambiguous improvement, -1 = decline, 0 = unchanged/ambiguous.
    // Compare with the most recent earlier workout of the same plan containing
    // this exercise, ordered by the user's actual training date.
    // RIR is intentionally not used; mixed load/repetition changes stay neutral.
    fun progressDirection(session:TrainingSession,exercise:Exercise):Int {
        val previousId=query("""SELECT t.session_id FROM training_sets t
            JOIN sessions s ON s.id=t.session_id
            WHERE t.exercise_id=? AND s.plan=? AND
              (s.timestamp<? OR (s.timestamp=? AND s.id<?))
            GROUP BY t.session_id
            ORDER BY s.timestamp DESC,s.id DESC LIMIT 1""",
            arrayOf(exercise.id.toString(),session.plan,session.timestamp.toString(),
                session.timestamp.toString(),session.id.toString())) { it.getLong(0) }
            .firstOrNull() ?: return 0
        val before=sets(previousId).filter {it.exerciseId==exercise.id}.sortedBy {it.number}
        val now=sets(session.id).filter {it.exerciseId==exercise.id}.sortedBy {it.number}
        if(now.isEmpty() || before.size!=now.size)return 0
        var improved=false
        var reduced=false
        for(i in now.indices) {
            val current=now[i]
            val old=before[i]
            if(exercise.kind=="time") {
                val a=current.seconds ?: return 0
                val b=old.seconds ?: return 0
                if(a>b)improved=true else if(a<b)reduced=true
            } else {
                val a=current.reps ?: return 0
                val b=old.reps ?: return 0
                if(a>b)improved=true else if(a<b)reduced=true
                if(current.weight!=null || old.weight!=null) {
                    val c=current.weight ?: return 0
                    val d=old.weight ?: return 0
                    if(c>d+0.00001)improved=true else if(c<d-0.00001)reduced=true
                }
            }
        }
        return when {
            improved && !reduced -> 1
            reduced && !improved -> -1
            else -> 0
        }
    }

    fun mostRecentNote(exerciseId:Long):String? =
        query("""SELECT t.note FROM training_sets t
            JOIN sessions s ON s.id=t.session_id
            WHERE t.exercise_id=? AND LENGTH(TRIM(t.note))>0
            ORDER BY s.timestamp DESC,s.id DESC,t.set_number DESC LIMIT 1""",
            arrayOf(exerciseId.toString())) {it.getString(0)}
            .firstOrNull()

    fun recentSets(exerciseId: Long): List<TrainingSet> {
        val ids = query("""SELECT t.session_id FROM training_sets t
            JOIN sessions s ON s.id=t.session_id
            WHERE t.exercise_id=? ORDER BY s.timestamp DESC,s.id DESC LIMIT 1""",
            arrayOf(exerciseId.toString())) { it.getLong(0) }
        return if(ids.isEmpty()) emptyList() else sets(ids[0]).filter { it.exerciseId==exerciseId }
    }
    fun newDraft(plan:String,phaseId:Long=activePhase()?.id ?: 1L,before:Long=System.currentTimeMillis()):MutableList<ExerciseDraft> = planEntries(plan,phaseId).map { (exercise, count) ->
        val previous=previousPlanSets(exercise.id,plan,phaseId,before)
        val rows=(0 until count.coerceIn(1,BackupValidation.MAX_TARGET_SETS)).map { i ->
            val last=previous.getOrNull(i)
            SetDraft(
                last?.weight?.let { formatNumber(it) } ?: "",
                last?.reps?.toString() ?: "",
                last?.seconds?.toString() ?: "",
                "" // RIR is never suggested as if measured today.
            )
        }.toMutableList()
        ExerciseDraft(exercise,rows)
    }.toMutableList()

    // Only explicitly confirmed sets can enter the training history.
    // The suggestions shown in the UI are never treated as completed sets.
    fun saveSession(plan:String, cardio:Boolean, cardioType:String, cardioMinutes:Int, entries:List<ExerciseDraft>, phaseId:Long=activePhase()?.id ?: 1L, timestamp:Long=System.currentTimeMillis()):Long {
        val confirmed=entries.flatMap { entry ->
            entry.sets.mapIndexedNotNull { index, row ->
                if(row.completed) Triple(entry.exercise,index+entry.setNumberOffset,row) else null
            }
        }
        require(confirmed.isNotEmpty()) { "Bitte mindestens einen Satz als erledigt markieren." }
        for((exercise,_,row) in confirmed) {
            val reps=row.reps.trim().toIntOrNull()
            val seconds=row.seconds.trim().toIntOrNull()
            require(if(exercise.kind=="time") seconds!=null && seconds>0 else reps!=null && reps>0) {
                "Bitte einen gültigen Wert für ${exercise.name} eintragen."
            }
            val weight=row.weight.trim().replace(',','.').toDoubleOrNull()
            val rir=row.rir.trim().replace(',','.').toDoubleOrNull()
            require(row.weight.isBlank() || (weight!=null && weight>=0.0)) {
                "Ungültiges Gewicht bei ${exercise.name}."
            }
            require(row.rir.isBlank() || (rir!=null && rir>=0.0)) {
                "Ungültiger RIR-Wert bei ${exercise.name}."
            }
        }

        val db=writableDatabase
        db.beginTransaction()
        try {
            val sessionId=db.insertOrThrow("sessions",null,ContentValues().apply {
                put("timestamp",timestamp);put("plan",plan);put("phase_id",phaseId)
                put("cardio",if(cardio)1 else 0);put("cardio_type",cardioType)
                put("cardio_minutes",cardioMinutes)
            })
            for((exercise,target) in planEntries(plan,phaseId)) {
                db.insertOrThrow("session_plan_exercises",null,ContentValues().apply {
                    put("session_id",sessionId);put("exercise_id",exercise.id)
                    put("target_sets",target);put("kind",exercise.kind);put("per_side",if(exercise.perSide)1 else 0)
                })
            }
            for((exercise,index,row) in confirmed) {
                val reps=row.reps.trim().toIntOrNull()
                val seconds=row.seconds.trim().toIntOrNull()
                db.insertOrThrow("training_sets",null,ContentValues().apply {
                    put("session_id",sessionId);put("exercise_id",exercise.id);put("set_number",index+1)
                    row.weight.trim().replace(',','.').toDoubleOrNull()?.let { put("weight",it) }
                    reps?.let { put("reps",it) }
                    seconds?.let { put("seconds",it) }
                    row.rir.trim().replace(',','.').toDoubleOrNull()?.let { put("rir",it) }
                    put("note",row.note)
                })
            }
            entries.forEachIndexed {position,entry ->
                entry.replacedById?.let {replacementId ->
                    db.insertOrThrow("exercise_swaps",null,ContentValues().apply {
                        put("session_id",sessionId)
                        put("original_exercise_id",entry.exercise.id)
                        put("replacement_exercise_id",replacementId)
                        put("reason",entry.replacementReason)
                        put("position",position)
                    })
                }
            }
            db.setTransactionSuccessful()
            return sessionId
        } finally { db.endTransaction() }
    }

    fun bodyWeights():List<BodyWeight> = query("SELECT id,timestamp,kg FROM body_weights ORDER BY timestamp ASC,id ASC") {
        BodyWeight(it.getLong(0),it.getLong(1),it.getDouble(2))
    }
    fun addBodyWeight(kg:Double,timestamp:Long=System.currentTimeMillis()):Long =
        writableDatabase.insertOrThrow("body_weights",null,ContentValues().apply {
            put("timestamp",timestamp);put("kg",kg)
        })
    fun updateBodyWeight(id:Long,kg:Double,timestamp:Long) {
        require(kg>0.0 && kg<=500.0 && timestamp>0L) {"Bitte gültige Messwerte eingeben."}
        val updated=writableDatabase.update("body_weights",ContentValues().apply {
            put("kg",kg);put("timestamp",timestamp)
        },"id=?",arrayOf(id.toString()))
        require(updated==1) {"Diese Gewichtsmessung wurde nicht gefunden."}
    }
    fun deleteBodyWeight(id:Long) {
        require(id>0L) {"Ungültige Gewichtsmessung."}
        val deleted=writableDatabase.delete("body_weights","id=?",arrayOf(id.toString()))
        require(deleted==1) {"Die Gewichtsmessung wurde nicht gefunden."}
    }
    fun progressExercises(plan:String,phaseId:Long?=null):List<Exercise> {
        val ids=if(phaseId!=null) {
            query("""SELECT DISTINCT exercise_id FROM (
                SELECT exercise_id FROM plans WHERE plan=? AND phase_id=?
                UNION ALL
                SELECT t.exercise_id FROM training_sets t
                JOIN sessions s ON s.id=t.session_id
                WHERE s.plan=? AND s.phase_id=?
            )""",arrayOf(plan,phaseId.toString(),plan,phaseId.toString())) {it.getLong(0)}
        } else {
            query("""SELECT DISTINCT exercise_id FROM (
                SELECT exercise_id FROM plans WHERE plan=?
                UNION ALL
                SELECT t.exercise_id FROM training_sets t JOIN sessions s ON s.id=t.session_id
                WHERE s.plan=?
            )""",arrayOf(plan,plan)) {it.getLong(0)}
        }
        return ids.mapNotNull {exercise(it)}.sortedBy {it.name}
    }

    fun changeSessionDate(id:Long,timestamp:Long) {
        require(timestamp>0L) {"Ungültiges Datum."}
        require(session(id)!=null) {"Trainingseinheit nicht gefunden."}
        writableDatabase.update("sessions",ContentValues().apply {put("timestamp",timestamp)},
            "id=?",arrayOf(id.toString()))
    }
    fun updateSession(id:Long,timestamp:Long,cardio:Boolean,cardioType:String,
        cardioMinutes:Int,entries:List<ExerciseDraft>) {
        require(session(id)!=null) {"Trainingseinheit nicht gefunden."}
        require(timestamp>0L) {"Ungültiges Datum."}
        val selected=entries.flatMap {entry ->
            entry.sets.mapIndexedNotNull {index,set ->
                if(set.completed) Triple(entry.exercise,index+entry.setNumberOffset,set) else null
            }
        }
        require(selected.isNotEmpty()) {"Mindestens einen absolvierten Satz auswählen."}
        for((ex,_,set) in selected) {
            val value=if(ex.kind=="time") set.seconds.trim().toIntOrNull()
                else set.reps.trim().toIntOrNull()
            require(value!=null && value>0) {"Ungültiger Satz bei \${ex.name}."}
            val weight=set.weight.trim().replace(',','.').toDoubleOrNull()
            val rir=set.rir.trim().replace(',','.').toDoubleOrNull()
            require(set.weight.isBlank() || (weight!=null && weight>=0)) {"Ungültiges Gewicht."}
            require(set.rir.isBlank() || (rir!=null && rir>=0)) {"Ungültiger RIR-Wert."}
        }
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.update("sessions",ContentValues().apply {
                put("timestamp",timestamp);put("cardio",if(cardio)1 else 0)
                put("cardio_type",cardioType);put("cardio_minutes",cardioMinutes)
            },"id=?",arrayOf(id.toString()))
            db.delete("training_sets","session_id=?",arrayOf(id.toString()))
            for((ex,index,set) in selected) {
                db.insertOrThrow("training_sets",null,ContentValues().apply {
                    put("session_id",id);put("exercise_id",ex.id);put("set_number",index+1)
                    set.weight.trim().replace(',','.').toDoubleOrNull()?.let {put("weight",it)}
                    set.reps.trim().toIntOrNull()?.let {put("reps",it)}
                    set.seconds.trim().toIntOrNull()?.let {put("seconds",it)}
                    set.rir.trim().replace(',','.').toDoubleOrNull()?.let {put("rir",it)}
                    put("note",set.note)
                })
            }
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }

    fun formatCell(sessionId:Long,exercise:Exercise):String {
        val all=sets(sessionId).filter {it.exerciseId==exercise.id}.sortedBy {it.number}
        if(all.isEmpty()) return "–"
        if(exercise.kind=="time") return all.joinToString("-") { (it.seconds ?: 0).toString()+"s" }
        val weights=all.map {it.weight}.distinct()
        val reps=all.joinToString("-") { (it.reps ?: 0).toString() }
        return if(weights.size<=1) {
            val w=weights.firstOrNull()?.let {formatNumber(it)} ?: "–"
            "$w / $reps"
        } else all.joinToString(" · ") {
            (it.weight?.let { w -> formatNumber(w) } ?: "–") + "/" + (it.reps ?: 0)
        }
    }
    fun rows(table:String):List<List<Any?>> {
        require(table in TABLES)
        return query("SELECT * FROM $table ORDER BY id") { c ->
            (0 until c.columnCount).map { i ->
                when(c.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> c.getString(i)
                    else -> null
                }
            }
        }
    }
    fun columns(table:String):List<String> {
        require(table in TABLES)
        return readableDatabase.rawQuery("SELECT * FROM $table LIMIT 0",null).use { c ->
            c.columnNames.toList()
        }
    }
    fun backupJson():String {
        val root=JSONObject().put("format","TrainingsappBackup").put("version",4)
        for(t in TABLES) {
            val columns=columns(t)
            val list=JSONArray()
            for(row in rows(t)) {
                val obj=JSONObject()
                for(i in columns.indices) obj.put(columns[i],row[i] ?: JSONObject.NULL)
                list.put(obj)
            }
            root.put(t,list)
        }
        return root.toString(2)
    }
    fun restoreJson(text:String) {
        // Parse, bound and validate the complete backup before any DELETE runs.
        val root=BackupValidation.validateAndNormalize(text)
        BackupValidation.checkInterrupted()
        val db=writableDatabase
        db.beginTransaction()
        try {
            for(t in TABLES.reversed()) {
                BackupValidation.checkInterrupted()
                db.delete(t,null,null)
            }
            for(t in TABLES) {
                val list=root.getJSONArray(t)
                val columns=columns(t)
                for(i in 0 until list.length()) {
                    BackupValidation.checkInterrupted()
                    val obj=list.getJSONObject(i)
                    val v=ContentValues()
                    for(col in columns) if(obj.has(col) && !obj.isNull(col)) {
                        when(val value=obj.get(col)) {
                            is Int -> v.put(col,value)
                            is Long -> v.put(col,value)
                            is Double -> v.put(col,value)
                            is Boolean -> v.put(col,if(value)1 else 0)
                            else -> v.put(col,value.toString())
                        }
                    }
                    db.insertOrThrow(t,null,v)
                }
            }
            // Also check when legacy inconsistencies prevented enabling FK
            // enforcement on open. Any failure still rolls back all replaced rows.
            db.rawQuery("PRAGMA foreign_key_check",null).use {
                check(!it.moveToFirst()) {"Die Sicherung enthält ungültige Datenverknüpfungen."}
            }
            // A surviving SharedPreferences draft from before a process death
            // cannot attach itself to the newly restored IDs after this commit.
            db.execSQL("INSERT OR REPLACE INTO app_metadata(key,value) VALUES(?,?)",
                arrayOf("generation",java.util.UUID.randomUUID().toString()))
            BackupValidation.checkInterrupted()
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
}

fun formatNumber(value:Double):String =
    if(value==value.toLong().toDouble()) value.toLong().toString()
    else String.format(java.util.Locale.GERMANY,"%.2f",value).trimEnd('0').trimEnd(',')
