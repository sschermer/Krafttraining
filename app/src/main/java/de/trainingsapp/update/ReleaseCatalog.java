package de.trainingsapp.update;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict selection of published GitHub Releases including prereleases.
 * Trainingsapp's release tag is vMAJOR.MINOR.PATCH-buildN and build N is
 * also the actual Android versionCode, which must be higher to upgrade.
 */
public final class ReleaseCatalog {
    public static final String REPO="sschermer/Krafttraining";
    public static final String REPO_URL="https://github.com/"+REPO;
    public static final long MAX_APK_BYTES=128L*1024*1024;
    private static final Pattern VERSION=Pattern.compile(
        "(0|[1-9][0-9]{0,7})\\.(0|[1-9][0-9]{0,7})\\.(0|[1-9][0-9]{0,7})");
    private static final Pattern TAG=Pattern.compile(
        "v(0|[1-9][0-9]{0,7})\\.(0|[1-9][0-9]{0,7})\\.(0|[1-9][0-9]{0,7})-build([1-9][0-9]{0,8})");
    private Release newest,stable;

    public static final class Version implements Comparable<Version> {
        private final int major,minor,patch;
        private Version(int major,int minor,int patch){
            this.major=major;this.minor=minor;this.patch=patch;
        }
        public static Version parse(String text){
            if(text==null)return null;
            Matcher m=VERSION.matcher(text);
            return m.matches()?new Version(Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))):null;
        }
        @Override public int compareTo(Version other){
            int c=Integer.compare(major,other.major);
            if(c==0)c=Integer.compare(minor,other.minor);
            if(c==0)c=Integer.compare(patch,other.patch);
            return c;
        }
        @Override public String toString(){return major+"."+minor+"."+patch;}
    }
    public static final class Release {
        public final Version version;
        public final int versionCode;
        public final String tag,notes,downloadUrl,digest;
        public final boolean prerelease;
        public final long size;
        private Release(Version version,int versionCode,String tag,boolean prerelease,
                        String notes,String downloadUrl,String digest,long size){
            this.version=version;this.versionCode=versionCode;this.tag=tag;
            this.prerelease=prerelease;this.notes=notes;this.downloadUrl=downloadUrl;
            this.digest=digest;this.size=size;
        }
    }
    public void addPage(JSONArray page) {
        for(int i=0;i<page.length();i++){
            Release release=parseRelease(page.optJSONObject(i));
            if(release==null)continue;
            if(isNewer(release,newest))newest=release;
            if(!release.prerelease&&isNewer(release,stable))stable=release;
        }
    }
    private static boolean isNewer(Release r,Release existing){
        if(existing==null)return true;
        int cmp=r.version.compareTo(existing.version);
        if(cmp!=0)return cmp>0;
        if(r.versionCode!=existing.versionCode)return r.versionCode>existing.versionCode;
        return existing.prerelease&&!r.prerelease;
    }
    public Release choose(Version installed,int installedCode,boolean includePrerelease,
                          int skippedCode,boolean manual) {
        Release candidate=eligible(installed,installedCode,includePrerelease,skippedCode,manual);
        return candidate!=null&&validDigest(candidate.digest)?candidate:null;
    }
    public String validationProblem(Version installed,int installedCode,boolean includePrerelease,
                                    int skippedCode,boolean manual){
        Release candidate=eligible(installed,installedCode,includePrerelease,skippedCode,manual);
        if(candidate==null||validDigest(candidate.digest))return null;
        return "Update "+candidate.version+" (Build "+candidate.versionCode+
            ") wird nicht angeboten: Die SHA-256-Prüfsumme auf GitHub fehlt oder ist ungültig.";
    }
    static boolean validDigest(String digest){
        return digest!=null&&digest.matches("sha256:[a-fA-F0-9]{64}");
    }
    private Release eligible(Version installed,int installedCode,boolean includePrerelease,
                             int skippedCode,boolean manual){
        Release candidate=includePrerelease?newest:stable;
        if(installed==null||candidate==null||
            candidate.version.compareTo(installed)<0||
            candidate.versionCode<=installedCode)return null;
        if(!manual && candidate.versionCode<=skippedCode)return null;
        return candidate;
    }
    private static Release parseRelease(JSONObject entry){
        if(entry==null||entry.optBoolean("draft",true)||
           !(entry.opt("prerelease") instanceof Boolean)||
           entry.optString("published_at","").isEmpty())return null;
        String tag=entry.optString("tag_name","");
        Matcher m=TAG.matcher(tag);
        if(!m.matches())return null;
        Version version=new Version(Integer.parseInt(m.group(1)),
            Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));
        int build=Integer.parseInt(m.group(4));
        if(build<=0)return null;
        String name="Trainingsapp-v"+version+"-build"+build+"-signed.apk";
        String expected=REPO_URL+"/releases/download/"+tag+"/"+name;
        JSONArray assets=entry.optJSONArray("assets");
        if(assets==null)return null;
        JSONObject asset=null;
        for(int i=0;i<assets.length();i++){
            JSONObject candidate=assets.optJSONObject(i);
            if(candidate!=null&&name.equals(candidate.optString("name",""))){
                if(asset!=null)return null;
                asset=candidate;
            }
        }
        if(asset==null||!"uploaded".equals(asset.optString("state",""))||
           !expected.equals(asset.optString("browser_download_url","")))return null;
        long size=asset.optLong("size",-1);
        if(size<=0||size>MAX_APK_BYTES)return null;
        String digest=asset.optString("digest","");
        // Keep incomplete candidates internally so the user sees the actual
        // verification problem instead of a misleading "no update" result.
        // choose() never exposes a candidate without a valid SHA-256 digest.
        String body=entry.optString("body","");
        if(body.length()>5000)body=body.substring(0,5000)+"\n…";
        return new Release(version,build,tag,entry.optBoolean("prerelease"),
            body,expected,digest.toLowerCase(Locale.ROOT),size);
    }
}
