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
import de.trainingsapp.update.UpdateManager
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var updates: UpdateManager
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
    private var pendingExport:ExportRequest?=null
    private var importTask:BackupImportTask?=null
    private var activityResumed=false
    private var viewingPhaseId:Long?=null
    private var activePhaseId:Long=1L
    private var workoutTimestamp:Long=System.currentTimeMillis()
    private val expandedCategories=mutableSetOf<String>()
    private var catalogQuery=""
    private var showArchivedExercises=false
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
        updates=UpdateManager(this)
        pendingExport=ExportRequest.restore(savedInstanceState)
        @Suppress("DEPRECATION")
        val retained=lastNonConfigurationInstance as? BackupImportTask
        importTask=retained
        if(retained!=null) {
            showImportProgress()
            observeImport(retained)
        } else {
            viewingPhaseId=store.activePhase()?.id ?: store.phases().firstOrNull()?.id
            restoreDraft()
            render()
            if(savedInstanceState?.getBoolean("backup_import_running")==true)
                info("Bitte prüfe den Datenstand. Falls die Wiederherstellung unterbrochen wurde, starte sie erneut.")
        }
        // The updater checks from onResume, including warm starts where
        // Android reuses this Activity and onCreate is never called.
    }
    private fun workPrefs()=getSharedPreferences("unfinished_workout", MODE_PRIVATE)

    // The pending workout is separate from the historical database.
    private fun saveDraft() {
        val active=draft ?: return
        val root=JSONObject()
            .put("plan",activePlan)
            .put("dataGeneration",store.dataGeneration())
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
                    .put("completed",set.completed).put("note",set.note))
            }
            entries.put(JSONObject()
                .put("exerciseId",entry.exercise.id)
                .put("sets",rows)
                .put("replacedById",entry.replacedById ?: JSONObject.NULL)
                .put("replacementReason",entry.replacementReason)
                .put("replacementForId",entry.replacementForId ?: JSONObject.NULL)
                .put("setNumberOffset",entry.setNumberOffset))
        }
        root.put("entries",entries)
        workPrefs().edit().putString("active",root.toString()).apply()
    }

    private fun restoreDraft() {
        val stored=workPrefs().getString("active",null) ?: return
        try {
            val root=JSONObject(stored)
            if(root.optString("dataGeneration","0")!=store.dataGeneration()) {
                workPrefs().edit().remove("active").commit()
                return
            }
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
                        row.optBoolean("completed",false),
                        row.optString("note","")
                    ))
                }
                if(sets.isNotEmpty()) {
                    restored.add(ExerciseDraft(exercise,sets,
                        if(item.isNull("replacedById"))null else item.optLong("replacedById").takeIf {it>0L},
                        item.optString("replacementReason",""),
                        if(item.isNull("replacementForId"))null else item.optLong("replacementForId").takeIf {it>0L},
                        item.optInt("setNumberOffset",0)
                    ))
                }
            }
            if(restored.isNotEmpty()) {
                activePlan=root.optString("plan","A")
                activePhaseId=root.optLong("phaseId",1L)
                require(store.planIdentities(activePhaseId).any {it.key==activePlan}) {"Unbekannter Plan im Entwurf."}
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
    override fun onResume() {
        super.onResume()
        activityResumed=true
        if(importTask==null && ::updates.isInitialized)updates.onResume()
    }
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus && importTask==null && ::updates.isInitialized)updates.onWindowFocus()
    }
    override fun onPause() {
        activityResumed=false
        if(importTask==null)saveDraft()
        if(::updates.isInitialized)updates.onPause()
        super.onPause()
    }
    override fun onDestroy() {
        importTask?.detach()
        if(!isChangingConfigurations)importTask?.cancel()
        if(::updates.isInitialized)updates.close()
        if(::store.isInitialized)store.close()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState:Bundle) {
        pendingExport?.save(outState)
        outState.putBoolean("backup_import_running",importTask!=null)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Retains only an import task without Activity references")
    override fun onRetainNonConfigurationInstance():Any? = importTask
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
        if(short)"dd.MM.yy" else "dd.MM.yyyy HH:mm",Locale.GERMANY).format(Date(ts))
    private fun info(msg:String) {Toast.makeText(this,msg,Toast.LENGTH_LONG).show()}

    private fun render() {
        val previousScroll=if(renderedTab==tab) activeScroll?.scrollY ?: 0 else 0
        renderedTab=tab
        root=column().apply {setBackgroundColor(light)}
        setContentView(root)
        val header=column().apply {
            setBackgroundColor(dark);setPadding(dp(18),dp(14),dp(18),dp(13))
        }
        val top=row()
        val heading=when(tab) {
            "Training"->"Planung"
            "Verlauf","Gewicht"->"Auswertung"
            "Daten"->"Einstellungen"
            "Katalog"->"Übungskatalog"
            "Workout"->store.planName(activePlan,activePhaseId)
            else->"Training"
        }
        top.addView(text(heading,21f,true,Color.WHITE),LinearLayout.LayoutParams(0,-2,1f))
        if(tab!="Workout") {
            val back=tab=="Daten" || tab=="Katalog"
            val icon=text(if(back)"‹" else "⚙",25f,true,Color.WHITE).apply {
                gravity=Gravity.CENTER
                contentDescription=if(back)"Zurück" else "Einstellungen"
                setOnClickListener {tab=if(back && tab=="Katalog")"Training" else if(back)"Start" else "Daten";render()}
            }
            add(top,icon,44,44)
        }
        add(header,top)
        add(root,header)
        val scroll=ScrollView(this).apply {isFillViewport=true;clipToPadding=false}
        activeScroll=scroll
        page=column().apply {setPadding(dp(14),dp(16),dp(14),dp(20))}
        scroll.addView(page)
        val refresh=androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this).apply {
            setColorSchemeColors(accent)
            setProgressBackgroundColorSchemeColor(Color.WHITE)
            setOnChildScrollUpCallback { _, _ -> scroll.canScrollVertically(-1) }
            setOnRefreshListener {
                // Pulling down triggers a manual search even if automatic
                // update checks have been disabled.
                updates.checkNow { isRefreshing=false }
            }
        }
        refresh.addView(scroll)
        root.addView(refresh,LinearLayout.LayoutParams(-1,0,1f))
        when(tab) {
            "Start"->home()
            "Training"->plans()
            "Workout"->if(draft!=null)workout() else home()
            "Katalog"->catalog()
            "Verlauf"->results()
            "Gewicht"->results()
            else->dataPage()
        }
        val nav=row().apply {
            setPadding(dp(3),dp(5),dp(3),dp(5));setBackgroundColor(Color.WHITE)
        }
        val tabs=listOf("Start","Training","Verlauf")
        val labels=listOf("Training","Planung","Auswertung")
        val icons=listOf("⌂","☷","↗")
        tabs.forEachIndexed {i,name ->
            val b=column().apply {gravity=Gravity.CENTER}
            add(b,text(icons[i],18f,true,if(tab==name)accent else muted).apply{gravity=Gravity.CENTER})
            add(b,text(labels[i],if(name=="Training")9f else 10f,tab==name,
                if(tab==name)accent else muted).apply{gravity=Gravity.CENTER})
            b.setOnClickListener {tab=name;render()}
            nav.addView(b,LinearLayout.LayoutParams(0,dp(55),1f))
        }
        if(tab !in listOf("Workout","Daten","Katalog"))add(root,nav)
        if(previousScroll>0) scroll.post {scroll.scrollTo(0,previousScroll)}
    }
    private fun title(s:String,subtitle:String?=null) {
        add(page,text(s,22f,true));gap(page,4)
        if(subtitle!=null) {add(page,text(subtitle,13f,false,muted));gap(page,12)}
        else gap(page,12)
    }
    private fun planSelector(parent:LinearLayout) {
        val phase=viewingPhaseId ?: store.activePhase()?.id ?: store.phases().firstOrNull()?.id ?: return
        val choices=store.planIdentities(phase)
        if(choices.isEmpty())return
        if(choices.none {it.key==plan})plan=choices.first().key
        add(parent,button(store.planName(plan,phase)+"  ▾",false) {
            AlertDialog.Builder(this).setTitle("Trainingsplan auswählen")
                .setItems(choices.map {it.name}.toTypedArray()){_,i->plan=choices[i].key;render()}.show()
        })
        gap(parent,9)
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
            .setMessage("Die Trainingspläne aus „"+current.name+
                "“ werden archiviert. Alle Trainingsdaten bleiben abrufbar. " +
                "Danach kannst du über „Training erstellen“ eine neue Phase anlegen.")
            .setNegativeButton("Abbrechen",null)
            .setPositiveButton("Abschließen") {_,_->
                try {
                    store.finishPhase(current.id)
                    viewingPhaseId=current.id
                    expandedCategories.clear()
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
        val choices=listOf("Leere Trainingspläne erstellen","Pläne der bisherigen Phase kopieren")
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
                    expandedCategories.clear()
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
        if(draft!=null) {
            val resume=card(page)
            add(resume,text("Training läuft",13f,true,accent))
            add(resume,text(store.planName(activePlan,activePhaseId),20f,true))
            add(resume,text(date(workoutTimestamp),12f,false,muted))
            gap(resume,7)
            add(resume,button("Weitertrainieren"){tab="Workout";render()})
        }
        val recent=store.sessions().lastOrNull()
        val summary=card(page)
        add(summary,text("Letztes Training",13f,true,muted))
        if(recent==null) {
            add(summary,text("Noch kein Training gespeichert.",14f))
        } else {
            val line=row()
            val details=column()
            add(details,text(store.planName(recent.plan,recent.phaseId),20f,true))
            add(details,text(date(recent.timestamp)+" · "+store.sets(recent.id).size+" Sätze",12f,false,muted))
            line.addView(details,LinearLayout.LayoutParams(0,-2,1f))
            add(line,button("ⓘ",false){showWorkoutPreview(recent)}.apply {contentDescription="Letztes Training anzeigen"},46)
            add(summary,line)
            summary.setOnLongClickListener {showWorkoutPreview(recent);true}
            val result=store.performanceHistory(recent.plan,recent.phaseId)[recent.id]
            if(result!=null && result.result.index!=null)add(summary,text("Index "+indexLabel(result),15f,true,accent))
        }
        val current=store.activePhase()
        add(page,text("Trainingspläne",18f,true));gap(page,3)
        add(page,text(current?.name ?: "Keine aktive Phase",12f,false,muted));gap(page,9)
        if(current==null) {
            add(page,button("Neue Trainingsphase"){createPhaseDialog()})
        } else for(identity in store.planIdentities(current.id)) {
            val c=card(page)
            val exercises=store.planEntries(identity.key,current.id)
            val last=store.sessions(identity.key,current.id).lastOrNull()
            val line=row()
            val left=column()
            add(left,text(identity.name,17f,true))
            add(left,text(exercises.size.toString()+" Übungen · "+(last?.let {date(it.timestamp,true)} ?: "noch nie"),
                12f,false,muted))
            line.addView(left,LinearLayout.LayoutParams(0,-2,1f))
            fun preview() {
                AlertDialog.Builder(this).setTitle(identity.name)
                    .setMessage(exercises.joinToString("\n") {(ex,n)->ex.name+" · $n Sätze"}.ifBlank {"Keine Übungen im Plan."})
                    .setPositiveButton("Schließen",null).show()
            }
            add(line,button("ⓘ",false){preview()}.apply {contentDescription="Übungen anzeigen"},44)
            add(line,button("▶"){
                if(exercises.isEmpty()){tab="Training";render()}
                else {plan=identity.key;askCardio()}
            }.apply {contentDescription="Training starten"},48)
            add(c,line)
            left.setOnLongClickListener {preview();true}
        }
        val w=card(page)
        val weightRow=row()
        val weightText=column()
        add(weightText,text("Körpergewicht",13f,true,muted))
        add(weightText,text(store.bodyWeights().lastOrNull()?.let {formatNumber(it.kg)+" kg · "+date(it.timestamp,true)}
            ?: "Noch keine Messung",16f,true))
        weightRow.addView(weightText,LinearLayout.LayoutParams(0,-2,1f))
        add(weightRow,button("+",false){weightDialog()}.apply {contentDescription="Gewicht erfassen"},48)
        add(w,weightRow)
    }

    private fun indexLabel(p:Store.SessionPerformance):String {
        val index=p.result.index?.let {String.format(Locale.GERMANY,"%.1f",it)} ?: "–"
        return (if(p.estimated && p.result.index!=null)"≈ " else "")+index+" ("+p.result.completedSets+")"
    }

    private fun showWorkoutPreview(session:TrainingSession) {
        val lines=store.sets(session.id).groupBy {it.exerciseId}.map {(id,_)->
            val ex=store.exercise(id)
            if(ex==null)"Unbekannte Übung" else ex.name+": "+store.formatCell(session.id,ex)
        }
        AlertDialog.Builder(this).setTitle(store.planName(session.plan,session.phaseId)+" · "+date(session.timestamp,true))
            .setMessage(lines.joinToString("\n").ifBlank {"Keine Sätze"})
            .setNegativeButton("Schließen",null)
            .setPositiveButton("Bearbeiten"){_,_->editSessionDialog(session)}.show()
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
            tab="Workout"
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
                draft=store.newDraft(plan,phase.id,chosenDate)
                saveDraft()
                tab="Workout";render()
            }.show()
    }

    private fun noteDialog(set:SetDraft,onSaved:()->Unit) {
        val input=EditText(this).apply {
            setText(set.note)
            hint="Bemerkung (optional), z. B. Schmerzen, Technik, Tagesform"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines=3;maxLines=8
            setSingleLine(false)
            setPadding(dp(16),dp(10),dp(16),dp(10))
        }
        AlertDialog.Builder(this).setTitle("Satzbemerkung")
            .setView(input)
            .setNegativeButton("Abbrechen",null)
            .setNeutralButton("Löschen"){_,_->
                set.note=""
                onSaved()
            }
            .setPositiveButton("Übernehmen"){_,_->
                set.note=input.text.toString().trim()
                onSaved()
            }.show()
    }

    private fun replaceExerciseDialog(entry:ExerciseDraft) {
        val current=draft ?: return
        if(entry.replacedById!=null) return
        val existing=current.map {it.exercise.id}.toSet()
        val options=store.exercises().filter {it.id !in existing}
        if(options.isEmpty()) {
            info("Keine andere Übung im Katalog verfügbar.")
            return
        }
        val box=column().apply {setPadding(dp(16),dp(10),dp(16),dp(10))}
        val intro=text("Ersatz für "+entry.exercise.name+
            ". Die bisher bestätigten Sätze bleiben erhalten. Der Trainingsplan ändert sich nicht.",
            13f,false,muted)
        add(box,intro);gap(box,10)
        val holder=column()
        add(box,holder)
        var opened:String?=null
        val dialog=AlertDialog.Builder(this)
            .setTitle("Übung ersetzen").setView(ScrollView(this).apply {addView(box)})
            .setNegativeButton("Abbrechen",null).create()
        fun choose(exercise:Exercise) {
            val reason=edit("","Grund (optional, z. B. Schmerzen)")
            val container=column().apply {setPadding(dp(18),dp(8),dp(18),dp(8))}
            add(container,text("Neue Übung: "+exercise.name,15f,true));gap(container,12)
            add(container,reason)
            AlertDialog.Builder(this).setTitle("Austausch bestätigen")
                .setView(container)
                .setNegativeButton("Zurück",null)
                .setPositiveButton("Ersetzen"){_,_->
                    val completedUntil=entry.sets.indexOfLast {it.completed}+1
                    val pending=(entry.sets.size-completedUntil).coerceAtLeast(1)
                    val replacement=ExerciseDraft(exercise,
                        MutableList(pending){SetDraft()},
                        replacementForId=entry.exercise.id,
                        setNumberOffset=entry.setNumberOffset+completedUntil)
                    entry.replacedById=exercise.id
                    entry.replacementReason=reason.text.toString().trim()
                    val index=current.indexOf(entry)
                    current.add(index+1,replacement)
                    saveDraft()
                    dialog.dismiss()
                    render()
                }.show()
        }
        fun populate() {
            holder.removeAllViews()
            for(category in (Store.CATEGORIES+options.map {it.category}).distinct()) {
                val candidates=options.filter {it.category==category}
                if(candidates.isEmpty())continue
                val openedNow=opened==category
                add(holder,button(category+(if(openedNow)"  ▴" else "  ▾"),false){
                    opened=if(openedNow)null else category
                    populate()
                })
                if(openedNow) {
                    for(ex in candidates) {
                        gap(holder,3)
                        add(holder,button(ex.name,false){choose(ex)})
                    }
                }
                gap(holder,7)
            }
        }
        populate()
        dialog.show()
    }

    private fun workout() {
        title(store.planName(activePlan,activePhaseId),"Vorwerte sind Vorschläge: Erledigte Sätze bitte mit ✓ bestätigen.")
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
            if(item.replacedById!=null) {
                val other=store.exercise(item.replacedById!!)
                add(c,text("Ersetzt durch: "+(other?.name ?: "Unbekannte Übung"),13f,true,accent))
                if(item.replacementReason.isNotBlank()) {
                    add(c,text("Grund: "+item.replacementReason,12f,false,muted))
                }
                for((index,finished) in item.sets.withIndex()) if(finished.completed) {
                    val result=if(item.exercise.kind=="time")
                        (finished.seconds+" s") else (finished.weight.ifBlank{"–"}+
                        " kg / "+finished.reps+" Wdh.")
                    add(c,text("Erhaltener Satz "+(item.setNumberOffset+index+1)+": "+result+
                        (if(finished.note.isBlank())"" else " · "+finished.note),12f))
                }
                val next=list.firstOrNull {it.exercise.id==item.replacedById}
                if(next!=null && next.replacedById==null && next.sets.none {it.completed}) {
                    gap(c,8)
                    add(c,button("Austausch rückgängig",false) {
                        list.remove(next)
                        item.replacedById=null
                        item.replacementReason=""
                        saveDraft();render()
                    })
                }
                continue
            }
            if(item.replacementForId!=null) {
                val original=store.exercise(item.replacementForId!!)
                add(c,text("Ersatz für: "+(original?.name ?: "Unbekannte Übung"),12f,false,accent))
                gap(c,6)
            }
            add(c,text(if(item.exercise.kind=="time")"Haltedauer in Sekunden" else
                (if(item.exercise.perSide)"Gewicht in kg pro Seite" else "Gewicht in kg (optional)")+" · Wiederholungen",12f,false,muted))
            store.mostRecentNote(item.exercise.id)?.let {previousNote->
                gap(c,5)
                add(c,text("Frühere Bemerkung: "+previousNote,12f,false,muted))
            }
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
                add(r,text((item.setNumberOffset+i+1).toString()+".",14f,true),30)
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
                add(c,r)
                val noteButton=button(if(s.note.isBlank())"+ Notiz zu Satz "+(i+1)
                    else "✎ Notiz zu Satz "+(i+1)+" bearbeiten",false) {
                    noteDialog(s) {saveDraft();render()}
                }
                add(c,noteButton)
                if(s.note.isNotBlank()) add(c,text(s.note,12f,false,muted))
                gap(c,7)
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
            gap(c,8)
            add(c,button("Übung ersetzen",false){replaceExerciseDialog(item)})
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
                        val savedId=store.saveSession(activePlan,workoutCardio,workoutCardioType,
                            workoutCardioMinutes,list,activePhaseId,workoutTimestamp)
                        clearDraft()
                        tab="Verlauf";analysis="Tabelle";viewingPhaseId=activePhaseId;plan=activePlan;render()
                        AlertDialog.Builder(this)
                            .setTitle("Training gespeichert")
                            .setMessage("Wie möchtest du die vollständigen Daten dieses Trainings für ChatGPT bereitstellen?")
                            .setNegativeButton("Später",null)
                            .setNeutralButton("Text kopieren"){_,_->copyWorkoutText(savedId)}
                            .setPositiveButton("JSON herunterladen"){_,_->saveWorkoutJson(savedId)}
                            .show()
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
        phaseHeader()
        if(draft!=null) {
            add(page,text("Bitte das laufende Training abschließen oder verwerfen, bevor Pläne geändert werden.",14f,false,muted))
            add(page,button("Weitertrainieren"){tab="Workout";render()})
            return
        }
        val phase=store.activePhase()
        if(phase!=null) {
            add(page,text("Trainingspläne",18f,true));gap(page,8)
            for(identity in store.planIdentities(phase.id)) {
                val c=card(page)
                val line=row()
                val details=column()
                val entries=store.planEntries(identity.key,phase.id)
                add(details,text(identity.name,17f,true))
                add(details,text(entries.size.toString()+" Übungen · "+entries.sumOf {it.second}+" Sätze",12f,false,muted))
                line.addView(details,LinearLayout.LayoutParams(0,-2,1f))
                details.setOnClickListener {planEditor(phase.id,identity)}
                add(line,button("Bearbeiten",false){planEditor(phase.id,identity)},100)
                add(line,button("⋯",false){
                    AlertDialog.Builder(this).setTitle(identity.name)
                        .setItems(arrayOf("Umbenennen","Duplizieren")){_,i->
                            if(i==0)planNameDialog(phase.id,identity,false)
                            else planNameDialog(phase.id,identity,true)
                        }.show()
                },42)
                add(c,line)
            }
            add(page,button("+ Trainingsplan"){planNameDialog(phase.id,null,false)})
            gap(page,14)
        }
        add(page,button("Übungskatalog",false){expandedCategories.clear();catalogQuery="";tab="Katalog";render()})
    }

    private fun planNameDialog(phaseId:Long,identity:Store.PlanIdentity?,duplicate:Boolean) {
        val name=edit(if(duplicate)identity?.name.orEmpty()+" Kopie" else identity?.name.orEmpty(),"Name")
        val dialog=AlertDialog.Builder(this)
            .setTitle(if(duplicate)"Trainingsplan duplizieren" else if(identity==null)"Neuer Trainingsplan" else "Trainingsplan umbenennen")
            .setView(name).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val key=if(identity==null || duplicate)store.createPlan(phaseId,name.text.toString(),if(duplicate)identity?.key else null).key
                    else {store.renamePlan(phaseId,identity.key,name.text.toString());identity.key}
                plan=key
                dialog.dismiss();render()
                if(identity==null)store.planIdentities(phaseId).firstOrNull {it.key==key}?.let {planEditor(phaseId,it)}
            } catch(e:Exception) {name.error=e.message ?: "Speichern fehlgeschlagen"}
        }
    }

    private fun planEditor(phaseId:Long,identity:Store.PlanIdentity) {
        val initial=store.planEntries(identity.key,phaseId).map {it.first.id to it.second}
        val pending=initial.toMutableList()
        val layout=column().apply {setPadding(dp(12),dp(8),dp(12),dp(4))}
        val search=edit("","Übung, Kategorie oder Gerät suchen")
        add(layout,search)
        val scroll=ScrollView(this)
        val content=column()
        scroll.addView(content)
        layout.addView(scroll,LinearLayout.LayoutParams(-1,dp(390)))
        val dialog=AlertDialog.Builder(this).setTitle(identity.name)
            .setView(layout).setCancelable(false)
            .setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",null).create()
        fun populate() {
            content.removeAllViews()
            gap(content,8);add(content,text("Ausgewählt: "+pending.size+" Übungen",14f,true))
            val query=search.text.toString().trim()
            for((index,pair) in pending.withIndex()) {
                val ex=store.exercise(pair.first) ?: continue
                val c=card(content)
                val item=row()
                val details=column()
                add(details,text(ex.name,14f,true))
                add(details,text(ex.category+" · "+pair.second+" Sätze",11f,false,muted))
                item.addView(details,LinearLayout.LayoutParams(0,-2,1f))
                add(item,button("⋯",false) {
                    val actions=arrayOf("Satzanzahl ändern","Nach oben","Nach unten","Übung ersetzen","Entfernen")
                    AlertDialog.Builder(this).setTitle(ex.name).setItems(actions){_,i->
                        when(i) {
                            0 -> {
                                val field=edit(pair.second.toString(),"Sätze",true)
                                val countDialog=AlertDialog.Builder(this).setTitle("Satzanzahl").setView(field)
                                    .setNegativeButton("Abbrechen",null).setPositiveButton("Übernehmen",null).create()
                                countDialog.show()
                                countDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                    val count=field.text.toString().toIntOrNull()
                                    if(count==null || count !in 1..BackupValidation.MAX_TARGET_SETS)field.error="Ungültige Satzanzahl"
                                    else {pending[index]=pair.first to count;countDialog.dismiss();populate()}
                                }
                            }
                            1 -> if(index>0){java.util.Collections.swap(pending,index,index-1);populate()}
                            2 -> if(index<pending.lastIndex){java.util.Collections.swap(pending,index,index+1);populate()}
                            3 -> {
                                val options=store.exercises().filter {candidate->pending.none {it.first==candidate.id}}
                                val labels=options.map {it.name+" · "+it.category}
                                if(options.isEmpty())info("Keine weitere Übung verfügbar.")
                                else AlertDialog.Builder(this).setTitle("Ersatzübung auswählen")
                                    .setItems(labels.toTypedArray()){_,j->pending[index]=options[j].id to pair.second;populate()}.show()
                            }
                            4 -> {pending.removeAt(index);populate()}
                        }
                    }.show()
                },44)
                add(c,item)
                c.setOnLongClickListener {
                    c.startDragAndDrop(android.content.ClipData.newPlainText("plan-order",index.toString()),
                        View.DragShadowBuilder(c),index,0)
                    true
                }
                c.setOnDragListener {_,event->
                    when(event.action) {
                        android.view.DragEvent.ACTION_DRAG_STARTED -> event.localState is Int
                        android.view.DragEvent.ACTION_DROP -> {
                            val from=event.localState as? Int
                            if(from!=null && from in pending.indices && index in pending.indices && from!=index) {
                                val entry=pending.removeAt(from);pending.add(index,entry);populate()
                            }
                            true
                        }
                        else -> true
                    }
                }
            }
            add(content,text(if(query.isBlank())"Übungen hinzufügen – Suche nutzen" else "Suchergebnisse",14f,true))
            if(query.isNotBlank()) {
                val options=store.exercises().filter { ex ->
                    pending.none {it.first==ex.id} &&
                        listOf(ex.name,ex.category,ex.equipment).any {it.contains(query,true)}
                }
                if(options.isEmpty())add(content,text("Keine passenden Übungen.",13f,false,muted))
                for(ex in options) {
                    val line=row()
                    val details=column()
                    add(details,text(ex.name,14f,true));add(details,text(ex.category,11f,false,muted))
                    line.addView(details,LinearLayout.LayoutParams(0,-2,1f))
                    add(line,button("+",false){pending.add(ex.id to 2);populate()},44)
                    add(content,line);gap(content,4)
                }
            }
        }
        watch(search){populate()}
        populate()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {store.savePlanEntries(identity.key,phaseId,pending);dialog.dismiss();render()}
            catch(e:Exception){info(e.message ?: "Speichern fehlgeschlagen.")}
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if(pending==initial)dialog.dismiss()
            else AlertDialog.Builder(this).setTitle("Änderungen verwerfen?")
                .setMessage("Der bisher gespeicherte Plan bleibt unverändert.")
                .setNegativeButton("Weiter bearbeiten",null)
                .setPositiveButton("Verwerfen"){_,_->dialog.dismiss()}.show()
        }
    }

    private fun catalog() {
        if(draft!=null){add(page,text("Übungskatalog nach Abschluss des Trainings bearbeiten."));return}
        add(page,button("+ Eigene Übung"){exerciseDialog(null)})
        gap(page,8)
        val search=edit(catalogQuery,"Übung, Kategorie oder Gerät suchen")
        add(page,search);gap(page,8)
        add(page,button(if(showArchivedExercises)"Archivierte ausblenden" else "Archivierte anzeigen",false){
            showArchivedExercises=!showArchivedExercises;render()
        })
        gap(page,8)
        val content=column();add(page,content)
        fun populate() {
            content.removeAllViews()
            val q=search.text.toString().trim()
            val all=store.exercises(showArchivedExercises).filter {ex->
                q.isBlank() || listOf(ex.name,ex.category,ex.equipment).any {it.contains(q,true)}
            }
            for(category in (Store.CATEGORIES+all.map {it.category}).distinct()) {
                val options=all.filter {it.category==category}
                if(options.isEmpty())continue
                val c=card(content)
                val open=category in expandedCategories || q.isNotBlank()
                add(c,button(category+" ("+options.size+") "+if(open)"▴" else "▾",false){
                    if(!expandedCategories.add(category))expandedCategories.remove(category)
                    populate()
                })
                if(open)for(ex in options) {
                    val line=row()
                    val details=column()
                    add(details,text(ex.name+(if(ex.archived)" · archiviert" else ""),14f,true))
                    add(details,text(ex.equipment.ifBlank {"Ohne Geräte"},11f,false,muted))
                    line.addView(details,LinearLayout.LayoutParams(0,-2,1f))
                    add(line,button("Bearbeiten",false){exerciseDialog(ex)},96)
                    add(c,line);gap(c,4)
                }
            }
        }
        watch(search){catalogQuery=it;populate()}
        populate()
    }

    private fun exerciseDialog(old:Exercise?) {
        val box=column().apply {setPadding(dp(20),dp(5),dp(20),0)}
        add(box,text("Übungsname",13f,true))
        val name=edit(old?.name ?: "","Name").apply {tag="exercise_name"}
        add(box,name)
        gap(box,9);add(box,text("Kategorie",13f,true))
        // Include custom categories even if all their exercises are archived.
        val categories=(Store.CATEGORIES+store.exercises(includeArchived=true).map {it.category}).distinct()
        val categoryOptions=listOf("Kategorie auswählen …")+categories+"+ Neue Kategorie anlegen"
        val newCategoryIndex=categoryOptions.lastIndex
        val cat=Spinner(this,Spinner.MODE_DIALOG).apply {
            tag="exercise_category"
            prompt="Kategorie auswählen"
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_item,categoryOptions).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(old?.let {categories.indexOf(it.category)+1} ?: 0)
        }
        add(box,cat)
        val newCategory=edit("","Name der neuen Kategorie").apply {tag="exercise_new_category"}
        val newCategoryBox=column().apply {visibility=View.GONE}
        gap(newCategoryBox,7)
        add(newCategoryBox,text("Neue Kategorie",13f,true))
        add(newCategoryBox,newCategory)
        add(box,newCategoryBox)
        cat.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?) {newCategoryBox.visibility=View.GONE}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                newCategoryBox.visibility=if(position==newCategoryIndex)View.VISIBLE else View.GONE
            }
        }
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
        val archived=CheckBox(this).apply {
            text="Übung archivieren (aus der normalen Auswahl ausblenden)"
            isChecked=old?.archived ?: false
        }
        if(old!=null) {
            gap(box,7)
            add(box,archived)
            add(box,text("Archivieren löscht keine bisherigen Trainingsdaten oder gespeicherten Pläne.",12f,false,muted))
        }
        val scroll=ScrollView(this).apply {addView(box)}
        val dialog=AlertDialog.Builder(this).setTitle(if(old==null)"Neue Übung" else "Übung bearbeiten")
            .setView(scroll).setNegativeButton("Abbrechen",null)
            .setPositiveButton("Speichern",null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n=name.text.toString().trim()
            if(n.isBlank()) {name.error="Übungsname eingeben";return@setOnClickListener}
            val position=cat.selectedItemPosition
            val catName=when {
                position in 1..categories.size -> categories[position-1]
                position==newCategoryIndex -> {
                    val entered=newCategory.text.toString().trim()
                    if(entered.isBlank()) {
                        newCategory.error="Kategorienamen eingeben"
                        return@setOnClickListener
                    }
                    categories.firstOrNull {it.equals(entered,ignoreCase=true)} ?: entered
                }
                else -> {
                    info("Bitte eine Kategorie auswählen oder eine neue anlegen.")
                    return@setOnClickListener
                }
            }
            try {
                store.upsertExercise(Exercise(old?.id ?: 0L,n,catName,
                    if(kind.selectedItemPosition==1)"time" else "reps",
                    eq.text.toString().trim(),perSide.isChecked,archived.isChecked))
                dialog.dismiss();render()
            } catch(e:Exception) {info(e.message ?: "Übung konnte nicht gespeichert werden.")}
        }
    }

    private fun results() {
        val section=row()
        val groups=listOf("Fortschritt","Historie","Gewicht")
        val selected=when(analysis){"Historie"->"Historie";"Gewicht"->"Gewicht";else->"Fortschritt"}
        for(name in groups) {
            section.addView(button(name,name==selected){analysis=if(name=="Fortschritt")"Tabelle" else name;render()},
                LinearLayout.LayoutParams(0,dp(43),1f))
        }
        add(page,section);gap(page,10)
        if(analysis=="Gewicht"){weightPage();return}
        val phases=store.phases()
        val filter=store.phase(viewingPhaseId ?: -1L) ?: store.activePhase() ?: phases.firstOrNull()
        if(filter==null){add(page,text("Noch keine Trainingsphase."));return}
        viewingPhaseId=filter.id
        add(page,button("Trainingsphase: "+filter.name+"  ▾",false) {
            AlertDialog.Builder(this).setTitle("Trainingsphase auswählen")
                .setItems(phases.map {it.name}.toTypedArray()){_,index->
                    viewingPhaseId=phases[index].id;render()
                }.show()
        })
        gap(page,8)
        planSelector(page)
        if(analysis=="Historie"){history();return}
        val buttons=row()
        listOf("Tabelle","Kurven").forEach {name ->
            buttons.addView(button(name,analysis==name){analysis=name;render()},
                LinearLayout.LayoutParams(0,dp(42),1f))
        }
        add(page,buttons);gap(page,12)
        if(analysis=="Kurven")exerciseChart() else progressTable()
    }

    private fun history() {
        val phase=viewingPhaseId ?: return
        val values=store.performanceHistory(plan,phase)
        val sessions=store.sessions(plan,phase).asReversed()
        if(sessions.isEmpty())add(page,text("Noch keine gespeicherten Trainingseinheiten."))
        for(training in sessions) {
            val box=card(page)
            val line=row()
            val details=column()
            add(details,text(store.planName(training.plan,training.phaseId)+" · "+date(training.timestamp),15f,true))
            val result=values[training.id]
            add(details,text(if(result!=null)"Index "+indexLabel(result) else store.sets(training.id).size.toString()+" Sätze",12f,false,muted))
            line.addView(details,LinearLayout.LayoutParams(0,-2,1f))
            add(line,button("Öffnen",false){showWorkoutPreview(training)},80)
            add(box,line)
            add(box,button("JSON exportieren",false){saveWorkoutJson(training.id)})
            add(box,button("Text kopieren",false){copyWorkoutText(training.id)})
        }
    }

    private fun progressTable() {
        add(page,text("Älteste Einheiten links, neueste rechts.",12f,false,muted));gap(page,9)
        val sessions=store.sessions(plan,viewingPhaseId)
        val exercises=store.progressExercises(plan,viewingPhaseId)
        if(sessions.isEmpty()) {
            add(page,text("Noch keine gespeicherten Trainingseinheiten.",14f))
            return
        }

        // Both independent columns must use exactly matching heights.
        // The generic add() helper overrides existing layoutParams,
        // so table cells are added directly with their fixed sizes.
        val exerciseWidth=158
        val resultWidth=128
        val headerHeight=44
        val exerciseHeight=76

        val whole=row().apply {gravity=Gravity.TOP}
        val fixed=column()
        fixed.addView(cell("Übung",exerciseWidth,true,headerHeight,1))
        for(ex in exercises) {
            fixed.addView(cell(ex.name,exerciseWidth,true,exerciseHeight,3))
        }
        fixed.addView(cell("Leistungsindex",exerciseWidth,true,headerHeight,2))
        fixed.addView(cell("Sätze gesamt",exerciseWidth,false,headerHeight,1))
        add(whole,fixed,exerciseWidth)

        val scroll=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=true
            isFillViewport=false
        }
        val right=column()
        val dates=row()
        for(s in sessions) {
            val prefix=if(viewingPhaseId==null) "P"+s.phaseId+" · " else ""
            dates.addView(cell(prefix+date(s.timestamp,true),resultWidth,true,headerHeight,1))
        }
        right.addView(dates)
        for(ex in exercises) {
            val values=row()
            for(s in sessions) {
                val result=cell(store.formatCell(s.id,ex),resultWidth,false,exerciseHeight,2)
                when(store.progressDirection(s,ex)) {
                    1 -> {
                        result.background=shape(Color.rgb(226,246,232),0,Color.rgb(152,214,171))
                        result.setTextColor(Color.rgb(22,108,55))
                    }
                    -1 -> {
                        result.background=shape(Color.rgb(255,234,232),0,Color.rgb(244,168,163))
                        result.setTextColor(Color.rgb(158,42,45))
                    }
                }
                result.setOnClickListener {sessionDetails(s,ex)}
                values.addView(result)
            }
            right.addView(values)
        }
        val phase=viewingPhaseId
        val scores=if(phase!=null)store.performanceHistory(plan,phase) else emptyMap()
        val scoreRow=row()
        val countRow=row()
        for(session in sessions) {
            val perf=scores[session.id]
            val value=perf?.result?.index
            val label=(if(perf?.estimated==true && value!=null)"≈ " else "")+
                (value?.let {String.format(Locale.GERMANY,"%.1f",it)} ?: "–")
            val scoreCell=cell(label,resultWidth,true,headerHeight,1)
            if(value!=null)scoreCell.setTextColor(accent)
            scoreCell.setOnClickListener {
                AlertDialog.Builder(this).setTitle("Leistungsindex")
                    .setMessage(if(perf==null)"Kein Vergleich verfügbar." else
                        label+" · "+perf.result.comparableExercises+"/"+perf.result.referenceExercises+
                        " vergleichbare Übungen.\nReferenz: erste Einheit dieses Plans in der Phase.\n≈ = ältere Einheit, zwei reguläre Sätze angenommen.")
                    .setPositiveButton("Schließen",null).show()
            }
            scoreRow.addView(scoreCell)
            countRow.addView(cell(store.sets(session.id).size.toString(),resultWidth,false,headerHeight,1))
        }
        right.addView(scoreRow);right.addView(countRow)
        scroll.addView(right)
        whole.addView(scroll,LinearLayout.LayoutParams(0,-2,1f))
        add(page,whole)
        scroll.post {scroll.fullScroll(View.FOCUS_RIGHT)}
        gap(page,10)
        add(page,text("Grün = Steigerung · Rot = Verminderung gegenüber dem letzten Training mit dieser Übung. Gleich, gemischt oder nicht vergleichbar = neutral.",12f,false,muted))
        gap(page,6)
        add(page,text("Vergleich bei gleicher Satzanzahl anhand von kg, Wiederholungen bzw. Sekunden; RIR bleibt unberücksichtigt.",11f,false,muted))
        gap(page,6)
        add(page,text("Beispiel: 30 / 10-10 = 30 kg pro Seite, 10 und 10 Wdh. · 45s-40s = Haltedauer.",12f,false,muted))
        gap(page,6)
        add(page,text("Index: erste Einheit = 100. Zusatzsätze zählen zu ⅓; ≈ = geschätzte historische Satzanzahl.",12f,false,muted))
    }
    private fun cell(value:String,width:Int,bold:Boolean,height:Int,lines:Int):TextView =
        text(value,if(bold)12f else 13f,bold).apply {
            gravity=Gravity.CENTER_VERTICAL or (if(lines==3 || value=="Übung")
                Gravity.LEFT else Gravity.CENTER_HORIZONTAL)
            setPadding(dp(7),dp(4),dp(7),dp(4))
            background=shape(Color.WHITE,0,Color.rgb(224,232,227))
            layoutParams=LinearLayout.LayoutParams(dp(width),dp(height))
            maxLines=lines
            ellipsize=android.text.TextUtils.TruncateAt.END
            if(lines==1) setSingleLine(true)
        }
    private fun sessionDetails(s:TrainingSession,ex:Exercise) {
        val entries=store.sets(s.id).filter {it.exerciseId==ex.id}
        val body=entries.joinToString("\n") {
            "Satz ${it.number}: "+(if(ex.kind=="time")"${it.seconds ?: 0}s"
            else "${it.weight?.let {w->formatNumber(w)} ?: "–"} kg / ${it.reps ?: 0} Wdh.")+
                (it.rir?.let {r->" · RIR "+formatNumber(r)} ?: "")+
                (if(it.note.isBlank())"" else "\nBemerkung: "+it.note)
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
                    true,set.note
                )
            }.toMutableList(),setNumberOffset=entry.value.minOf {it.number}-1)
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
                    add(r,text((item.setNumberOffset+index+1).toString()+".",13f,true),28)
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
                    add(c,r)
                    add(c,button(if(set.note.isBlank())"+ Bemerkung zu Satz "+(index+1)
                        else "✎ Bemerkung bearbeiten",false){
                        noteDialog(set){refreshRows()}
                    })
                    if(set.note.isNotBlank())add(c,text(set.note,12f,false,muted))
                    gap(c,6)
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
            .setTitle(store.planName(session.plan,session.phaseId)+" bearbeiten")
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
        val phase=viewingPhaseId ?: return
        val scores=store.performanceHistory(plan,phase)
        val sessions=store.sessions(plan,phase)
        val points=sessions.mapNotNull { session->scores[session.id]?.result?.index?.let {session.timestamp to it} }
        val summary=card(page)
        add(summary,text("Leistungsindex",17f,true))
        val latest=sessions.lastOrNull()?.let {scores[it.id]}
        if(latest!=null)add(summary,text(indexLabel(latest),23f,true,accent))
        add(summary,text("Referenz = 100 · Klammern: Sätze · ≈ geschätzte Altwerte",12f,false,muted))
        if(points.isNotEmpty())add(summary,TrendChartView(points,"Index"),-1,210)
        for(session in sessions.takeLast(6).asReversed()) {
            val p=scores[session.id] ?: continue
            add(summary,text(date(session.timestamp,true)+"   "+indexLabel(p)+"   · "+p.result.comparableExercises+"/"+p.result.referenceExercises+" Übungen",12f,false,muted))
        }
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
        gap(summary,8)
        add(summary,button("Messwerte bearbeiten / löschen (${all.size})",false) {
            manageBodyWeights()
        })
        gap(summary,5)
        add(summary,text("Alle Einträge verwalten – unabhängig vom Zeitraum des Diagramms.",12f,false,muted))
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
    }

    /**
     * Edit/delete all stored weights independently from graph date filter.
     */
    private fun manageBodyWeights() {
        val entries=store.bodyWeights().asReversed()
        if(entries.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Messwerte verwalten")
                .setMessage("Es sind noch keine Gewichtsmessungen gespeichert.")
                .setNegativeButton("Schließen",null)
                .setPositiveButton("Messung erfassen"){_,_->weightDialog()}
                .show()
            return
        }
        val content=column().apply {setPadding(dp(14),dp(10),dp(14),dp(12))}
        add(content,text("Alle ${entries.size} Messungen – neueste zuerst",14f,true))
        gap(content,7)
        add(content,text("Einträge korrigieren oder löschen – unabhängig vom Diagrammzeitraum.",12f,false,muted))
        gap(content,8)
        val scroll=ScrollView(this).apply {addView(content)}
        val dialog=AlertDialog.Builder(this)
            .setTitle("Körpergewicht verwalten")
            .setView(scroll)
            .setNegativeButton("Schließen",null)
            .create()
        for(entry in entries) {
            val entryCard=card(content)
            add(entryCard,text(formatNumber(entry.kg)+" kg",16f,true))
            add(entryCard,text(date(entry.timestamp),12f,false,muted))
            gap(entryCard,7)
            val actions=row()
            actions.addView(button("Bearbeiten",false) {
                dialog.dismiss()
                weightDialog(entry,true)
            },LinearLayout.LayoutParams(0,dp(45),1f))
            add(actions,View(this),8,1)
            actions.addView(button("Löschen",false) {
                dialog.dismiss()
                confirmBodyWeightDelete(entry)
            },LinearLayout.LayoutParams(0,dp(45),1f))
            add(entryCard,actions)
            gap(content,8)
        }
        dialog.show()
    }

    private fun confirmBodyWeightDelete(entry:BodyWeight) {
        AlertDialog.Builder(this)
            .setTitle("Gewichtsmessung löschen?")
            .setMessage(date(entry.timestamp)+" · "+formatNumber(entry.kg)+
                " kg\n\nNur dieser Eintrag wird dauerhaft gelöscht. Andere Messungen und Trainings bleiben erhalten.")
            .setNegativeButton("Abbrechen"){_,_->manageBodyWeights()}
            .setPositiveButton("Löschen"){_,_->
                try {
                    store.deleteBodyWeight(entry.id)
                    render()
                    info("Gewichtsmessung gelöscht.")
                    manageBodyWeights()
                } catch(e:Exception) {
                    info(e.message ?: "Löschen fehlgeschlagen.")
                }
            }
            .show()
    }

    private fun weightDialog(existing:BodyWeight?=null,returnToList:Boolean=false) {
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
            .setView(box).setNegativeButton("Abbrechen"){_,_->
                if(returnToList)manageBodyWeights()
            }
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
                    if(returnToList)manageBodyWeights()
                } catch(e:Exception) {info(e.message ?: "Speichern fehlgeschlagen.")}
            }
        }
        dialog.show()
    }

    private fun dataPage() {
        title("Daten & Sicherung","Alle Trainingsdaten bleiben lokal auf dem Gerät.")
        val updateCard=card(page)
        add(updateCard,text("Software-Updates",17f,true))
        gap(updateCard,7)
        add(updateCard,text("Beim Start automatisch prüfen, Pre-Releases berücksichtigen oder jederzeit manuell suchen.",13f,false,muted))
        gap(updateCard,9)
        add(updateCard,button("Update-Einstellungen und Prüfung",false){updates.showSettings()})
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
        add(backup,button("Alle Trainingsdaten als Text kopieren",false){copyCompleteTrainingText()})
        gap(backup,5)
        add(backup,text("Der kopierte Text enthält alle Trainingsphasen, A/B-Pläne, Einheiten, Sätze, RIR, Notizen, Übungswechsel und Körpergewichte. Er ist zur ChatGPT-Auswertung gedacht, nicht zum Wiederherstellen der App.",12f,false,muted))
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

    private fun copyToClipboard(label:String,content:String) {
        val manager=getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val clip=android.content.ClipData.newPlainText(label,content)
        clip.description.extras=android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE",true)
        }
        manager.setPrimaryClip(clip)
        info("Trainingsdaten als Text in die Zwischenablage kopiert.")
    }

    private fun copyWorkoutText(sessionId:Long) {
        try {
            copyToClipboard("Trainingsapp – Einzeltraining",DataExport.workoutText(store,sessionId))
        } catch(e:Exception) {
            AlertDialog.Builder(this).setTitle("Kopieren fehlgeschlagen")
                .setMessage(e.message ?: "Zwischenablage nicht verfügbar.")
                .setPositiveButton("OK",null).show()
        }
    }

    private fun copyCompleteTrainingText() {
        try {
            copyToClipboard("Trainingsapp – gesamte Trainingsdaten",DataExport.fullTrainingText(store))
        } catch(e:Exception) {
            AlertDialog.Builder(this).setTitle("Kopieren fehlgeschlagen")
                .setMessage(e.message ?: "Zwischenablage nicht verfügbar.")
                .setPositiveButton("OK",null).show()
        }
    }

    private fun saveWorkoutJson(sessionId:Long) {
        saveFile("workout_json","application/json",DataExport.workoutFilename(store,sessionId),sessionId)
    }
    private fun saveFile(format:String,mime:String,name:String,sessionId:Long?=null) {
        if(pendingExport!=null) {info("Ein Export läuft bereits.");return}
        try {
            pendingExport=ExportRequest(format,sessionId)
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE);type=mime
                putExtra(Intent.EXTRA_TITLE,name)
            },501)
        } catch(e:Exception) {
            pendingExport=null
            fileError(e.message)
        }
    }
    @Deprecated("Used for Android Storage Access Framework compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==UpdateManager.PERMISSION_REQUEST) {
            updates.onPermissionReturn()
            return
        }
        if(requestCode!=501 && requestCode!=502)return
        val export=if(requestCode==501)pendingExport.also {pendingExport=null} else null
        if(resultCode!=RESULT_OK)return
        try {
            val uri=data?.data ?: error("Die Dateiauswahl wurde nicht abgeschlossen.")
            require(uri.scheme=="content") {"Bitte wähle eine Datei über die Android-Dateiauswahl."}
            if(requestCode==501) {
                val request=export ?: error("Der Exportauftrag fehlt. Bitte starte den gewünschten Export erneut.")
                val bytes=when(request.format) {
                    "xlsx"->DataExport.xlsx(store)
                    "csv"->DataExport.csv(store)
                    "workout_json"->DataExport.workoutJson(store,
                        request.workoutId ?: error("Trainingseinheit fehlt."))
                    "json"->store.backupJson().toByteArray(Charsets.UTF_8)
                    else->error("Unbekanntes Exportformat.")
                }
                val output=contentResolver.openOutputStream(uri,"wt") ?: error("Datei konnte nicht geöffnet werden.")
                output.use {it.write(bytes)}
                info("Datei gespeichert.")
            } else if(requestCode==502) {
                check(importTask==null) {"Eine Wiederherstellung läuft bereits."}
                saveDraft()
                val task=BackupImportTask(applicationContext,uri)
                importTask=task
                showImportProgress()
                observeImport(task)
                task.start()
            }
        } catch(e:Exception) {
            fileError(e.message)
        }
    }

    private fun fileError(message:String?) {
        AlertDialog.Builder(this).setTitle("Dateifehler")
            .setMessage(message ?: "Der Vorgang konnte nicht abgeschlossen werden.")
            .setPositiveButton("OK",null).show()
    }

    private fun showImportProgress() {
        root=column().apply {setPadding(dp(20),dp(30),dp(20),dp(20));setBackgroundColor(light)}
        page=root
        add(root,text("Sicherung wiederherstellen",22f,true))
        gap(root,14)
        add(root,ProgressBar(this))
        gap(root,14)
        add(root,text("Die Datei wird geprüft. Gültige Daten werden anschließend vollständig übernommen.",15f))
        gap(root,14)
        add(root,button("Abbrechen",false){importTask?.cancel()})
        setContentView(root)
    }

    private fun observeImport(task:BackupImportTask) {
        task.attach { result ->
            if(importTask===task && !isFinishing && !isDestroyed) {
                task.detach()
                importTask=null
                draft=null
                if(result.error==null) {
                    clearDraft()
                    tab="Start"
                    viewingPhaseId=store.activePhase()?.id ?: store.phases().firstOrNull()?.id
                } else restoreDraft()
                render()
                if(result.error==null)info("Datensicherung wiederhergestellt.") else fileError(result.error)
                if(activityResumed && ::updates.isInitialized)updates.onResume()
            }
        }
    }

    @Deprecated("Android back callback")
    override fun onBackPressed() {
        if(importTask!=null) {info("Die Wiederherstellung läuft. Mit „Abbrechen“ kannst du sie beenden.");return}
        if(draft!=null && tab=="Workout") {
            AlertDialog.Builder(this).setTitle("Training läuft")
                .setMessage("Zur Startseite wechseln? Die aktuellen Eingaben bleiben erhalten.")
                .setNegativeButton("Bleiben",null)
                .setPositiveButton("Startseite"){_,_->tab="Start";render()}.show()
        } else {tab=if(tab=="Katalog")"Training" else "Start";render()}
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
