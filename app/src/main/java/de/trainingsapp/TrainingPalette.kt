package de.trainingsapp

/** Color-only variants; structure and controls are identical. */
enum class TrainingPalette(
    val displayName:String,val darkMode:Boolean,
    headerHex:String,accentHex:String,backgroundHex:String,surfaceHex:String,
    textHex:String,mutedHex:String,borderHex:String,secondaryHex:String,
    exerciseHex:String,completedHex:String,completedHeaderHex:String,onAccentHex:String
) {
    SAGE("Sage",false,"#183A33","#197164","#EDF2EF","#FFFFFF","#252F2C","#697872",
        "#D2E0D9","#E2EDE7","#D5E5DB","#E6F3E9","#B8DEC7","#FFFFFF"),
    FOREST("Forest",true,"#101C16","#67C99B","#14221D","#20332B","#E7F2EB","#A7BDB0",
        "#365347","#2C4538","#345342","#264C38","#3E674F","#10251B"),
    GRAPHITE("Graphite",true,"#17191B","#78C9A7","#202225","#2C2F33","#F0F2F3","#B8C1C4",
        "#474C50","#373C41","#3B4145","#28483B","#385C4A","#13241D");

    private fun color(hex:String)=android.graphics.Color.parseColor(hex)
    val header=color(headerHex)
    val accent=color(accentHex)
    val background=color(backgroundHex)
    val surface=color(surfaceHex)
    val text=color(textHex)
    val muted=color(mutedHex)
    val border=color(borderHex)
    val secondary=color(secondaryHex)
    val exerciseHeader=color(exerciseHex)
    val completed=color(completedHex)
    val completedHeader=color(completedHeaderHex)
    val onAccent=color(onAccentHex)

    companion object {
        fun fromName(name:String?)=entries.firstOrNull {it.name==name} ?: SAGE
    }
}
