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
            .put("plan",session.plan)
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

    private fun csvCell(value:Any?):String {
        val s=value?.toString() ?: ""
        return "\"" + s.replace("\"","\"\"") + "\""
    }
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
        val names=listOf("Trainingsphasen","Uebungen","Trainingsplaene","Trainingseinheiten","Einzelsaetze","Uebungswechsel","Koerpergewicht")
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
