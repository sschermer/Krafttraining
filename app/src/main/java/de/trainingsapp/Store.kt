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
    val weight: Double?, val reps: Int?, val seconds: Int?, val rir: Double?
)
data class BodyWeight(val id: Long, val timestamp: Long, val kg: Double)
data class SetDraft(
    var weight: String = "", var reps: String = "", var seconds: String = "",
    var rir: String = "", var completed: Boolean = false
)
data class ExerciseDraft(val exercise: Exercise, val sets: MutableList<SetDraft>)

class Store(context: Context) : SQLiteOpenHelper(context, "training.db", null, 2) {
    companion object {
        val CATEGORIES = listOf(
            "Beine – kniedominant", "Hüfte & hintere Muskelkette",
            "Oberkörper – Drücken", "Oberkörper – Ziehen",
            "Rumpf", "Isolationsübungen", "Beweglichkeit & Stabilität",
            "Ausdauer & Kondition"
        )
        val TABLES = listOf("training_phases", "exercises", "plans", "sessions", "training_sets", "body_weights")
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
        // Editable starting examples; users can replace them freely.
        for ((plan, names) in mapOf(
            "A" to listOf("Wandsitz","Bankdrücken mit Kurzhanteln","Langhantelrudern","Unterarmstütz (Plank)"),
            "B" to listOf("Rumänisches Kreuzheben","Klimmzüge – enger Griff","Schulterdrücken mit Kurzhanteln","Russian Twists")
        )) {
            names.forEachIndexed { index, name ->
                db.execSQL("INSERT INTO plans(plan,exercise_id,position,target_sets) SELECT ?,id,?,2 FROM exercises WHERE name=?",
                    arrayOf(plan, index, name))
            }
        }
        installPhases(db)
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
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion < 2) installPhases(db)
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

    fun planEntries(plan: String): List<Pair<Exercise,Int>> =
        query("""SELECT e.id,e.name,e.category,e.kind,e.equipment,e.per_side,e.archived,p.target_sets FROM plans p
            JOIN exercises e ON e.id=p.exercise_id WHERE p.plan=? ORDER BY p.position""",
            arrayOf(plan)) { c -> Pair(c.asExercise(),c.getInt(7)) }

    fun addToPlan(plan: String, exerciseId: Long) {
        val v=ContentValues().apply {
            put("plan",plan);put("exercise_id",exerciseId)
            put("position",(planEntries(plan).size));put("target_sets",2)
        }
        if(planEntries(plan).none {it.first.id==exerciseId}) writableDatabase.insert("plans",null,v)
    }
    fun removeFromPlan(plan:String,exerciseId:Long) {
        writableDatabase.delete("plans","plan=? AND exercise_id=?",arrayOf(plan,exerciseId.toString()))
    }

    fun sessions(plan: String? = null): List<TrainingSession> =
        query("SELECT id,timestamp,plan,cardio,cardio_type,cardio_minutes FROM sessions " +
            (if(plan==null) "" else "WHERE plan=? ") + "ORDER BY timestamp ASC,id ASC",
            if(plan==null) emptyArray() else arrayOf(plan)) { c ->
            TrainingSession(c.getLong(0),c.getLong(1),c.getString(2),
                c.getInt(3)!=0,c.getString(4),c.getInt(5))
        }
    fun sets(sessionId:Long):List<TrainingSet> = query(
        "SELECT id,session_id,exercise_id,set_number,weight,reps,seconds,rir FROM training_sets WHERE session_id=? ORDER BY set_number",
        arrayOf(sessionId.toString())) { c ->
        TrainingSet(c.getLong(0),c.getLong(1),c.getLong(2),c.getInt(3),
            c.optDouble(4),c.optInt(5),c.optInt(6),c.optDouble(7))
    }
    fun recentSets(exerciseId: Long): List<TrainingSet> {
        val ids = query("""SELECT session_id FROM training_sets
            WHERE exercise_id=? ORDER BY session_id DESC LIMIT 1""",
            arrayOf(exerciseId.toString())) { it.getLong(0) }
        return if(ids.isEmpty()) emptyList() else sets(ids[0]).filter { it.exerciseId==exerciseId }
    }
    fun newDraft(plan: String): MutableList<ExerciseDraft> = planEntries(plan).map { (exercise, count) ->
        val previous=recentSets(exercise.id)
        val rows=(0 until maxOf(2,count)).map { i ->
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
    fun saveSession(plan:String, cardio:Boolean, cardioType:String, cardioMinutes:Int, entries:List<ExerciseDraft>):Long {
        val confirmed=entries.flatMap { entry ->
            entry.sets.mapIndexedNotNull { index, row ->
                if(row.completed) Triple(entry.exercise,index,row) else null
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
                put("timestamp",System.currentTimeMillis());put("plan",plan)
                put("cardio",if(cardio)1 else 0);put("cardio_type",cardioType)
                put("cardio_minutes",cardioMinutes)
            })
            for((exercise,index,row) in confirmed) {
                val reps=row.reps.trim().toIntOrNull()
                val seconds=row.seconds.trim().toIntOrNull()
                db.insertOrThrow("training_sets",null,ContentValues().apply {
                    put("session_id",sessionId);put("exercise_id",exercise.id);put("set_number",index+1)
                    row.weight.trim().replace(',','.').toDoubleOrNull()?.let { put("weight",it) }
                    reps?.let { put("reps",it) }
                    seconds?.let { put("seconds",it) }
                    row.rir.trim().replace(',','.').toDoubleOrNull()?.let { put("rir",it) }
                })
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
    fun deleteBodyWeight(id:Long) { writableDatabase.delete("body_weights","id=?",arrayOf(id.toString())) }
    fun progressExercises(plan:String):List<Exercise> {
        val ids=query("""SELECT DISTINCT exercise_id FROM (
             SELECT exercise_id FROM plans WHERE plan=?
             UNION ALL
             SELECT t.exercise_id FROM training_sets t
             JOIN sessions s ON s.id=t.session_id WHERE s.plan=?
        )""",arrayOf(plan,plan)) {it.getLong(0)}
        return ids.mapNotNull {exercise(it)}.sortedBy {it.name}
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
        val root=JSONObject().put("format","TrainingsappBackup").put("version",1)
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
        val root=JSONObject(text)
        require(root.optString("format")=="TrainingsappBackup" && root.optInt("version")==1) {
            "Keine gültige Trainingsapp-Sicherung"
        }
        for(t in TABLES) require(root.has(t)) {"Tabelle fehlt: $t"}
        val db=writableDatabase
        db.beginTransaction()
        try {
            for(t in TABLES.reversed()) db.delete(t,null,null)
            for(t in TABLES) {
                val list=root.getJSONArray(t)
                val columns=columns(t)
                for(i in 0 until list.length()) {
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
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
}

fun formatNumber(value:Double):String =
    if(value==value.toLong().toDouble()) value.toLong().toString()
    else String.format(java.util.Locale.GERMANY,"%.2f",value).trimEnd('0').trimEnd(',')
