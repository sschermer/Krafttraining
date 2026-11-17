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
    private fun csvCell(value:Any?):String {
        val s=value?.toString() ?: ""
        return "\"" + s.replace("\"","\"\"") + "\""
    }
    fun csv(store:Store):ByteArray {
        val header=listOf("Datum","Trainingsphase","Training","Kondition vorher","Art Kondition","Minuten Kondition",
            "Übung","Satz","Gewicht (kg)","Gewicht pro Seite","Wiederholungen","Sekunden","RIR")
        val lines=mutableListOf(header.joinToString(";") { csvCell(it) })
        for(session in store.sessions()) {
            val all=store.sets(session.id)
            for(s in all) {
                val e=store.exercise(s.exerciseId)
                val row=listOf(
                    date(session.timestamp), store.phase(session.phaseId)?.name ?: "Trainingsphase "+session.phaseId, session.plan, if(session.cardio)"Ja" else "Nein",
                    session.cardioType, session.cardioMinutes, e?.name ?: "",
                    s.number, s.weight, if(e?.perSide==true)"Ja" else "Nein",
                    s.reps, s.seconds, s.rir
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
        val names=listOf("Trainingsphasen","Uebungen","Trainingsplaene","Trainingseinheiten","Einzelsaetze","Koerpergewicht")
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
