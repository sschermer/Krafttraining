package de.trainingsapp.update;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Regression checks for release trust boundaries and download integrity. */
public class UpdateSecurityTest {
    private static final String TAG = "v0.5.5-build67";
    private static final String NAME = "Trainingsapp-"+TAG+"-signed.apk";
    private static final String URL_OK = "https://github.com/sschermer/Krafttraining/releases/download/"+TAG+"/"+NAME;
    private static final byte[] CONTENT = "security-audit-safe-test-bytes".getBytes(StandardCharsets.UTF_8);
    private static final long FUTURE = Long.MAX_VALUE;

    private static JSONObject release() throws JSONException {
        return new JSONObject().put("draft",false).put("prerelease",false)
            .put("published_at","2026-11-26T13:43:21Z").put("tag_name",TAG)
            .put("assets",new JSONArray().put(new JSONObject().put("name",NAME)
                .put("state","uploaded").put("size",CONTENT.length)
                .put("digest","sha256:"+"a".repeat(64)).put("browser_download_url",URL_OK)));
    }
    private static JSONObject asset(JSONObject release) throws JSONException {
        return release.getJSONArray("assets").getJSONObject(0);
    }
    private static ReleaseCatalog catalog(JSONObject release) {
        ReleaseCatalog c = new ReleaseCatalog(); c.addPage(new JSONArray().put(release)); return c;
    }
    private static ReleaseCatalog.Release selected(JSONObject release) {
        return catalog(release).choose(ReleaseCatalog.Version.parse("0.5.4"),64,true,-1,true);
    }
    private static String digest() throws Exception {
        return "sha256:"+GithubUpdates.hex(MessageDigest.getInstance("SHA-256").digest(CONTENT));
    }
    private static ByteArrayOutputStream copy(byte[] input,long size,String digest,
        boolean cancelled,long deadline) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        GithubUpdates.copyVerified(new ByteArrayInputStream(input),out,size,digest,
            ()->cancelled,p->{},deadline);
        return out;
    }

    @Test public void releaseRejectsMalformedTagsAndMissingMetadata() throws Exception {
        assertNotNull(selected(release()));
        for(String tag:new String[]{"v0.5.5-build0","v0.5.5-build1000000000","v00.5.5-build67",
            "v0.5.5-build067","0.5.5-build67","v0.5.5-build67/path","v0.5.5-build67?x=1"})
            assertNull(tag,selected(release().put("tag_name",tag)));
        assertNull(selected(release().put("draft",true)));
        assertNull(selected(release().put("published_at","")));
        assertNull(selected(release().put("prerelease","false")));
        JSONObject noDraft=release(); noDraft.remove("draft"); assertNull(selected(noDraft));
    }

    @Test public void releaseRejectsOffRepositoryOrAlteredUrls() throws Exception {
        for(String url:new String[]{URL_OK.replace("sschermer/Krafttraining","attacker/other"),
            URL_OK.replace("https://","http://"),URL_OK+"?download=1",URL_OK+"#fragment",
            URL_OK.replace("github.com","github.com.evil.example"),
            URL_OK.replace("github.com","user@github.com"),
            URL_OK.replace("github.com","release-assets.githubusercontent.com")}) {
            JSONObject r=release();asset(r).put("browser_download_url",url);assertNull(url,selected(r));
        }
    }

    @Test public void releaseRejectsBadDigestsAndSizes() throws Exception {
        for(String d:new String[]{"md5:"+"a".repeat(32),"sha256:"+"a".repeat(63),
            "sha256:"+"g".repeat(64),"sha256:"+"a".repeat(65)}) {
            JSONObject r=release();asset(r).put("digest",d);assertNull(d,selected(r));
        }
        for(long n:new long[]{0,-1,ReleaseCatalog.MAX_APK_BYTES+1}) {
            JSONObject r=release();asset(r).put("size",n);assertNull("size="+n,selected(r));
        }
    }

    @Test public void releaseRejectsMissingOrEmptyDigest() throws Exception {
        JSONObject r=release(); asset(r).remove("digest"); assertNull(selected(r));
        r=release();asset(r).put("digest","");assertNull(selected(r));
    }

    @Test public void missingDigestProducesSpecificProblemOnlyForRelevantCandidate() throws Exception {
        JSONObject r=release();asset(r).remove("digest");
        ReleaseCatalog c=catalog(r);
        assertNotNull(c.validationProblem(ReleaseCatalog.Version.parse("0.5.4"),64,true,-1,true));
        assertTrue(c.validationProblem(ReleaseCatalog.Version.parse("0.5.4"),64,true,-1,true)
            .contains("Prüfsumme"));
        assertNull(c.validationProblem(ReleaseCatalog.Version.parse("0.5.5"),67,true,-1,true));
        assertNull(c.validationProblem(ReleaseCatalog.Version.parse("0.5.4"),64,true,67,false));
        r.put("prerelease",true);c=catalog(r);
        assertNull(c.validationProblem(ReleaseCatalog.Version.parse("0.5.4"),64,false,-1,true));
    }

    @Test public void incompleteNewestCandidateIsNotSilentlyReplacedWithOlderRelease() throws Exception {
        JSONObject valid=release();
        JSONObject invalid=release();
        invalid.put("tag_name","v0.5.6-build68");
        asset(invalid).put("name","Trainingsapp-v0.5.6-build68-signed.apk")
            .put("browser_download_url",URL_OK.replace("v0.5.5-build67","v0.5.6-build68"))
            .remove("digest");
        ReleaseCatalog c=new ReleaseCatalog();c.addPage(new JSONArray().put(valid).put(invalid));
        assertNull(c.choose(ReleaseCatalog.Version.parse("0.5.4"),64,true,-1,true));
        assertTrue(c.validationProblem(ReleaseCatalog.Version.parse("0.5.4"),64,true,-1,true)
            .contains("0.5.6"));
    }

    @Test public void releaseRejectsDuplicateAssetsAndWrongState() throws Exception {
        JSONObject r=release();r.getJSONArray("assets").put(new JSONObject(asset(r).toString()));
        assertNull(selected(r));
        r=release();asset(r).put("state","new");assertNull(selected(r));
    }

    @Test public void selectionRejectsSemanticAndBuildDowngrades() throws Exception {
        ReleaseCatalog c=catalog(release());
        assertNull(c.choose(ReleaseCatalog.Version.parse("0.6.0"),66,true,-1,true));
        assertNull(c.choose(ReleaseCatalog.Version.parse("0.5.4"),68,true,-1,true));
        assertNull(c.choose(ReleaseCatalog.Version.parse("0.5.5"),67,true,-1,true));
        assertNotNull(c.choose(ReleaseCatalog.Version.parse("0.5.5"),66,true,-1,true));
    }

    @Test public void hostValidationAcceptsOnlyExpectedHttpsHosts() throws Exception {
        for(String h:new String[]{"github.com","release-assets.githubusercontent.com",
            "objects.githubusercontent.com","github-releases.githubusercontent.com"}) {
            assertTrue(h,GithubUpdates.allowedDownload(new URL("https://"+h+"/file.apk")));
            assertTrue(h,GithubUpdates.allowedDownload(new URL("https://"+h+":443/file.apk?sig=token")));
        }
    }

    @Test public void hostValidationRejectsDowngradesCredentialsPortsAndFragments() throws Exception {
        for(String url:new String[]{"http://github.com/file.apk","ftp://github.com/file.apk",
            "https://evil.example/file.apk","https://github.com.evil.example/file.apk",
            "https://api.github.com/file.apk","https://github.com./file.apk",
            "https://127.0.0.1/file.apk","https://user@github.com/file.apk",
            "https://user:password@github.com/file.apk","https://github.com:80/file.apk",
            "https://github.com:444/file.apk","https://github.com/file.apk#fragment"})
            assertFalse(url,GithubUpdates.allowedDownload(new URL(url)));
    }

    @Test public void copyAcceptsCorrectDigestAndSignalsComplete() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); final int[] progress={-1};
        GithubUpdates.copyVerified(new ByteArrayInputStream(CONTENT),out,CONTENT.length,digest(),
            ()->false,p->progress[0]=p,FUTURE);
        assertArrayEquals(CONTENT,out.toByteArray()); assertEquals(100,progress[0]);
    }

    @Test public void copyRejectsWrongDigest() throws Exception {
        assertThrows(IOException.class,()->copy(CONTENT,CONTENT.length,"sha256:"+"0".repeat(64),false,FUTURE));
    }

    @Test public void copyRejectsTruncatedContent() throws Exception {
        assertThrows(IOException.class,()->copy(CONTENT,CONTENT.length+1,digest(),false,FUTURE));
    }

    @Test public void copyRejectsOversizedContent() throws Exception {
        assertThrows(IOException.class,()->copy(CONTENT,CONTENT.length-1,digest(),false,FUTURE));
    }

    @Test public void copyRejectsInvalidSizeBounds() throws Exception {
        for(long size:new long[]{0,-1,ReleaseCatalog.MAX_APK_BYTES+1})
            assertThrows(IOException.class,()->copy(CONTENT,size,digest(),false,FUTURE));
    }

    @Test public void copyRejectsEmptyDigest() throws Exception {
        assertThrows(IOException.class,()->copy(CONTENT,CONTENT.length,"",false,FUTURE));
    }

    @Test public void copyRejectsCancellation() throws Exception {
        assertThrows(IOException.class,()->copy(CONTENT,CONTENT.length,digest(),true,FUTURE));
    }

    @Test public void copyRejectsExpiredDeadline() throws Exception {
        assertThrows(IOException.class,()->copy(CONTENT,CONTENT.length,digest(),false,System.nanoTime()-1));
    }
}
