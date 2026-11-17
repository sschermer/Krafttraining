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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private lateinit var page: LinearLayout
    private var tab="Start"
    private var plan="A"
    private var activePlan="A"
    private var analysis="Tabelle"
    private var draft: MutableList<ExerciseDraft>?=null
    private var workoutCardio=false
    private var workoutCardioType=""
    private var workoutCardioMinutes=0
    private var exportType="xlsx"
    private var viewingPhaseId:Long?=null
    private var activePhaseId:Long=1L
    private var workoutTimestamp:Long=System.currentTimeMillis()
    private var expandedCategory:String?=null
    private var catalogExpandedCategory:String?=null
    private var weightRangeDays:Int=90
    private var activeScroll: ScrollView?=null
    private var renderedTab=""

    private val dark=Color.rgb(24,58,51)
    private val accent=Color.rgb(25,113,100)
    private val light=Color.rgb(245,248,246)
    private val ink=Color.rgb(37,47,44)
    private val muted=Color.rgb(105,120,114)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        store=Store(this)
        viewingPhaseId=store.activePhase()?.id ?: store.phases().firstOrNull()?.id
        restoreDraft()
        render()
    }
    private fun workPrefs()=getSharedPreferences("unfinished_workout", MODE_PRIVATE)

    // The pending workout is separate from the historical database.
    private fun saveDraft() {
        val active=draft ?: return
        val root=JSONObject()
            .put("plan",activePlan)
            .put("phaseId",activePhaseId)
            .put("trainingDate",workoutTimestamp)
            .put("cardio",workoutCardio)
            .put("cardioType",workoutCardioType)
            .put("cardioMinutes",workoutCardioMinutes)
        val entries=JSONArray()
        for(entry in active) {
            val rows=JSONArray()
            for(set in entry.sets) {
                rows.put(JSONObject()
                    .put("weight",set.weight).put("reps",set.reps)
                    .put("seconds",set.seconds).put("rir",set.rir)
                    .put("completed",set.completed))
            }
            entries.put(JSONObject().put("exerciseId",entry.exercise.id).put("sets",rows))
        }
        root.put("entries",entries)
        workPrefs().edit().putString("active",root.toString()).apply()
    }

    private fun restoreDraft() {
        val stored=workPrefs().getString("active",null) ?: return
        try {
            val root=JSONObject(stored)
            val entries=root.getJSONArray("entries")
            val restored=mutableListOf<ExerciseDraft>()
            for(i in 0 until entries.length()) {
                val item=entries.getJSONObject(i)
                val exercise=store.exercise(item.getLong("exerciseId")) ?: continue
                val rows=item.getJSONArray("sets")
                val sets=mutableListOf<SetDraft>()
                for(j in 0 until rows.length()) {
                    val row=rows.getJSONObject(j)
                    sets.add(SetDraft(
                        row.optString("weight"),row.optString("reps"),
                        row.optString("seconds"),row.optString("rir"),
                        row.optBoolean("completed",false)
                    ))
                }
                if(sets.isNotEmpty()) restored.add(ExerciseDraft(exercise,sets))
            }
            if(restored.isNotEmpty()) {
                activePlan=root.optString("plan","A").takeIf {it in listOf("A","B")} ?: "A"
                activePhaseId=root.optLong("phaseId",1L)
                workoutTimestamp=root.optLong("trainingDate",System.currentTimeMillis())
                workoutCardio=root.optBoolean("cardio",false)
                workoutCardioType=root.optString("cardioType","")
                workoutCardioMinutes=root.optInt("cardioMinutes",0)
                draft=restored
            } else {
                workPrefs().edit().remove("active").apply()
            }
        } catch(e:Exception) {
            // Keep a malformed draft for diagnosis instead of overwriting it.
            android.util.Log.e("Trainingsapp","Cannot restore unfinished workout",e)
        }
    }
    private fun clearDraft() {
        draft=null
        workPrefs().edit().remove("active").commit()
    }
    override fun onPause() {
        saveDraft()
        super.onPause()
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
            isSingleLine=true
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
        val previousScroll=if(renderedTab==tab) activeScroll?.scrollY ?: 0 else 0
        renderedTab=tab
        root=column().apply {setBackgroundColor(light)}
        setContentView(root)
        val header=column().apply {
            setBackgroundColor(dark);setPadding(dp(18),dp(14),dp(18),dp(13))
        }
        add(header,text("TRAININGSAPP",21f,true,Color.WHITE))
        add(header,text(if(draft!=null)"Training $activePlan läuft" else "Dein Training. Deine Entwicklung.",12f,false,Color.rgb(195,224,212)))
        add(root,header)
        val scroll=ScrollView(this).apply {isFillViewport=true;clipToPadding=false}
        activeScroll=scroll
        page=column().apply {setPadding(dp(14),dp(16),dp(14),dp(20))}
        scroll.addView(page)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        when(tab) {
            "Start"->home()
            "Training"->if(draft==null)plans() else workout()
            "Übungen"->catalog()
            "Verlauf"->results()
            "Gewicht"->weightScreen()
            else->dataPage()
        }
        val nav=row().apply {
            setPadding(dp(3),dp(5),dp(3),dp(5));setBackgroundColor(Color.WHITE)
        }
        val tabs=listOf("Start","Training","Übungen","Verlauf","Gewicht","Daten")
        val icons=listOf("⌂","A/B","≡","↗","kg","⇩")
        tabs.forEachIndexed {i,name ->
            val b=column().apply {gravity=Gravity.CENTER}
            add(b,text(icons[i],18f,true,if(tab==name)accent else muted).apply{gravity=Gravity.CENTER})
            add(b,text(name,10f,tab==name,if(tab==name)accent else muted).apply{gravity=Gravity.CENTER})
            b.setOnClickListener {tab=name;render()}
            nav.addView(b,LinearLayout.LayoutParams(0,dp(55),1f))
        }
        add(root,nav)
        if(previousScroll>0) scroll.post {scroll.scrollTo(0,previousScroll)}
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
                plan=name;expandedCategory=null;render()
            }
            r.addView(b,LinearLayout.LayoutParams(0,dp(45),1f))
            if(name=="A")add(r,View(this),7,1)
        }
        add(parent,r);gap(parent,14)
    }
    private fun phaseHeader() {
        val active=store.activePhase()
        val c=card(page)
        add(c,text("Trainingsphasen",18f,true));gap(c,5)
        if(active!=null) {
            add(c,text(active.name,16f,true));gap(c,3)
            add(c,text("Aktuelle Phase · seit "+date(active.startedAt),12f,false,muted));gap(c,10)
            add(c,button("Neue Trainingsphase",false){createPhaseDialog()})
            gap(c,7)
            add(c,button("Diese Trainingsphase abschließen",false){finishPhaseDialog(active)})
        } else {
            add(c,text("Derzeit keine aktive Trainingsphase.",14f));gap(c,5)
            add(c,text("Alte Pläne und Ergebnisse bleiben erhalten.",12f,false,muted));gap(c,9)
            add(c,button("Neue Trainingsphase anlegen"){createPhaseDialog()})
        }
    }

    private fun finishPhaseDialog(current:TrainingPhase) {
        if(draft!=null) {
            info("Bitte zuerst das begonnene Training abschließen oder verwerfen.")
            return
        }
        AlertDialog.Builder(this).setTitle("Trainingsphase abschließen?")
            .setMessage("Die Pläne A und B aus „"+current.name+
                "“ werden archiviert. Alle Trainingsdaten bleiben abrufbar. " +
                "Danach kannst du über „Training erstellen“ eine neue Phase anlegen.")
            .setNegativeButton("Abbrechen",null)
            .setPositiveButton("Abschließen") {_,_->
                try {
                    store.finishPhase(current.id)
                    viewingPhaseId=current.id
                    expandedCategory=null
                    render()
                    info("Trainingsphase archiviert.")
                } catch(e:Exception) {info(e.message ?: "Abschluss fehlgeschlagen.")}
            }.show()
    }

    private fun createPhaseDialog() {
        if(draft!=null) {
            info("Bitte zuerst das begonnene Training abschließen oder verwerfen.")
            return
        }
        val current=store.activePhase()
        val previous=store.phases().firstOrNull()
        val box=column().apply {setPadding(dp(20),dp(6),dp(20),0)}
        add(box,text("Name der neuen Trainingsphase",13f,true))
        val name=edit("Trainingsphase "+(store.phases().size+1),"Name")
        add(box,name);gap(box,13)
        val choices=listOf("Leeren A/B-Plan erstellen","A/B-Pläne der bisherigen Phase kopieren")
        val chooser=RadioGroup(this).apply {orientation=RadioGroup.VERTICAL}
        choices.forEachIndexed {index,label ->
            val radio=RadioButton(this).apply {
                id=View.generateViewId(); text=label;textSize=14f
                tag=index
                if(index==0) isChecked=true
            }
            add(chooser,radio)
        }
        if(previous==null) chooser.getChildAt(1).visibility=View.GONE
        add(box,chooser);gap(box,9)
        if(current!=null) {
            add(box,text("Die aktuell laufende Phase wird beim Erstellen automatisch abgeschlossen. Ihre Ergebnisse bleiben unverändert erhalten.",12f,false,muted))
        }
        val dialog=AlertDialog.Builder(this).setTitle("Neue Trainingsphase")
            .setView(box).setNegativeButton("Abbrechen",null)
            .setPositiveButton("Erstellen",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected=chooser.findViewById<RadioButton>(chooser.checkedRadioButtonId)
                val copy=selected?.tag==1 && previous!=null
                try {
                    val phase=store.createPhase(name.text.toString(),if(copy)previous?.id else null)
                    viewingPhaseId=phase.id
                    expandedCategory=null
                    plan="A"
                    tab="Training"
                    dialog.dismiss()
                    render()
                    info("Neue Trainingsphase angelegt.")
                } catch(e:Exception) {info(e.message ?: "Erstellen fehlgeschlagen.")}
            }
        }
        dialog.show()
    }

    private fun home() {
        title("Übersicht","Aktive Trainingsphase und Trainingsverlauf.")
        phaseHeader()
        val current=store.activePhase()
        if(current!=null) {
            planSelector(page)
            val c=card(page)
            add(c,text("Nächstes Training: "+current.name+" / "+plan,18f,true));gap(c,7)
            store.planEntries(plan,current.id).forEach { (exercise,_) ->
                add(c,text("• "+exercise.name,14f))
            }
            gap(c,12)
            add(c,button("Training $plan beginnen") {askCardio()})
        }
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
        add(w,button("Gewicht erfassen / Verlauf",false){tab="Gewicht";render()})
        if(draft!=null) {
            val running=card(page)
            add(running,text("Training $activePlan noch nicht abgeschlossen",15f,true))
            add(running,button("Weitertrainieren"){tab="Training";render()})
        }
        gap(page,9)
        add(page,button("Trainingsplan bearbeiten",false){tab="Training";render()})
        gap(page,6)
        add(page,button("Frühere Trainingsphasen ansehen",false) {
            tab="Verlauf";analysis="Tabelle";render()
        })
    }

    private fun chooseTrainingDate(initial:Long,accept:(Long)->Unit) {
        val cal=Calendar.getInstance().apply {timeInMillis=initial}
        DatePickerDialog(this,{_,year,month,day->
            cal.set(Calendar.YEAR,year)
            cal.set(Calendar.MONTH,month)
            cal.set(Calendar.DAY_OF_MONTH,day)
            TimePickerDialog(this,{_,hour,minute->
                cal.set(Calendar.HOUR_OF_DAY,hour)
                cal.set(Calendar.MINUTE,minute)
                cal.set(Calendar.SECOND,0)
                cal.set(Calendar.MILLISECOND,0)
                accept(cal.timeInMillis)
            },cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true).show()
        },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun askCardio() {
        if(draft!=null) {
            tab="Training"
            render()
            info("Bitte zuerst das laufende Training abschließen oder verwerfen.")
            return
        }
        val phase=store.activePhase()
        if(phase==null) {info("Erstelle zuerst eine neue Trainingsphase.");return}
        if(store.planEntries(plan,phase.id).isEmpty()) {info("Füge dem Trainingsplan zuerst Übungen hinzu.");return}
        val box=column().apply {setPadding(dp(20),dp(6),dp(20),0)}
        var chosenDate=System.currentTimeMillis()
        val dateButton=button("Trainingsdatum: "+date(chosenDate),false){}
        dateButton.setOnClickListener {
            chooseTrainingDate(chosenDate) {
                chosenDate=it
                dateButton.text="Trainingsdatum: "+date(it)
            }
        }
        add(box,dateButton);gap(box,12)
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
                activePlan=plan
                activePhaseId=phase.id
                workoutTimestamp=chosenDate
                draft=store.newDraft(plan,phase.id)
                saveDraft()
                tab="Training";render()
            }.show()
    }

    private fun workout() {
        title("Training $activePlan","Vorwerte sind Vorschläge: Erledigte Sätze bitte mit ✓ bestätigen.")
        val dateCard=card(page)
        add(dateCard,text("Trainingsdatum und Uhrzeit",14f,true));gap(dateCard,7)
        add(dateCard,button(date(workoutTimestamp)+"  ·  Ändern",false) {
            chooseTrainingDate(workoutTimestamp) {
                workoutTimestamp=it
                saveDraft()
                render()
            }
        })
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
            add(headings,text("Satz",11f,true,muted),30)
            if(item.exercise.kind=="time") {
                add(headings,text("Sek.",11f,true,muted),80)
                add(headings,text("RIR",11f,true,muted),64)
            } else {
                add(headings,text("kg",11f,true,muted),73)
                add(headings,text("Wdh.",11f,true,muted),66)
                add(headings,text("RIR",11f,true,muted),64)
            }
            add(headings,text("OK",11f,true,muted),35)
            add(c,headings);gap(c,4)
            item.sets.forEachIndexed {i,s ->
                val r=row()
                add(r,text((i+1).toString()+".",14f,true),30)
                if(item.exercise.kind=="time") {
                    val sec=edit(s.seconds,"Sek.",true)
                    add(r,sec,75);watch(sec){s.seconds=it;saveDraft()}
                } else {
                    val kg=edit(s.weight,"kg",true)
                    add(r,kg,68);watch(kg){s.weight=it;saveDraft()}
                    add(r,View(this),5,1)
                    val reps=edit(s.reps,"Wdh.",true)
                    add(r,reps,61);watch(reps){s.reps=it;saveDraft()}
                }
                add(r,View(this),5,1)
                val rir=edit(s.rir,"–",true)
                add(r,rir,55);watch(rir){s.rir=it;saveDraft()}
                val complete=CheckBox(this).apply {
                    text="✓"
                    textSize=13f
                    isChecked=s.completed
                    buttonTintList=android.content.res.ColorStateList.valueOf(accent)
                    setPadding(0,0,0,0)
                    setOnCheckedChangeListener {button,checked ->
                        val valid=if(item.exercise.kind=="time")
                            (s.seconds.trim().toIntOrNull() ?: 0)>0
                        else (s.reps.trim().toIntOrNull() ?: 0)>0
                        if(checked && !valid) {
                            info("Zuerst die Wiederholungen bzw. Sekunden eintragen.")
                            button.isChecked=false
                        } else {
                            s.completed=checked
                            saveDraft()
                        }
                    }
                }
                add(r,complete,43)
                add(c,r);gap(c,7)
            }
            val controls=row()
            controls.addView(button("+ Satz",false) {
                item.sets.add(SetDraft(weight=item.sets.lastOrNull()?.weight ?: ""))
                saveDraft()
                render()
            },LinearLayout.LayoutParams(0,dp(44),1f))
            if(item.sets.size>2) {
                add(controls,View(this),8,1)
                controls.addView(button("− Letzter Satz",false) {
                    item.sets.removeAt(item.sets.lastIndex);saveDraft();render()
                },LinearLayout.LayoutParams(0,dp(44),1f))
            }
            add(c,controls)
        }
        add(page,button("Training abschließen und speichern") {
            val confirmed=list.sumOf {item->item.sets.count {it.completed} }
            if(confirmed==0) {
                info("Bitte mindestens einen absolvierten Satz mit ✓ bestätigen.")
                return@button
            }
            val invalid=list.any {item->item.sets.any {set->
                set.completed && (if(item.exercise.kind=="time")
                    (set.seconds.trim().toIntOrNull() ?: 0)<=0
                else (set.reps.trim().toIntOrNull() ?: 0)<=0)
            }}
            if(invalid) {info("Bitte markierte Sätze prüfen.");return@button}
            AlertDialog.Builder(this).setTitle("Training abschließen?")
                .setMessage("$confirmed bestätigte Sätze werden dauerhaft gespeichert. Unbestätigte Vorschläge werden nicht übernommen.")
                .setNegativeButton("Weitertrainieren",null).setPositiveButton("Speichern"){_,_->
                    try {
                        store.saveSession(activePlan,workoutCardio,workoutCardioType,workoutCardioMinutes,list,activePhaseId,workoutTimestamp)
                        clearDraft()
                        tab="Verlauf";analysis="Tabelle";render()
                        info("Training gespeichert.")
                    } catch(e:Exception) {
                        AlertDialog.Builder(this).setTitle("Speichern nicht möglich")
                            .setMessage(e.message ?: "Unbekannter Fehler")
                            .setPositiveButton("OK",null).show()
                    }
                }.show()
        });gap(page,10)
        add(page,button("Training abbrechen",false) {
            AlertDialog.Builder(this).setTitle("Training verwerfen?")
                .setMessage("Nicht gespeicherte Eingaben gehen verloren.")
                .setNegativeButton("Zurück",null).setPositiveButton("Verwerfen"){_,_->
                    clearDraft();tab="Start";render()
                }.show()
        })
    }

    private fun plans() {
        val phase=store.activePhase()
        title("Trainingsplan zusammenstellen",phase?.name ?: "Keine aktive Trainingsphase")
        if(phase==null) {
            add(page,text("Erstelle eine neue Trainingsphase, um Übungen für A und B festzulegen.",14f))
            gap(page,12)
            add(page,button("Neue Trainingsphase anlegen"){createPhaseDialog()})
            return
        }
        planSelector(page)
        val selected=store.planEntries(plan,phase.id).map {it.first.id}.toSet()
        val all=store.exercises()
        add(page,text("Übungskategorien",17f,true));gap(page,3)
        add(page,text("Eine Kategorie antippen, um Übungen auszuwählen. Die Zahl in Klammern zeigt bereits ausgewählte Übungen.",12f,false,muted))
        gap(page,12)
        val categories=(Store.CATEGORIES+all.map{it.category}).distinct()
        for(category in categories) {
            val exercises=all.filter {it.category==category}
            if(exercises.isEmpty())continue
            val chosen=exercises.count {it.id in selected}
            val opened=expandedCategory==category
            val box=card(page)
            val headline=category+(if(chosen>0)" ($chosen)" else "")+(if(opened)"  ▴" else "  ▾")
            add(box,button(headline,false) {
                expandedCategory=if(opened)null else category
                render()
            })
            if(opened) {
                gap(box,10)
                for(exercise in exercises) {
                    val check=CheckBox(this).apply {
                        text=exercise.name
                        textSize=14f
                        isChecked=exercise.id in selected
                        setOnCheckedChangeListener {_,checked->
                            try {
                                if(checked) store.addToPlan(plan,exercise.id,phase.id)
                                else store.removeFromPlan(plan,exercise.id,phase.id)
                                render()
                            } catch(e:Exception) {info(e.message ?: "Auswahl fehlgeschlagen.")}
                        }
                    }
                    add(box,check)
                }
            }
        }
        val stats=card(page)
        add(stats,text("Training $plan: ${selected.size} Übungen gewählt",15f,true))
        gap(stats,6)
        add(stats,text("Auch mehrere Übungen aus derselben Kategorie sind möglich.",12f,false,muted))
        gap(stats,10)
        add(stats,button("Training $plan beginnen"){askCardio()})
        gap(page,7)
        add(page,button("Zur Startseite",false){tab="Start";render()})
        gap(page,8)
        add(page,button("Trainingsphase abschließen",false){finishPhaseDialog(phase)})
    }

    private fun catalog() {
        title("Übungskatalog","Eine Kategorie antippen: Übungen werden aufgeklappt.")
        add(page,button("+ Eigene Übung"){exerciseDialog(null)})
        gap(page,13)
        add(page,text("Im Bereich „Training“ wählst du die Übungen für A oder B aus.",12f,false,muted))
        gap(page,12)
        val all=store.exercises()
        val chosen=store.activePhase()?.let {store.planEntries(plan,it.id).map {pair->pair.first.id}.toSet()} ?: emptySet()
        for(category in (Store.CATEGORIES+all.map {it.category}).distinct()) {
            val exercises=all.filter {it.category==category}
            if(exercises.isEmpty())continue
            val count=exercises.count {it.id in chosen}
            val opened=catalogExpandedCategory==category
            val box=card(page)
            val header=row()
            val heading=column()
            add(heading,text(category+(if(count>0)" ($count)" else ""),15f,true))
            add(heading,text(if(opened)"Zum Schließen antippen" else "Zum Anzeigen antippen",
                11f,false,muted))
            header.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
            add(header,text(if(opened)"⌃" else "⌄",21f,true,accent),24)
            header.setPadding(dp(2),dp(4),dp(2),dp(4))
            header.setOnClickListener {
                catalogExpandedCategory=if(opened)null else category
                render()
            }
            add(box,header)
            if(opened) {
                gap(box,9)
                for(ex in exercises) {
                    val inner=column()
                    add(inner,text((if(ex.id in chosen)"✓ " else "")+ex.name,14f,true))
                    add(inner,text((if(ex.kind=="time")"Sekunden" else "Wiederholungen")+
                        " · "+ex.equipment,11f,false,muted))
                    gap(inner,5)
                    add(inner,button("Übung bearbeiten",false){exerciseDialog(ex)})
                    add(box,inner)
                    gap(box,10)
                }
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
        val filter=store.phase(viewingPhaseId ?: -1L)
        add(page,button("Trainingsphase: "+(filter?.name ?: "Alle Phasen")+"  ▾",false) {
            val options=listOf("Alle Phasen – gesamte Entwicklung")+
                store.phases().map {it.name+(if(it.active)" · aktiv" else " · abgeschlossen")}
            AlertDialog.Builder(this).setTitle("Trainingsphase auswählen")
                .setItems(options.toTypedArray()) {_,index->
                    viewingPhaseId=if(index==0)null else store.phases()[index-1].id
                    render()
                }.show()
        })
        gap(page,11)
        val buttons=row()
        listOf("Tabelle","Kurven").forEach {name ->
            buttons.addView(button(name,analysis==name){analysis=name;render()},
                LinearLayout.LayoutParams(0,dp(42),1f))
        }
        add(page,buttons);gap(page,14)
        when(analysis) {
            "Tabelle"->progressTable()
            else->exerciseChart()
        }
    }
    private fun progressTable() {
        planSelector(page)
        add(page,text("Älteste Einheiten links, neueste rechts.",12f,false,muted));gap(page,9)
        val sessions=store.sessions(plan,viewingPhaseId)
        val exercises=store.progressExercises(plan,viewingPhaseId)
        if(sessions.isEmpty()) {add(page,text("Noch keine gespeicherten Trainingseinheiten.",14f));return}
        val whole=row().apply {gravity=Gravity.TOP}
        val fixed=column()
        val fixedHead=cell("Übung",142,true)
        add(fixed,fixedHead)
        for(ex in exercises) add(fixed,cell(ex.name,142,true))
        add(whole,fixed,142)
        val scroll=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=true;isFillViewport=false
        }
        val right=column()
        val dates=row()
        for(s in sessions) add(dates,cell((if(viewingPhaseId==null)"P"+s.phaseId+" · " else "")+date(s.timestamp,true),116,true))
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
        gap(page,16)
        add(page,text("Gespeicherte Trainingseinheiten",17f,true))
        gap(page,8)
        for(training in sessions.asReversed()) {
            val box=card(page)
            val r=row()
            val left=column()
            add(left,text("Training "+training.plan+" · "+date(training.timestamp),14f,true))
            add(left,text(store.phase(training.phaseId)?.name ?: "Phase "+training.phaseId,12f,false,muted))
            r.addView(left,LinearLayout.LayoutParams(0,-2,1f))
            add(r,button("Bearbeiten",false){editSessionDialog(training)},105)
            add(box,r)
        }
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
            .setMessage(body.ifBlank{"Keine Sätze"})
            .setNegativeButton("Schließen",null)
            .setPositiveButton("Einheit bearbeiten"){_,_->editSessionDialog(s)}
            .show()
    }
    private fun editSessionDialog(original:TrainingSession) {
        val session=store.session(original.id) ?: run {
            info("Trainingseinheit nicht gefunden.")
            return
        }
        val rows=store.sets(session.id)
        val edits=rows.groupBy {it.exerciseId}.mapNotNull {entry->
            val ex=store.exercise(entry.key) ?: return@mapNotNull null
            ExerciseDraft(ex,entry.value.sortedBy {it.number}.map {set->
                SetDraft(
                    set.weight?.let {formatNumber(it)} ?: "",
                    set.reps?.toString() ?: "",
                    set.seconds?.toString() ?: "",
                    set.rir?.let {formatNumber(it)} ?: "",
                    true
                )
            }.toMutableList())
        }.toMutableList()
        if(edits.isEmpty()) {info("Für diese Einheit sind keine Sätze vorhanden.");return}
        var selectedDate=session.timestamp
        val scroll=ScrollView(this).apply {isFillViewport=false}
        val content=column().apply {setPadding(dp(15),dp(10),dp(15),dp(10))}
        scroll.addView(content)
        add(content,text("Tatsächliches Trainingsdatum",14f,true));gap(content,7)
        val dateButton=button(date(selectedDate)+" · Ändern",false){}
        dateButton.setOnClickListener {
            chooseTrainingDate(selectedDate) {value->
                selectedDate=value
                dateButton.text=date(value)+" · Ändern"
            }
        }
        add(content,dateButton);gap(content,13)
        val cardioCheck=CheckBox(this).apply {
            text="Vorher Kondition gemacht"
            isChecked=session.cardio
        }
        add(content,cardioCheck)
        val cardioType=edit(session.cardioType,"Art, z. B. Laufen")
        add(content,cardioType);gap(content,5)
        val cardioMinutes=edit(session.cardioMinutes.let{if(it>0)it.toString() else ""},"Minuten",true)
        add(content,cardioMinutes);gap(content,14)
        add(content,text("Sätze korrigieren",16f,true));gap(content,9)
        val holder=column()
        add(content,holder)
        fun refreshRows() {
            val oldScroll=scroll.scrollY
            holder.removeAllViews()
            for(item in edits) {
                val c=card(holder)
                add(c,text(item.exercise.name,15f,true));gap(c,7)
                item.sets.forEachIndexed {index,set->
                    val r=row()
                    add(r,text((index+1).toString()+".",13f,true),28)
                    if(item.exercise.kind=="time") {
                        val secs=edit(set.seconds,"Sek.",true)
                        add(r,secs,75);watch(secs){set.seconds=it}
                    } else {
                        val kg=edit(set.weight,"kg",true)
                        add(r,kg,64);watch(kg){set.weight=it}
                        add(r,View(this),4,1)
                        val reps=edit(set.reps,"Wdh.",true)
                        add(r,reps,60);watch(reps){set.reps=it}
                    }
                    add(r,View(this),4,1)
                    val rir=edit(set.rir,"RIR",true)
                    add(r,rir,55);watch(rir){set.rir=it}
                    val done=CheckBox(this).apply {
                        text="✓";textSize=12f
                        isChecked=set.completed
                        setOnCheckedChangeListener {_,checked->set.completed=checked}
                    }
                    add(r,done,46)
                    add(c,r);gap(c,6)
                }
                val actions=row()
                actions.addView(button("+ Satz",false) {
                    item.sets.add(SetDraft(weight=item.sets.lastOrNull()?.weight ?: ""))
                    refreshRows()
                },LinearLayout.LayoutParams(0,dp(42),1f))
                if(item.sets.size>1) {
                    add(actions,View(this),7,1)
                    actions.addView(button("− Letzter",false) {
                        item.sets.removeAt(item.sets.lastIndex)
                        refreshRows()
                    },LinearLayout.LayoutParams(0,dp(42),1f))
                }
                add(c,actions)
            }
            scroll.post {scroll.scrollTo(0,oldScroll)}
        }
        refreshRows()
        val dialog=AlertDialog.Builder(this)
            .setTitle("Training "+session.plan+" bearbeiten")
            .setView(scroll).setNegativeButton("Abbrechen",null)
            .setPositiveButton("Änderungen speichern",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    store.updateSession(session.id,selectedDate,cardioCheck.isChecked,
                        if(cardioCheck.isChecked)cardioType.text.toString().trim() else "",
                        if(cardioCheck.isChecked)cardioMinutes.text.toString().toIntOrNull() ?: 0 else 0,
                        edits)
                    dialog.dismiss()
                    render()
                    info("Gespeichertes Training aktualisiert.")
                } catch(e:Exception) {info(e.message ?: "Korrektur fehlgeschlagen.")}
            }
        }
        dialog.show()
    }

    private fun exerciseChart() {
        planSelector(page)
        val all=store.progressExercises(plan,viewingPhaseId)
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
            val points=store.sessions(plan,viewingPhaseId).mapNotNull {s->
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
    private fun weightScreen() {
        title("Körpergewicht","Gewicht mit Datum erfassen und die Entwicklung verfolgen.")
        weightPage()
    }

    private fun weightPage() {
        val all=store.bodyWeights()
        val summary=card(page)
        add(summary,text("Meine Gewichtsentwicklung",18f,true));gap(summary,6)
        add(summary,text(all.lastOrNull()?.let {formatNumber(it.kg)+" kg · "+date(it.timestamp)}
            ?: "Noch keine Messungen gespeichert.",15f))
        gap(summary,10)
        add(summary,button("+ Körpergewicht eintragen") {weightDialog()})
        gap(page,5)

        val ranges=listOf(7 to "7 T",30 to "30 T",90 to "90 T",365 to "1 Jahr",0 to "Alle")
        val selection=row()
        for((days,label) in ranges) {
            selection.addView(button(label,weightRangeDays==days) {
                weightRangeDays=days
                render()
            },LinearLayout.LayoutParams(0,dp(42),1f))
        }
        add(page,selection);gap(page,12)
        val cutoff=if(weightRangeDays==0)Long.MIN_VALUE
            else System.currentTimeMillis()-weightRangeDays.toLong()*24*60*60*1000
        val filtered=all.filter {it.timestamp>=cutoff}
        if(filtered.isEmpty()) {
            val empty=card(page)
            add(empty,text("Für diesen Zeitraum liegen noch keine Messungen vor.",14f))
            return
        }

        val first=filtered.first()
        val last=filtered.last()
        val delta=last.kg-first.kg
        val metrics=card(page)
        add(metrics,text(filtered.size.toString()+" Messungen im Zeitraum",14f,true))
        gap(metrics,4)
        add(metrics,text("Veränderung: "+(if(delta>0)"+" else "")+
            formatNumber(delta)+" kg",13f,false,muted))
        gap(page,3)
        add(page,TrendChartView(filtered.map {Pair(it.timestamp,it.kg)},"kg"),-1,250)
        gap(page,10)
        add(page,text("Messwerte · neueste zuerst",16f,true));gap(page,8)
        for(w in filtered.asReversed()) {
            val c=card(page)
            val r=row()
            val values=column()
            add(values,text(formatNumber(w.kg)+" kg",15f,true))
            add(values,text(date(w.timestamp),12f,false,muted))
            r.addView(values,LinearLayout.LayoutParams(0,-2,1f))
            add(r,button("Ändern",false){weightDialog(w)},86)
            add(r,View(this),6,1)
            add(r,button("Löschen",false){
                AlertDialog.Builder(this).setTitle("Messung löschen?")
                    .setMessage(date(w.timestamp)+" · "+formatNumber(w.kg)+" kg")
                    .setNegativeButton("Abbrechen",null)
                    .setPositiveButton("Löschen"){_,_->store.deleteBodyWeight(w.id);render()}
                    .show()
            },85)
            add(c,r)
        }
    }

    private fun weightDialog(existing:BodyWeight?=null) {
        val box=column().apply {setPadding(dp(22),dp(8),dp(22),0)}
        add(box,text("Körpergewicht in kg",13f,true))
        val input=edit(existing?.kg?.let {formatNumber(it)} ?: "","z. B. 82,4",true)
        add(box,input);gap(box,11)
        var selectedDate=existing?.timestamp ?: System.currentTimeMillis()
        val dateButton=button("Datum: "+date(selectedDate),false){}
        dateButton.setOnClickListener {
            chooseTrainingDate(selectedDate) {time->
                selectedDate=time
                dateButton.text="Datum: "+date(time)
            }
        }
        add(box,dateButton)
        gap(box,7)
        add(box,text("Datum und Uhrzeit sind frei wählbar, auch rückwirkend.",12f,false,muted))
        val dialog=AlertDialog.Builder(this)
            .setTitle(if(existing==null)"Gewicht eintragen" else "Gewicht korrigieren")
            .setView(box).setNegativeButton("Abbrechen",null)
            .setPositiveButton("Speichern",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val kg=input.text.toString().trim().replace(',','.').toDoubleOrNull()
                if(kg==null || kg<=0.0 || kg>500.0) {
                    info("Bitte ein gültiges Körpergewicht eingeben.")
                    return@setOnClickListener
                }
                try {
                    if(existing==null)store.addBodyWeight(kg,selectedDate)
                    else store.updateBodyWeight(existing.id,kg,selectedDate)
                    dialog.dismiss()
                    render()
                    info("Körpergewicht gespeichert.")
                } catch(e:Exception) {info(e.message ?: "Speichern fehlgeschlagen.")}
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
        add(help,text("Training: zwei Sätze sind voreingestellt. Über „+ Satz“ fügst du weitere hinzu. Vorwerte sind nur Vorschläge; erledigte Sätze mit ✓ bestätigen. RIR ist optional. Angefangene Trainings bleiben auch nach Schließen der App erhalten.",13f))
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
                store.restoreJson(backup);clearDraft();tab="Start";render()
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
