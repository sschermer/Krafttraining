package de.trainingsapp

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private lateinit var page: LinearLayout
    private var tab="Start"
    private var plan="A"
    private var analysis="Tabelle"
    private var draft: MutableList<ExerciseDraft>?=null
    private var workoutCardio=false
    private var workoutCardioType=""
    private var workoutCardioMinutes=0
    private var exportType="xlsx"

    private val dark=Color.rgb(24,58,51)
    private val accent=Color.rgb(25,113,100)
    private val light=Color.rgb(245,248,246)
    private val ink=Color.rgb(37,47,44)
    private val muted=Color.rgb(105,120,114)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        store=Store(this)
        render()
    }
    private fun dp(n:Int):Int=(n*resources.displayMetrics.density+0.5f).toInt()
    private fun shape(color:Int,radius:Int=14,stroke:Int?=null):GradientDrawable =
        GradientDrawable().apply {
            setColor(color);cornerRadius=dp(radius).toFloat()
            if(stroke!=null) setStroke(dp(1),stroke)
        }
    private fun column():LinearLayout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
    private fun row():LinearLayout=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun text(s:String,size:Float=15f,bold:Boolean=false,color:Int=ink):TextView =
        TextView(this).apply {
            this.text=s;textSize=size;setTextColor(color)
            if(bold) setTypeface(null,android.graphics.Typeface.BOLD)
            gravity=Gravity.CENTER_VERTICAL
        }
    private fun gap(parent:LinearLayout,height:Int=10) {
        parent.addView(View(this),LinearLayout.LayoutParams(1,dp(height)))
    }
    private fun add(parent:LinearLayout,view:View,width:Int=-1,height:Int=-2) {
        parent.addView(view,LinearLayout.LayoutParams(if(width<0)width else dp(width),if(height<0)height else dp(height)))
    }
    private fun card(parent:LinearLayout):LinearLayout {
        val c=column().apply {
            background=shape(Color.WHITE,16,Color.rgb(222,233,228))
            setPadding(dp(14),dp(14),dp(14),dp(14))
        }
        add(parent,c);gap(parent,11);return c
    }
    private fun button(title:String,primary:Boolean=true,onClick:()->Unit):Button =
        Button(this).apply {
            text=title;textSize=14f;isAllCaps=false
            setTextColor(if(primary)Color.WHITE else accent)
            background=shape(if(primary)accent else Color.rgb(230,243,238),11)
            setPadding(dp(9),dp(4),dp(9),dp(4))
            minHeight=dp(43);minimumHeight=dp(43)
            setOnClickListener {onClick()}
        }
    private fun edit(value:String="",hint:String="",number:Boolean=false):EditText =
        EditText(this).apply {
            setText(value);this.hint=hint;textSize=15f
            singleLine=true
            inputType=if(number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelectAllOnFocus(true)
            background=shape(light,8,Color.rgb(210,226,217))
            setPadding(dp(8),dp(9),dp(8),dp(9))
        }
    private fun watch(field:EditText,callback:(String)->Unit) {
        field.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {callback(s?.toString() ?: "")}
            override fun afterTextChanged(s:Editable?) {}
        })
    }
    private fun date(ts:Long,short:Boolean=false):String=SimpleDateFormat(
        if(short)"dd.MM.\nyy" else "dd.MM.yyyy HH:mm",Locale.GERMANY).format(Date(ts))
    private fun info(msg:String) {Toast.makeText(this,msg,Toast.LENGTH_LONG).show()}

    private fun render() {
        root=column().apply {setBackgroundColor(light)}
        setContentView(root)
        val header=column().apply {
            setBackgroundColor(dark);setPadding(dp(18),dp(14),dp(18),dp(13))
        }
        add(header,text("TRAININGSAPP",21f,true,Color.WHITE))
        add(header,text(if(draft!=null)"Training $plan läuft" else "Dein Training. Deine Entwicklung.",12f,false,Color.rgb(195,224,212)))
        add(root,header)
        val scroll=ScrollView(this).apply {fillViewport=true;clipToPadding=false}
        page=column().apply {setPadding(dp(14),dp(16),dp(14),dp(20))}
        scroll.addView(page)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        when(tab) {
            "Start"->home()
            "Training"->if(draft==null)plans() else workout()
            "Katalog"->catalog()
            "Verlauf"->results()
            else->dataPage()
        }
        val nav=row().apply {
            setPadding(dp(3),dp(5),dp(3),dp(5));setBackgroundColor(Color.WHITE)
        }
        val tabs=listOf("Start","Training","Katalog","Verlauf","Daten")
        val icons=listOf("⌂","A/B","≡","↗","⇩")
        tabs.forEachIndexed {i,name ->
            val b=column().apply {gravity=Gravity.CENTER}
            add(b,text(icons[i],18f,true,if(tab==name)accent else muted).apply{gravity=Gravity.CENTER})
            add(b,text(name,10f,tab==name,if(tab==name)accent else muted).apply{gravity=Gravity.CENTER})
            b.setOnClickListener {tab=name;render()}
            nav.addView(b,LinearLayout.LayoutParams(0,dp(55),1f))
        }
        add(root,nav)
    }
    private fun title(s:String,subtitle:String?=null) {
        add(page,text(s,22f,true));gap(page,4)
        if(subtitle!=null) {add(page,text(subtitle,13f,false,muted));gap(page,12)}
        else gap(page,12)
    }
    private fun planSelector(parent:LinearLayout) {
        val r=row()
        for(name in listOf("A","B")) {
            val b=button("Training $name",plan==name) {
                plan=name;render()
            }
            r.addView(b,LinearLayout.LayoutParams(0,dp(45),1f))
            if(name=="A")add(r,View(this),7,1)
        }
        add(parent,r);gap(parent,14)
    }
    private fun home() {
        title("Übersicht","Training A oder B jederzeit frei wählen.")
        planSelector(page)
        val c=card(page)
        add(c,text("Nächstes Training: $plan",18f,true));gap(c,7)
        store.planEntries(plan).forEach { (exercise,_) ->
            add(c,text("• "+exercise.name,14f))
        }
        gap(c,12)
        add(c,button("Training $plan beginnen") {askCardio()})
        gap(page,5)
        val recent=store.sessions().lastOrNull()
        val summary=card(page)
        add(summary,text("Letztes Training",17f,true));gap(summary,5)
        add(summary,text(recent?.let {"Training ${it.plan} · ${date(it.timestamp)}"} ?: "Noch kein Training gespeichert.",14f))
        gap(summary,8)
        add(summary,button("Fortschritt ansehen",false){tab="Verlauf";analysis="Tabelle";render()})
        val weights=store.bodyWeights()
        val w=card(page)
        add(w,text("Körpergewicht",17f,true));gap(w,5)
        add(w,text(weights.lastOrNull()?.let {formatNumber(it.kg)+" kg · "+date(it.timestamp)}
            ?: "Noch keine Messung",15f))
        gap(w,9)
        add(w,button("+ Gewicht eintragen",false){weightDialog()})
        if(draft!=null) {
            val running=card(page)
            add(running,text("Training noch nicht abgeschlossen",15f,true))
            add(running,button("Weitertrainieren"){tab="Training";render()})
        }
    }

    private fun askCardio() {
        if(store.planEntries(plan).isEmpty()) {info("Füge dem Trainingsplan zuerst Übungen hinzu.");return}
        val box=column().apply {setPadding(dp(20),dp(6),dp(20),0)}
        add(box,text("Hast du vor diesem Krafttraining Kondition gemacht?",15f))
        val yes=CheckBox(this).apply {text="Ja, vorher Kondition gemacht"}
        add(box,yes)
        val type=edit("","Art, z. B. Laufen")
        val minutes=edit("","Minuten",true)
        type.visibility=View.GONE;minutes.visibility=View.GONE
        add(box,type);gap(box,7);add(box,minutes)
        yes.setOnCheckedChangeListener {_,checked ->
            type.visibility=if(checked)View.VISIBLE else View.GONE
            minutes.visibility=if(checked)View.VISIBLE else View.GONE
        }
        AlertDialog.Builder(this).setTitle("Vor dem Training")
            .setView(box).setNegativeButton("Abbrechen",null)
            .setPositiveButton("Training starten"){_,_ ->
                workoutCardio=yes.isChecked
                workoutCardioType=if(yes.isChecked)type.text.toString().trim() else ""
                workoutCardioMinutes=if(yes.isChecked)minutes.text.toString().toIntOrNull() ?: 0 else 0
                draft=store.newDraft(plan)
                tab="Training";render()
            }.show()
    }

    private fun workout() {
        title("Training $plan","Vorwerte sind vorausgefüllt. RIR wird neu eingetragen.")
        if(workoutCardio) {
            val c=card(page)
            add(c,text("Vorher Kondition: "+(workoutCardioType.ifBlank{"Ja"})+
                (if(workoutCardioMinutes>0)" · $workoutCardioMinutes min" else ""),13f))
        }
        val list=draft ?: return
        for(item in list) {
            val c=card(page)
            add(c,text(item.exercise.name,17f,true));gap(c,3)
            add(c,text(if(item.exercise.kind=="time")"Haltedauer in Sekunden" else
                (if(item.exercise.perSide)"Gewicht in kg pro Seite" else "Gewicht in kg (optional)")+" · Wiederholungen",12f,false,muted))
            gap(c,9)
            val headings=row()
            add(headings,text("Satz",12f,true,muted),42)
            if(item.exercise.kind=="time") {
                add(headings,text("Sekunden",12f,true,muted),95)
                add(headings,text("RIR (opt.)",12f,true,muted),100)
            } else {
                add(headings,text("kg",12f,true,muted),85)
                add(headings,text("Wdh.",12f,true,muted),85)
                add(headings,text("RIR (opt.)",12f,true,muted),95)
            }
            add(c,headings);gap(c,4)
            item.sets.forEachIndexed {i,s ->
                val r=row()
                add(r,text((i+1).toString()+".",14f,true),42)
                if(item.exercise.kind=="time") {
                    val sec=edit(s.seconds,"Sek.",true)
                    add(r,sec,90);watch(sec){s.seconds=it}
                } else {
                    val kg=edit(s.weight,"kg",true)
                    add(r,kg,80);watch(kg){s.weight=it}
                    add(r,View(this),5,1)
                    val reps=edit(s.reps,"Wdh.",true)
                    add(r,reps,80);watch(reps){s.reps=it}
                }
                add(r,View(this),5,1)
                val rir=edit(s.rir,"–",true)
                add(r,rir,65);watch(rir){s.rir=it}
                add(c,r);gap(c,7)
            }
            val controls=row()
            controls.addView(button("+ Satz",false) {
                item.sets.add(SetDraft(weight=item.sets.lastOrNull()?.weight ?: ""))
                render()
            },LinearLayout.LayoutParams(0,dp(44),1f))
            if(item.sets.size>2) {
                add(controls,View(this),8,1)
                controls.addView(button("− Letzter Satz",false) {
                    item.sets.removeAt(item.sets.lastIndex);render()
                },LinearLayout.LayoutParams(0,dp(44),1f))
            }
            add(c,controls)
        }
        add(page,button("Training abschließen und speichern") {
            val valid=list.any {item->item.sets.any {
                if(item.exercise.kind=="time") (it.seconds.toIntOrNull() ?: 0)>0
                else (it.reps.toIntOrNull() ?: 0)>0
            }}
            if(!valid) {info("Trage mindestens einen absolvierten Satz ein.");return@button}
            AlertDialog.Builder(this).setTitle("Training abschließen?")
                .setMessage("Die erfassten Sätze werden dauerhaft gespeichert.")
                .setNegativeButton("Weitertrainieren",null).setPositiveButton("Speichern"){_,_->
                    store.saveSession(plan,workoutCardio,workoutCardioType,workoutCardioMinutes,list)
                    draft=null;tab="Verlauf";analysis="Tabelle";render()
                    info("Training gespeichert.")
                }.show()
        });gap(page,10)
        add(page,button("Training abbrechen",false) {
            AlertDialog.Builder(this).setTitle("Training verwerfen?")
                .setMessage("Nicht gespeicherte Eingaben gehen verloren.")
                .setNegativeButton("Zurück",null).setPositiveButton("Verwerfen"){_,_->
                    draft=null;tab="Start";render()
                }.show()
        })
    }

    private fun plans() {
        title("Trainingspläne","A/B-Einheiten unabhängig zusammenstellen.")
        planSelector(page)
        for((ex,_) in store.planEntries(plan)) {
            val c=card(page)
            val r=row()
            r.addView(text(ex.name,15f,true),LinearLayout.LayoutParams(0,-2,1f))
            add(r,button("Entfernen",false){
                store.removeFromPlan(plan,ex.id);render()
            },105)
            add(c,r)
        }
        add(page,button("+ Übung zu Training $plan") {
            val options=store.exercises().filter {candidate ->
                store.planEntries(plan).none {it.first.id==candidate.id}
            }
            if(options.isEmpty()) {info("Alle Übungen sind bereits zugeordnet.");return@button}
            AlertDialog.Builder(this).setTitle("Übung hinzufügen")
                .setItems(options.map {it.name}.toTypedArray()) {_,which ->
                    store.addToPlan(plan,options[which].id);render()
                }.show()
        })
        gap(page,12)
        add(page,button("Training $plan beginnen",false){askCardio()})
    }

    private fun catalog() {
        title("Übungskatalog","43 Startübungen plus eigene Übungen; Kategorien frei wählbar.")
        add(page,button("+ Eigene Übung"){exerciseDialog(null)})
        gap(page,15)
        val all=store.exercises()
        for(category in all.map {it.category}.distinct()) {
            add(page,text(category,17f,true));gap(page,6)
            for(ex in all.filter {it.category==category}) {
                val c=card(page)
                val r=row()
                val labels=column()
                add(labels,text(ex.name,14f,true));gap(labels,2)
                add(labels,text((if(ex.kind=="time")"Sekunden" else "Wiederholungen")+
                    " · "+ex.equipment,11f,false,muted))
                r.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
                add(r,button("Bearbeiten",false){exerciseDialog(ex)},100)
                add(c,r)
            }
        }
    }
    private fun exerciseDialog(old:Exercise?) {
        val box=column().apply {setPadding(dp(20),dp(5),dp(20),0)}
        add(box,text("Übungsname",13f,true));val name=edit(old?.name ?: "","Name");add(box,name)
        gap(box,9);add(box,text("Kategorie (auch neue Namen möglich)",13f,true))
        val cat=AutoCompleteTextView(this).apply {
            setText(old?.category ?: Store.CATEGORIES[0]);textSize=15f
            setAdapter(ArrayAdapter(this@MainActivity,android.R.layout.simple_dropdown_item_1line,
                (Store.CATEGORIES+store.exercises().map {it.category}).distinct()))
            threshold=1
        }
        add(box,cat)
        gap(box,9)
        add(box,text("Art der Übung",13f,true))
        val kinds=listOf("Wiederholungen","Haltedauer (Sekunden)")
        val kind=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,kinds)
            setSelection(if(old?.kind=="time")1 else 0)
        }
        add(box,kind)
        gap(box,9);add(box,text("Geräte",13f,true))
        val eq=edit(old?.equipment ?: "","z. B. Langhantel, Bank");add(box,eq)
        val perSide=CheckBox(this).apply {
            text="Gewicht pro Seite erfassen";isChecked=old?.perSide ?: true
        }
        add(box,perSide)
        val dialog=AlertDialog.Builder(this).setTitle(if(old==null)"Neue Übung" else "Übung bearbeiten")
            .setView(box).setNegativeButton("Abbrechen",null)
            .setPositiveButton("Speichern",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n=name.text.toString().trim();val catName=cat.text.toString().trim()
                if(n.isBlank() || catName.isBlank()) {info("Name und Kategorie sind erforderlich.");return@setOnClickListener}
                store.upsertExercise(Exercise(old?.id ?: 0L,n,catName,
                    if(kind.selectedItemPosition==1)"time" else "reps",
                    eq.text.toString().trim(),perSide.isChecked,old?.archived ?: false))
                dialog.dismiss();render()
            }
        }
        dialog.show()
    }

    private fun results() {
        title("Fortschritt","Tabellen, Trainingskurven und Körpergewicht.")
        val buttons=row()
        listOf("Tabelle","Kurven","Gewicht").forEach {name ->
            buttons.addView(button(name,analysis==name){analysis=name;render()},
                LinearLayout.LayoutParams(0,dp(42),1f))
        }
        add(page,buttons);gap(page,14)
        when(analysis) {
            "Tabelle"->progressTable()
            "Kurven"->exerciseChart()
            else->weightPage()
        }
    }
    private fun progressTable() {
        planSelector(page)
        add(page,text("Älteste Einheiten links, neueste rechts.",12f,false,muted));gap(page,9)
        val sessions=store.sessions(plan)
        val exercises=store.progressExercises(plan)
        if(sessions.isEmpty()) {add(page,text("Noch keine gespeicherten Trainingseinheiten.",14f));return}
        val whole=row().apply {gravity=Gravity.TOP}
        val fixed=column()
        val fixedHead=cell("Übung",142,true)
        add(fixed,fixedHead)
        for(ex in exercises) add(fixed,cell(ex.name,142,true))
        add(whole,fixed,142)
        val scroll=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=true;fillViewport=false
        }
        val right=column()
        val dates=row()
        for(s in sessions) add(dates,cell(date(s.timestamp,true),116,true))
        add(right,dates)
        for(ex in exercises) {
            val values=row()
            for(s in sessions) {
                val v=cell(store.formatCell(s.id,ex),116,false)
                v.setOnClickListener {sessionDetails(s,ex)}
                add(values,v)
            }
            add(right,values)
        }
        scroll.addView(right)
        whole.addView(scroll,LinearLayout.LayoutParams(0,-2,1f))
        add(page,whole)
        scroll.post {scroll.fullScroll(View.FOCUS_RIGHT)}
        gap(page,10)
        add(page,text("Beispiel: 30 / 10-10 = 30 kg pro Seite, 10 und 10 Wdh. · 45s-40s = Haltedauer.",12f,false,muted))
    }
    private fun cell(value:String,width:Int,bold:Boolean):TextView=text(value,if(bold)12f else 13f,bold).apply {
        gravity=Gravity.CENTER_VERTICAL or (if(width==142)Gravity.LEFT else Gravity.CENTER_HORIZONTAL)
        setPadding(dp(6),dp(2),dp(6),dp(2))
        background=shape(Color.WHITE,0,Color.rgb(224,232,227))
        layoutParams=LinearLayout.LayoutParams(dp(width),dp(55))
        maxLines=2
    }
    private fun sessionDetails(s:TrainingSession,ex:Exercise) {
        val entries=store.sets(s.id).filter {it.exerciseId==ex.id}
        val body=entries.joinToString("\n") {
            "Satz ${it.number}: "+(if(ex.kind=="time")"${it.seconds ?: 0}s"
            else "${it.weight?.let {w->formatNumber(w)} ?: "–"} kg / ${it.reps ?: 0} Wdh.")+
                (it.rir?.let {r->" · RIR "+formatNumber(r)} ?: "")
        }
        AlertDialog.Builder(this).setTitle(ex.name+" · "+date(s.timestamp))
            .setMessage(body.ifBlank{"Keine Sätze"}).setPositiveButton("Schließen",null).show()
    }
    private fun exerciseChart() {
        planSelector(page)
        val all=store.progressExercises(plan)
        if(all.isEmpty()) {add(page,text("Noch keine Übungen im Plan.",14f));return}
        val picker=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,all.map {it.name})
        }
        add(page,picker);gap(page,10)
        val holder=column()
        add(page,holder)
        fun updateChart(index:Int) {
            holder.removeAllViews()
            val ex=all.getOrNull(index) ?: return
            val points=store.sessions(plan).mapNotNull {s->
                val sets=store.sets(s.id).filter {it.exerciseId==ex.id}
                val numbers=if(ex.kind=="time")sets.mapNotNull {it.seconds?.toDouble()}
                    else sets.mapNotNull {it.weight }
                numbers.maxOrNull()?.let {Pair(s.timestamp,it)}
            }
            if(points.isEmpty()) {add(holder,text("Noch keine Messwerte für diese Übung."));return}
            add(holder,text(if(ex.kind=="time")"Maximale Haltedauer (s)" else
                "Höchstes eingetragenes Gewicht (kg)",13f,false,muted))
            add(holder,TrendChartView(points,if(ex.kind=="time")"s" else "kg"),-1,230)
            if(points.size==1) add(holder,text("Die Kurve wächst mit weiteren Trainings.",12f,false,muted))
        }
        picker.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,v:View?,position:Int,id:Long) {updateChart(position)}
        }
    }
    private fun weightPage() {
        val weights=store.bodyWeights()
        val summary=card(page)
        add(summary,text("Körpergewicht",17f,true))
        add(summary,text(weights.lastOrNull()?.let {formatNumber(it.kg)+" kg · "+date(it.timestamp)}
            ?: "Noch kein Körpergewicht gespeichert.",14f))
        gap(summary,9);add(summary,button("+ Gewicht mit Datum/Uhrzeit erfassen"){weightDialog()})
        if(weights.isNotEmpty()) {
            add(page,TrendChartView(weights.map {Pair(it.timestamp,it.kg)},"kg"),-1,230)
            gap(page,10)
            for(w in weights.asReversed().take(50)) {
                val c=card(page)
                val r=row()
                r.addView(text(date(w.timestamp)+"  ·  "+formatNumber(w.kg)+" kg",14f),
                    LinearLayout.LayoutParams(0,-2,1f))
                add(r,button("Löschen",false){
                    AlertDialog.Builder(this).setTitle("Messung löschen?")
                        .setMessage(date(w.timestamp)+" · "+formatNumber(w.kg)+" kg")
                        .setNegativeButton("Abbrechen",null)
                        .setPositiveButton("Löschen"){_,_->store.deleteBodyWeight(w.id);render()}
                        .show()
                },100)
                add(c,r)
            }
        }
    }
    private fun weightDialog() {
        val box=column().apply {setPadding(dp(22),dp(8),dp(22),0)}
        add(box,text("Gewicht in kg",13f,true))
        val input=edit("","z. B. 82,4",true);add(box,input);gap(box,9)
        val time=text("Jetzt: "+date(System.currentTimeMillis()),13f,false,muted)
        add(box,time)
        var ts=System.currentTimeMillis()
        gap(box,7)
        add(box,button("Datum und Uhrzeit ändern",false){
            val cal=Calendar.getInstance().apply {timeInMillis=ts}
            DatePickerDialog(this,{_,y,m,d->
                cal.set(Calendar.YEAR,y);cal.set(Calendar.MONTH,m);cal.set(Calendar.DAY_OF_MONTH,d)
                TimePickerDialog(this,{_,h,min->
                    cal.set(Calendar.HOUR_OF_DAY,h);cal.set(Calendar.MINUTE,min)
                    cal.set(Calendar.SECOND,0);ts=cal.timeInMillis;time.text=date(ts)
                },cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true).show()
            },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show()
        })
        val dialog=AlertDialog.Builder(this).setTitle("Körpergewicht eintragen").setView(box)
            .setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val kg=input.text.toString().replace(',','.').toDoubleOrNull()
                if(kg==null || kg<=0 || kg>500) {info("Bitte gültiges Körpergewicht eintragen.");return@setOnClickListener}
                store.addBodyWeight(kg,ts);dialog.dismiss();render()
            }
        }
        dialog.show()
    }

    private fun dataPage() {
        title("Daten & Sicherung","Alle Trainingsdaten bleiben lokal auf dem Gerät.")
        val c=card(page)
        add(c,text("Daten exportieren",17f,true));gap(c,7)
        add(c,button("Excel-Datei herunterladen (.xlsx)") {saveFile("xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","Trainingsapp.xlsx")})
        gap(c,8)
        add(c,button("CSV-Datei herunterladen",false){saveFile("csv","text/csv","Trainingssaetze.csv")})
        val backup=card(page)
        add(backup,text("Vollständige Datensicherung",17f,true));gap(backup,7)
        add(backup,text("JSON enthält Übungen, Pläne, Sätze, Konditionsangaben und Körpergewicht. Beim Import werden alle aktuellen Daten ersetzt.",13f))
        gap(backup,10)
        add(backup,button("Sicherung exportieren (.json)"){saveFile("json","application/json","Trainingsapp-Backup.json")})
        gap(backup,8)
        add(backup,button("Sicherung wiederherstellen",false){
            AlertDialog.Builder(this).setTitle("Sicherung importieren?")
                .setMessage("Alle vorhandenen Daten werden durch die Sicherung ersetzt. Bitte exportiere vorher eine Sicherung.")
                .setNegativeButton("Abbrechen",null)
                .setPositiveButton("Datei auswählen"){_,_->
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE);type="application/json"
                    },502)
                }.show()
        })
        val help=card(page)
        add(help,text("Bedienung",17f,true));gap(help,5)
        add(help,text("Training: zwei Sätze sind voreingestellt. Über „+ Satz“ fügst du weitere hinzu. RIR ist optional. Einheiten werden erst beim Abschluss gespeichert.",13f))
        gap(help,5)
        add(help,text("Fortschrittstabelle: Das neueste Training steht immer rechts. Jede Zelle zeigt Gewicht / Wiederholungen oder Sekunden mit „s“.",13f))
    }

    private fun saveFile(format:String,mime:String,name:String) {
        exportType=format
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type=mime
            putExtra(Intent.EXTRA_TITLE,name)
        },501)
    }
    @Deprecated("Used for Android Storage Access Framework compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK || data?.data==null)return
        try {
            val uri=data.data!!
            if(requestCode==501) {
                val bytes=when(exportType) {
                    "xlsx"->DataExport.xlsx(store)
                    "csv"->DataExport.csv(store)
                    else->store.backupJson().toByteArray(Charsets.UTF_8)
                }
                contentResolver.openOutputStream(uri)?.use {it.write(bytes)}
                info("Datei gespeichert.")
            } else if(requestCode==502) {
                val backup=contentResolver.openInputStream(uri)?.bufferedReader()?.use {it.readText()}
                    ?: error("Datei nicht lesbar")
                store.restoreJson(backup);draft=null;tab="Start";render()
                info("Datensicherung wiederhergestellt.")
            }
        } catch(e:Exception) {
            AlertDialog.Builder(this).setTitle("Dateifehler")
                .setMessage(e.message ?: "Der Vorgang konnte nicht abgeschlossen werden.")
                .setPositiveButton("OK",null).show()
        }
    }

    @Deprecated("Android back callback")
    override fun onBackPressed() {
        if(draft!=null && tab=="Training") {
            AlertDialog.Builder(this).setTitle("Training läuft")
                .setMessage("Zur Startseite wechseln? Die aktuellen Eingaben bleiben erhalten.")
                .setNegativeButton("Bleiben",null)
                .setPositiveButton("Startseite"){_,_->tab="Start";render()}.show()
        } else {tab="Start";render()}
    }

    inner class TrendChartView(private val points:List<Pair<Long,Double>>,private val unit:String):View(this) {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            if(points.isEmpty())return
            val w=width.toFloat();val h=height.toFloat()
            val l=dp(52).toFloat();val r=w-dp(15);val top=dp(20).toFloat();val bottom=h-dp(39)
            val values=points.map {it.second}
            var minY=values.minOrNull() ?: 0.0
            var maxY=values.maxOrNull() ?: 1.0
            val spread=max(1.0,maxY-minY)
            minY-=spread*0.15;maxY+=spread*0.15
            val minX=points.minOf {it.first};val maxX=points.maxOf {it.first}
            fun x(t:Long):Float=if(maxX==minX)(l+r)/2f else
                l+(r-l)*(t-minX).toFloat()/(maxX-minX).toFloat()
            fun y(v:Double):Float=bottom-((v-minY)/(maxY-minY)).toFloat()*(bottom-top)
            p.style=Paint.Style.STROKE;p.color=Color.rgb(208,224,215);p.strokeWidth=dp(1).toFloat()
            for(i in 0..4) {
                val yy=top+(bottom-top)*i/4f
                canvas.drawLine(l,yy,r,yy,p)
            }
            p.style=Paint.Style.FILL;p.color=muted;p.textSize=dp(11).toFloat()
            canvas.drawText(String.format(Locale.GERMANY,"%.1f",maxY)+" $unit",dp(2).toFloat(),top+dp(5),p)
            canvas.drawText(String.format(Locale.GERMANY,"%.1f",minY),dp(2).toFloat(),bottom,p)
            p.color=accent;p.style=Paint.Style.STROKE;p.strokeWidth=dp(3).toFloat()
            val path=Path()
            points.sortedBy {it.first}.forEachIndexed {i,pair->
                if(i==0)path.moveTo(x(pair.first),y(pair.second)) else path.lineTo(x(pair.first),y(pair.second))
            }
            canvas.drawPath(path,p)
            p.style=Paint.Style.FILL
            for(pair in points)canvas.drawCircle(x(pair.first),y(pair.second),dp(4).toFloat(),p)
            p.color=muted;p.textSize=dp(10).toFloat()
            val fmt=SimpleDateFormat("dd.MM.yy",Locale.GERMANY)
            canvas.drawText(fmt.format(Date(minX)),l,bottom+dp(23),p)
            val end=fmt.format(Date(maxX))
            canvas.drawText(end,r-p.measureText(end),bottom+dp(23),p)
        }
    }
}
