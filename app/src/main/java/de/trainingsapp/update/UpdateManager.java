package de.trainingsapp.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Based on Sofacockpit's opt-in GitHub release updater. Only public release
 * metadata is checked automatically. APK download/install requires consent.
 */
public final class UpdateManager implements AutoCloseable {
    public static final int PERMISSION_REQUEST=753;
    private static final String AUTO="auto_check", ALL="include_prerelease";
    private final Activity activity;
    private final SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ReleaseCatalog.Version installed;
    private final int installedCode;
    private final String installedName;
    private volatile boolean closed=false;
    private boolean resumed=false,checking=false,manualRequested=false,downloading=false;
    private ReleaseCatalog pendingCatalog;
    private boolean pendingManual;
    private ReleaseCatalog.Release permissionRelease,approvedDownload,verifiedRelease;
    private File verifiedApk;
    private AtomicBoolean cancelDownload;
    private AlertDialog currentDialog;
    private ProgressBar progress;
    private TextView progressText;
    private final Runnable present=this::maybePresent;

    public UpdateManager(Activity activity) {
        this.activity=activity;
        this.prefs=activity.getSharedPreferences("software_updates",Activity.MODE_PRIVATE);
        String name="Unbekannt";int code=-1;
        try {
            android.content.pm.PackageInfo info=activity.getPackageManager().getPackageInfo(
                activity.getPackageName(),0);
            if(info.versionName!=null)name=info.versionName;
            code=(int)info.getLongVersionCode();
        }catch(Exception ignored){}
        installedName=name;
        installed=ReleaseCatalog.Version.parse(name);
        installedCode=code;
    }
    public String versionName(){return installedName;}
    private boolean automatic(){return prefs.getBoolean(AUTO,true);}
    private boolean all(){return prefs.getBoolean(ALL,true);}
    private String skipKey(boolean include){return include?"skipped_all":"skipped_stable";}
    public void startAutomaticCheck(){if(automatic())check(false);}
    public void checkNow(){check(true);}
    public void onResume(){resumed=true;schedule();}
    public void onPause(){resumed=false;main.removeCallbacks(present);}
    public void onWindowFocus(){schedule();}
    public void onPermissionReturn(){
        ReleaseCatalog.Release release=permissionRelease;
        permissionRelease=null;
        if(closed||release==null)return;
        if(canInstall()) {
            approvedDownload=release;schedule();
        } else toast("Installation nicht erlaubt. Unter Software-Updates erneut versuchen.");
    }
    private void schedule(){
        if(closed||!resumed)return;
        main.removeCallbacks(present);
        main.postDelayed(present,240);
    }
    private boolean foreground(){
        return !closed&&resumed&&!activity.isFinishing()&&!activity.isDestroyed()&&activity.hasWindowFocus();
    }
    private void check(boolean manual){
        if(closed)return;
        if(installed==null||installedCode<0){
            if(manual)toast("Installierte Versionsnummer unbekannt.");
            return;
        }
        if(downloading||permissionRelease!=null||approvedDownload!=null||verifiedApk!=null){
            if(manual)toast("Ein Update wird bereits vorbereitet.");
            return;
        }
        manualRequested |= manual;
        if(checking) return;
        pendingCatalog=null;checking=true;
        if(manual)toast("Suche nach Updates …");
        worker.execute(()->{
            try{
                ReleaseCatalog result=GithubUpdates.query(()->closed);
                main.post(()->{
                    if(closed)return;
                    checking=false;pendingManual=manualRequested;manualRequested=false;
                    pendingCatalog=result;schedule();
                });
            }catch(Exception error){
                main.post(()->{
                    if(closed)return;
                    checking=false;
                    boolean notify=manualRequested;manualRequested=false;
                    if(notify)toast("Updateprüfung nicht möglich. Internetverbindung prüfen.");
                });
            }
        });
    }
    private void maybePresent(){
        if(!foreground()||currentDialog!=null)return;
        if(verifiedApk!=null){
            File file=verifiedApk;ReleaseCatalog.Release release=verifiedRelease;
            verifiedApk=null;verifiedRelease=null;
            install(file,release);return;
        }
        if(approvedDownload!=null){
            ReleaseCatalog.Release release=approvedDownload;approvedDownload=null;
            if(canInstall())download(release);
            else toast("Installationsberechtigung fehlt.");
            return;
        }
        if(downloading||permissionRelease!=null||pendingCatalog==null)return;
        ReleaseCatalog result=pendingCatalog;pendingCatalog=null;
        boolean manual=pendingManual;pendingManual=false;
        if(!manual&&!automatic())return;
        boolean includePre=all();
        int skipped=prefs.getInt(skipKey(includePre),-1);
        ReleaseCatalog.Release release=result.choose(installed,installedCode,includePre,skipped,manual);
        if(release==null){
            if(manual)toast(includePre?
                "Keine neuere Version verfügbar.":
                "Kein neueres stabiles Release verfügbar.");
            return;
        }
        showOffer(release,includePre);
    }
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){
        TextView t=new TextView(activity);
        t.setText(value);t.setTextSize(size);
        t.setPadding(0,dp(4),0,dp(4));
        return t;
    }
    private LinearLayout box(){
        LinearLayout b=new LinearLayout(activity);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(18),dp(10),dp(18),dp(10));
        return b;
    }
    private void show(AlertDialog dialog){
        if(closed||activity.isFinishing())return;
        currentDialog=dialog;
        dialog.setOnDismissListener(d->{
            if(currentDialog==dialog)currentDialog=null;
            schedule();
        });
        dialog.show();
    }
    public void showSettings(){
        if(closed||activity.isFinishing()||currentDialog!=null)return;
        if(downloading||permissionRelease!=null||approvedDownload!=null){
            toast("Ein Update wird vorbereitet.");return;
        }
        LinearLayout content=box();
        content.addView(text("Installiert: v"+installedName+" (Build "+installedCode+")",17));
        CheckBox automaticCheck=new CheckBox(activity);
        automaticCheck.setText("Beim App-Start automatisch nach Updates suchen");
        automaticCheck.setChecked(automatic());
        automaticCheck.setOnCheckedChangeListener((button,on)->
            prefs.edit().putBoolean(AUTO,on).apply());
        content.addView(automaticCheck);
        content.addView(text("Update-Kanal",15));
        RadioGroup channels=new RadioGroup(activity);
        channels.setOrientation(RadioGroup.VERTICAL);
        RadioButton stable=new RadioButton(activity);
        RadioButton both=new RadioButton(activity);
        stable.setId(View.generateViewId());both.setId(View.generateViewId());
        stable.setText("Nur stabile Releases");
        both.setText("Releases und Pre-Releases");
        channels.addView(stable);channels.addView(both);
        channels.check(all()?both.getId():stable.getId());
        channels.setOnCheckedChangeListener((g,id)->
            prefs.edit().putBoolean(ALL,id==both.getId()).apply());
        content.addView(channels);
        content.addView(text("Pre-Releases sind Testversionen. Eine manuelle Prüfung bietet auch übersprungene Updates wieder an.",12));
        content.addView(text("Es werden nur öffentliche GitHub-Release-Daten abgefragt – keine Trainingsdaten übertragen.",12));
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        show(new AlertDialog.Builder(activity).setTitle("Software-Updates")
            .setView(scroll)
            .setPositiveButton("Jetzt prüfen",(d,w)->check(true))
            .setNegativeButton("Schließen",null)
            .create());
    }
    private void showOffer(ReleaseCatalog.Release release,boolean includePre){
        LinearLayout content=box();
        content.addView(text(release.prerelease?"PRE-RELEASE · ENTWICKLUNGSVERSION":"STABILES RELEASE",16));
        content.addView(text("Version "+release.version+" · Build "+release.versionCode,20));
        content.addView(text("Installiert: "+installedName+" · Build "+installedCode+
            "  |  "+String.format(Locale.GERMANY,"%.1f MB",release.size/1_000_000.0),13));
        content.addView(text(release.prerelease?
            "Diese Testversion kann noch Fehler enthalten.":
            "Reguläres Release auf GitHub.",13));
        content.addView(text("Neuerungen:",14));
        TextView notes=text(release.notes.isBlank()?"Keine Änderungsbeschreibung vorhanden.":release.notes,13);
        ScrollView notesScroll=new ScrollView(activity);
        notesScroll.addView(notes);
        notesScroll.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(155)));
        content.addView(notesScroll);
        content.addView(text("Die APK wird erst nach Zustimmung heruntergeladen und vor dem Android-Installer geprüft.",12));
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        show(new AlertDialog.Builder(activity).setTitle("Update verfügbar")
            .setView(scroll)
            .setPositiveButton("Aktualisieren",(d,w)->requestUpdate(release))
            .setNegativeButton("Später",null)
            .setNeutralButton("Überspringen",(d,w)->{
                int keyBefore=prefs.getInt(skipKey(includePre),-1);
                if(release.versionCode>keyBefore)
                    prefs.edit().putInt(skipKey(includePre),release.versionCode).apply();
                toast("Update übersprungen.");
            }).create());
    }
    private boolean canInstall(){
        try{return activity.getPackageManager().canRequestPackageInstalls();}
        catch(Exception e){return false;}
    }
    private void requestUpdate(ReleaseCatalog.Release release){
        if(closed)return;
        if(canInstall()){approvedDownload=release;schedule();return;}
        main.post(()->{
            if(closed||activity.isFinishing())return;
            show(new AlertDialog.Builder(activity).setTitle("Installation erlauben")
                .setMessage("Android benötigt einmalig die Berechtigung „Unbekannte Apps installieren“ für Trainingsapp. Die APK wird erst danach heruntergeladen.")
                .setNegativeButton("Abbrechen",null)
                .setPositiveButton("Android-Einstellungen",(d,w)->{
                    permissionRelease=release;
                    try{
                        activity.startActivityForResult(
                            new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:"+activity.getPackageName())),
                            PERMISSION_REQUEST);
                    }catch(Exception ex){
                        permissionRelease=null;
                        toast("Installationsberechtigung konnte nicht geöffnet werden.");
                    }
                }).create());
        });
    }
    private void download(ReleaseCatalog.Release release){
        if(closed||downloading)return;
        downloading=true;
        AtomicBoolean cancelled=new AtomicBoolean(false);cancelDownload=cancelled;
        LinearLayout content=box();
        progressText=text("Download "+release.version+" … 0 %",15);
        content.addView(progressText);
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        content.addView(progress,new LinearLayout.LayoutParams(-1,dp(20)));
        content.addView(text("Dateigröße, SHA-256 (sofern vorhanden), App-ID, Version und Signatur werden geprüft.",12));
        AlertDialog loading=new AlertDialog.Builder(activity).setTitle("Update wird geladen")
            .setView(content)
            .setNegativeButton("Abbrechen",(d,w)->cancelled.set(true))
            .setCancelable(false).create();
        show(loading);
        worker.execute(()->{
            File part=null,apk=null;
            try{
                File dir=new File(activity.getCacheDir(),"updates");
                if(!dir.isDirectory()&&!dir.mkdirs())
                    throw new IOException("Update-Ordner nicht verfügbar.");
                File[] old=dir.listFiles();
                if(old!=null)for(File file:old)
                    if(file.isFile()&&System.currentTimeMillis()-file.lastModified()>172800000L)file.delete();
                part=File.createTempFile("trainingsapp-",".part",dir);
                GithubUpdates.download(release,part,()->closed||cancelled.get(),percent->
                    main.post(()->{
                        if(!closed&&cancelDownload==cancelled&&progress!=null&&progressText!=null){
                            progress.setProgress(percent);
                            progressText.setText(percent<100?
                                "Download "+release.version+" … "+percent+" %":"APK wird geprüft …");
                        }
                    }));
                if(closed||cancelled.get())throw new IOException("Abgebrochen");
                apk=new File(dir,part.getName().replace(".part",".apk"));
                if(!part.renameTo(apk))throw new IOException("APK konnte nicht gespeichert werden.");
                part=null;
                ApkVerification.verify(activity,apk,release);
                if(closed||cancelled.get())throw new IOException("Abgebrochen");
                File ready=apk;apk=null;
                main.post(()->{
                    finishDownload(loading,cancelled);
                    if(closed||cancelled.get()){ready.delete();return;}
                    verifiedApk=ready;verifiedRelease=release;schedule();
                });
            }catch(Exception ex){
                String why=ex instanceof IOException&&ex.getMessage()!=null?
                    ex.getMessage():"Download oder APK-Prüfung fehlgeschlagen.";
                main.post(()->{
                    finishDownload(loading,cancelled);
                    if(!closed)toast(cancelled.get()?"Update abgebrochen.":"Update nicht installiert: "+why);
                });
            }finally{
                if(part!=null)part.delete();
                if(apk!=null)apk.delete();
            }
        });
    }
    private void finishDownload(AlertDialog dialog,AtomicBoolean token){
        if(cancelDownload!=token)return;
        downloading=false;cancelDownload=null;progress=null;progressText=null;
        if(dialog.isShowing())dialog.dismiss();
    }
    private void install(File apk,ReleaseCatalog.Release release){
        if(closed){apk.delete();return;}
        if(!canInstall()){apk.delete();toast("Installationsberechtigung fehlt.");return;}
        try{
            Uri uri=FileProvider.getUriForFile(activity,
                activity.getPackageName()+".updates",apk);
            Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("Trainingsapp "+release.version,uri));
            activity.startActivity(intent);
        }catch(Exception ex){
            try{
                Uri uri=FileProvider.getUriForFile(activity,
                    activity.getPackageName()+".updates",apk);
                Intent intent=new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri,"application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("Trainingsapp",uri));
                activity.startActivity(intent);
            }catch(Exception secondary){
                apk.delete();toast("Android-Installer konnte nicht geöffnet werden.");
            }
        }
        // Android controls installation; no success is claimed here.
    }
    private void toast(String value){
        if(!closed)Toast.makeText(activity,value,Toast.LENGTH_LONG).show();
    }
    @Override public void close(){
        closed=true;resumed=false;
        main.removeCallbacks(present);
        if(cancelDownload!=null)cancelDownload.set(true);
        if(currentDialog!=null){currentDialog.dismiss();currentDialog=null;}
        if(verifiedApk!=null){verifiedApk.delete();verifiedApk=null;}
        permissionRelease=null;approvedDownload=null;pendingCatalog=null;
        worker.shutdownNow();
    }
}
