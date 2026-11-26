package de.trainingsapp.update;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Regression checks for our exact GitHub release/tag conventions. */
public class ReleaseCatalogTest {
    private static JSONObject release(String version,int build,boolean prerelease) throws Exception {
        String tag="v"+version+"-build"+build;
        String name="Trainingsapp-v"+version+"-build"+build+"-signed.apk";
        JSONObject asset=new JSONObject()
            .put("name",name).put("state","uploaded").put("size",1613643L)
            .put("digest","sha256:cccda77929a26dbf6a25bf428b3a226cfdb8e88776255c15d56b75080d8523d5")
            .put("browser_download_url","https://github.com/sschermer/Krafttraining/releases/download/"+tag+"/"+name);
        return new JSONObject()
            .put("draft",false).put("prerelease",prerelease)
            .put("published_at","2026-11-26T12:36:07Z")
            .put("tag_name",tag).put("assets",new JSONArray().put(asset));
    }
    @Test public void findsNewerPrereleaseForInstalledBuild51()throws Exception{
        ReleaseCatalog catalog=new ReleaseCatalog();
        catalog.addPage(new JSONArray()
            .put(release("0.5.0",51,true))
            .put(release("0.5.1",54,true)));
        ReleaseCatalog.Release result=catalog.choose(
            ReleaseCatalog.Version.parse("0.5.0"),51,true,-1,false);
        assertNotNull(result);
        assertEquals(54,result.versionCode);
        assertEquals("v0.5.1-build54",result.tag);
        assertTrue(result.prerelease);
    }
    @Test public void stableOnlyHidesPrereleases()throws Exception{
        ReleaseCatalog catalog=new ReleaseCatalog();
        catalog.addPage(new JSONArray().put(release("0.5.1",54,true)));
        assertNull(catalog.choose(ReleaseCatalog.Version.parse("0.5.0"),51,false,-1,false));
    }
    @Test public void manualCheckOverridesSkippedBuild()throws Exception{
        ReleaseCatalog catalog=new ReleaseCatalog();
        catalog.addPage(new JSONArray().put(release("0.5.1",54,true)));
        assertNull(catalog.choose(ReleaseCatalog.Version.parse("0.5.0"),51,true,54,false));
        assertNotNull(catalog.choose(ReleaseCatalog.Version.parse("0.5.0"),51,true,54,true));
    }
    @Test public void recognizesNextBuildOfSameVersion()throws Exception{
        ReleaseCatalog catalog=new ReleaseCatalog();
        catalog.addPage(new JSONArray().put(release("0.5.1",55,true)));
        assertNotNull(catalog.choose(ReleaseCatalog.Version.parse("0.5.1"),54,true,-1,false));
        assertNull(catalog.choose(ReleaseCatalog.Version.parse("0.5.1"),55,true,-1,false));
    }
    @Test public void rejectWrongAssetName()throws Exception{
        JSONObject invalid=release("0.5.1",54,true);
        invalid.getJSONArray("assets").getJSONObject(0).put("name","example.apk");
        ReleaseCatalog catalog=new ReleaseCatalog();
        catalog.addPage(new JSONArray().put(invalid));
        assertNull(catalog.choose(ReleaseCatalog.Version.parse("0.5.0"),51,true,-1,false));
    }
}
