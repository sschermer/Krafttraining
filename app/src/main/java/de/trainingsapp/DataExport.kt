package de.trainingsapp

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DataExport {
    private fun date(value:Long) =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(value))
    // Portable, UTF-8 single-workout export for sharing with ChatGPT.
    // Unlike backupJson(), this is a human-readable analysis document
    // and is not intended for restoring the app database.
    fun workoutJson(store:Store,sessionId:Long):ByteArray {
        val session=store.session(sessionId) ?: error("Trainingseinheit nicht gefunden.")
        val phase=store.phase(session.phaseId)
        val timestampFormat=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.GERMANY)
        val result=org.json.JSONObject()
            .put("format","TrainingsappSingleWorkout")
            .put("schemaVersion",2)
            .put("exportedAt",timestampFormat.format(Date()))
            .put("notes","Ein einzelnes Krafttraining. Gewicht ist in kg angegeben; 'proSeite' bezeichnet die Gewichtseinheit. RIR ist optional.")
        val context=org.json.JSONObject()
            .put("id",session.id)
            .put("datum",timestampFormat.format(Date(session.timestamp)))
            .put("plan",store.planName(session.plan,session.phaseId))
            .put("planId",session.plan)
            .put("trainingsphase",phase?.name ?: "Trainingsphase "+session.phaseId)
            .put("trainingsphaseId",session.phaseId)
        val cardio=org.json.JSONObject()
            .put("vorherAbsolviert",session.cardio)
            .put("art",session.cardioType)
            .put("dauerMinuten",session.cardioMinutes)
        context.put("konditionVorher",cardio)
        result.put("training",context)
        val list=org.json.JSONArray()
        for((exerciseId,rows) in store.sets(session.id).groupBy {it.exerciseId}) {
            val exercise=store.exercise(exerciseId)
            val item=org.json.JSONObject()
                .put("uebungId",exerciseId)
                .put("name",exercise?.name ?: "Unbekannte Übung")
                .put("kategorie",exercise?.category ?: "")
                .put("typ",if(exercise?.kind=="time")"Haltedauer" else "Wiederholungen")
                .put("gewichtProSeite",exercise?.perSide ?: false)
            val items=org.json.JSONArray()
            for(set in rows.sortedBy {it.number}) {
                val entry=org.json.JSONObject()
                    .put("satz",set.number)
                    .put("gewichtKg",set.weight ?: org.json.JSONObject.NULL)
                    .put("wiederholungen",set.reps ?: org.json.JSONObject.NULL)
                    .put("sekunden",set.seconds ?: org.json.JSONObject.NULL)
                    .put("rir",set.rir ?: org.json.JSONObject.NULL)
                    .put("bemerkung",set.note)
                items.put(entry)
            }
            item.put("saetze",items)
            list.put(item)
        }
        result.put("uebungen",list)
        val swaps=org.json.JSONArray()
        for(event in store.exerciseSwaps(session.id)) {
            swaps.put(org.json.JSONObject()
                .put("urspruenglicheUebungId",event.originalExerciseId)
                .put("urspruenglicheUebung",store.exercise(event.originalExerciseId)?.name ?: "")
                .put("ersatzUebungId",event.replacementExerciseId)
                .put("ersatzUebung",store.exercise(event.replacementExerciseId)?.name ?: "")
                .put("grund",event.reason))
        }
        result.put("uebungswechsel",swaps)
        return result.toString(2).toByteArray(Charsets.UTF_8)
    }

    fun workoutFilename(store:Store,sessionId:Long):String {
        val session=store.session(sessionId) ?: error("Trainingseinheit nicht gefunden.")
        val day=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(Date(session.timestamp))
        return "Trainingsapp-"+day+"-"+session.plan+"-"+session.id+".json"
    }


    /**
     * Lesbarer Export mit genau denselben trainingsbezogenen Informationen wie
     * die Einzeltraining-JSON: Datum, Phase, Kondition, Uebungen, Saetze, RIR,
     * Bemerkungen und Uebungswechsel. Keine direkte Uebertragung an ChatGPT.
     */
    fun workoutText(store:Store,sessionId:Long):String {
        val session=store.session(sessionId) ?: error("Trainingseinheit nicht gefunden.")
        return buildString {
            append("TRAININGSAPP - EINZELTRAINING ZUR AUSWERTUNG\n")
            append("Exportiert: ").append(date(System.currentTimeMillis())).append("\n")
            append("Format: lesbarer Text, entspricht Einzeltraining-JSON Schema 2\n\n")
            appendWorkoutText(store,session,this)
        }
    }

    private fun appendWorkoutText(store:Store,session:TrainingSession,out:StringBuilder) {
        val phase=store.phase(session.phaseId)
        out.append("Training-ID: ").append(session.id).append("\n")
        out.append("Trainingsdatum: ").append(date(session.timestamp)).append("\n")
        out.append("Trainingstyp: ").append(store.planName(session.plan,session.phaseId)).append("\n")
        out.append("Trainingsphase: ").append(phase?.name ?: "Phase "+session.phaseId)
            .append(" (ID ").append(session.phaseId).append(")\n")
        out.append("Kondition vor dem Krafttraining: ")
            .append(if(session.cardio)"Ja" else "Nein").append("\n")
        out.append("Konditionsart: ").append(session.cardioType.ifBlank { "Nicht angegeben" }).append("\n")
        out.append("Konditionsdauer: ").append(session.cardioMinutes).append(" Minuten\n")
        out.append("GELEISTETE SAETZE:\n")
        val grouped=store.sets(session.id).groupBy { it.exerciseId }
        if(grouped.isEmpty())out.append("  Keine Saetze gespeichert\n")
        for((exerciseId,sets) in grouped) {
            val exercise=store.exercise(exerciseId)
            out.append("- Uebung: ").append(exercise?.name ?: "Unbekannte Uebung")
                .append(" (ID ").append(exerciseId).append(")\n")
            out.append("  Kategorie: ").append(exercise?.category ?: "Unbekannt").append("\n")
            out.append("  Typ: ").append(if(exercise?.kind=="time")"Haltedauer" else "Wiederholungen")
                .append("\n")
            out.append("  Gewicht bezogen auf: ")
                .append(if(exercise?.perSide==true)"pro Seite" else "gesamte Uebung")
                .append("\n")
            for(set in sets.sortedBy { it.number }) {
                out.append("  Satz ").append(set.number).append(": ")
                if(exercise?.kind=="time") {
                    out.append(set.seconds?.toString() ?: "Nicht angegeben").append(" Sekunden")
                    if(set.weight!=null)out.append(" | Gewicht: ").append(formatNumber(set.weight)).append(" kg")
                } else {
                    out.append("Gewicht: ").append(set.weight?.let {formatNumber(it)} ?: "Nicht angegeben")
                        .append(" kg | Wiederholungen: ").append(set.reps?.toString() ?: "Nicht angegeben")
                }
                out.append(" | RIR: ").append(set.rir?.let {formatNumber(it)} ?: "Nicht angegeben")
                    .append("\n")
                if(set.note.isNotBlank()) {
                    out.append("    Bemerkung: ")
                        .append(set.note.trim().replace("\r\n","\n").replace("\n","\n    "))
                        .append("\n")
                }
            }
        }
        out.append("UEBUNGSWECHSEL:\n")
        val swaps=store.exerciseSwaps(session.id)
        if(swaps.isEmpty())out.append("  Keine\n")
        for(swap in swaps) {
            out.append("- Urspruengliche Uebung: ")
                .append(store.exercise(swap.originalExerciseId)?.name ?: "Unbekannt")
                .append(" (ID ").append(swap.originalExerciseId).append(")\n")
            out.append("  Ersatzuebung: ")
                .append(store.exercise(swap.replacementExerciseId)?.name ?: "Unbekannt")
                .append(" (ID ").append(swap.replacementExerciseId).append(")\n")
            out.append("  Grund: ").append(swap.reason.ifBlank { "Nicht angegeben" }).append("\n")
        }
    }

    /**
     * Gesamter auswertungsrelevanter Bestand: alle Trainings, A/B-Plaene,
     * Trainingsphasen, Uebungskatalog und Koerpergewichtswerte. Kein
     * Datenbanksicherungsformat; der bestehende JSON-Backup-Export bleibt separat.
     */
    fun fullTrainingText(store:Store):String = buildString {
        append("TRAININGSAPP - GESAMTE TRAININGSDATEN ZUR CHATGPT-AUSWERTUNG\n")
        append("Exportiert: ").append(date(System.currentTimeMillis())).append("\n")
        append("Dies ist ein lesbarer Bericht, keine wiederherstellbare Datensicherung.\n\n")
        append("TRAININGSPHASEN UND TRAININGSPLAENE\n")
        val phases=store.phases().sortedBy { it.id }
        if(phases.isEmpty())append("Keine Trainingsphase vorhanden.\n")
        for(phase in phases) {
            append("Phase ").append(phase.id).append(": ").append(phase.name)
                .append(" | Beginn: ").append(date(phase.startedAt))
                .append(" | Abschluss: ").append(phase.endedAt?.let {date(it)} ?: "Noch aktiv")
                .append("\n")
            for(plan in store.planIdentities(phase.id)) {
                append("  ").append(plan.name).append(" (ID ").append(plan.key).append("):\n")
                val entries=store.planEntries(plan.key,phase.id)
                if(entries.isEmpty())append("    Keine Uebungen ausgewaehlt\n")
                for((exercise,targetSets) in entries) {
                    append("    - ").append(exercise.name).append(" (ID ").append(exercise.id)
                        .append(") | Kategorie: ").append(exercise.category)
                        .append(" | Zielsaetze: ").append(targetSets)
                        .append(" | Typ: ").append(if(exercise.kind=="time")"Haltedauer" else "Wiederholungen")
                        .append(" | Equipment: ").append(exercise.equipment)
                        .append(" | Gewicht pro Seite: ").append(if(exercise.perSide)"Ja" else "Nein")
                        .append("\n")
                }
            }
        }
        append("\nTRAININGSVERLAUF (chronologisch)\n")
        val sessions=store.sessions()
        if(sessions.isEmpty())append("Keine abgeschlossenen Trainings vorhanden.\n")
        for((index,session) in sessions.withIndex()) {
            append("\n=== Training ").append(index+1).append(" von ").append(sessions.size).append(" ===\n")
            appendWorkoutText(store,session,this)
        }
        append("\nKOERPERGEWICHT\n")
        val weights=store.bodyWeights()
        if(weights.isEmpty())append("Keine Gewichtsmessungen vorhanden.\n")
        for(weight in weights) {
            append("- ").append(date(weight.timestamp)).append(": ")
                .append(formatNumber(weight.kg)).append(" kg\n")
        }
        append("\nUEBUNGSKATALOG (einschliesslich eigener und archivierter Uebungen)\n")
        val exercises=store.exercises(includeArchived=true)
        if(exercises.isEmpty())append("Keine Uebungen vorhanden.\n")
        for(ex in exercises) {
            append("- ID ").append(ex.id).append(": ").append(ex.name)
                .append(" | Kategorie: ").append(ex.category)
                .append(" | Typ: ").append(if(ex.kind=="time")"Haltedauer" else "Wiederholungen")
                .append(" | Geraet: ").append(ex.equipment)
                .append(" | Gewicht pro Seite: ").append(if(ex.perSide)"Ja" else "Nein")
                .append(" | Archiviert: ").append(if(ex.archived)"Ja" else "Nein")
                .append("\n")
        }
    }

    private fun csvCell(value:Any?):String = CsvEncoding.cell(value)
    fun csv(store:Store):ByteArray {
        val header=listOf("Datum","Trainingsphase","Training","Kondition vorher","Art Kondition","Minuten Kondition",
            "Übung","Satz","Gewicht (kg)","Gewicht pro Seite","Wiederholungen","Sekunden","RIR","Bemerkung")
        val lines=mutableListOf(header.joinToString(";") { csvCell(it) })
        for(session in store.sessions()) {
            val all=store.sets(session.id)
            for(s in all) {
                val e=store.exercise(s.exerciseId)
                val row=listOf(
                    date(session.timestamp), store.phase(session.phaseId)?.name ?: "Trainingsphase "+session.phaseId, session.plan, if(session.cardio)"Ja" else "Nein",
                    session.cardioType, session.cardioMinutes, e?.name ?: "",
                    s.number, s.weight, if(e?.perSide==true)"Ja" else "Nein",
                    s.reps, s.seconds, s.rir, s.note
                )
                lines.add(row.joinToString(";") { csvCell(it) })
            }
        }
        return ("\uFEFF"+lines.joinToString("\r\n")).toByteArray(Charsets.UTF_8)
    }

    private fun xml(value:String):String=value.replace("&","&amp;").replace("<","&lt;")
        .replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")

    private fun colName(i:Int):String {
        var n=i+1
        var s=""
        while(n>0) { n--;s=('A'.code+n%26).toChar()+s;n/=26 }
        return s
    }
    private fun sheet(columns:List<String>,rows:List<List<Any?>>):String {
        val out=StringBuilder()
        out.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        out.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        for((index,row) in (listOf(columns.map {it as Any?})+rows).withIndex()) {
            out.append("<row r=\"").append(index+1).append("\">")
            for((j,v) in row.withIndex()) {
                if(v==null) continue
                val address=colName(j)+(index+1)
                if(index>0 && v is Number) {
                    out.append("<c r=\"").append(address).append("\"><v>")
                        .append(v.toString()).append("</v></c>")
                } else {
                    out.append("<c r=\"").append(address).append("\" t=\"inlineStr\"><is><t>")
                        .append(xml(v.toString())).append("</t></is></c>")
                }
            }
            out.append("</row>")
        }
        out.append("</sheetData></worksheet>")
        return out.toString()
    }
    private fun table(store:Store,name:String):Pair<List<String>,List<List<Any?>>> {
        val columns=store.columns(name)
        val rows=store.rows(name)
        if(name=="training_phases") {
            return Pair(columns+listOf("beginn","abschluss"),rows.map {raw ->
                raw+listOf(
                    date((raw[columns.indexOf("started_at")] as Number).toLong()),
                    raw[columns.indexOf("ended_at")]?.let {date((it as Number).toLong())}
                )
            })
        }
        if(name=="sessions" || name=="body_weights") {
            val timeIndex=columns.indexOf("timestamp")
            val cols=columns.toMutableList().apply {add(timeIndex+1,"datum_uhrzeit")}
            val revised=rows.map { raw ->
                raw.toMutableList().apply {add(timeIndex+1,date((raw[timeIndex] as Number).toLong()))}
            }
            return Pair(cols,revised)
        }
        return Pair(columns,rows)
    }
    fun xlsx(store:Store):ByteArray {
        val sheets=Store.TABLES.map { table(store,it) }
        val names=listOf("Trainingsphasen","Uebungen","Trainingsplaene","Trainingseinheiten","Einzelsaetze","Uebungswechsel","Koerpergewicht","PlanIdentitaeten","HistorischePlanuebungen")
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(path:String,content:String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val overrides=(1..sheets.size).joinToString("") {
                """<Override PartName="/xl/worksheets/sheet$it.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
            }
            add("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                $overrides</Types>""")
            add("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""")
            val workbookSheets=names.mapIndexed { i,n ->
                """<sheet name="$n" sheetId="${i+1}" r:id="rId${i+1}"/>"""
            }.joinToString("")
            add("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets>$workbookSheets</sheets></workbook>""")
            val rels=names.indices.joinToString("") { i ->
                """<Relationship Id="rId${i+1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i+1}.xml"/>"""
            }
            add("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>""")
            for((i,t) in sheets.withIndex()) add("xl/worksheets/sheet${i+1}.xml",sheet(t.first,t.second))
        }
        return out.toByteArray()
    }
}
