package de.trainingsapp.update;

import static org.junit.Assert.*;
import org.junit.Test;

public class UpdateCheckPolicyTest {
    private static final long NOW=1_800_000_000_000L;

    @Test public void firstAutomaticCheckRunsImmediately(){
        assertEquals(0,UpdateCheckPolicy.waitMillis(false,NOW,0,0,0));
    }

    @Test public void automaticSuccessIsReusedForFifteenMinutes(){
        assertEquals(14*60_000L,UpdateCheckPolicy.waitMillis(false,NOW,NOW-60_000,0,0));
        assertEquals(0,UpdateCheckPolicy.waitMillis(false,NOW,NOW-15*60_000L,0,0));
        assertTrue(UpdateCheckPolicy.cacheFresh(NOW-60_000,NOW));
        assertFalse(UpdateCheckPolicy.cacheFresh(NOW-15*60_000L,NOW));
    }

    @Test public void manualCheckBypassesCacheAndOrdinaryFailureBackoff(){
        assertEquals(0,UpdateCheckPolicy.waitMillis(true,NOW,NOW,NOW+300_000,0));
    }

    @Test public void manualAndAutomaticChecksRespectServerRetryDeadline(){
        assertEquals(3_600_000L,UpdateCheckPolicy.waitMillis(true,NOW,NOW,0,NOW+3_600_000));
        assertEquals(3_600_000L,UpdateCheckPolicy.waitMillis(false,NOW,NOW,NOW+60_000,NOW+3_600_000));
        assertEquals(0,UpdateCheckPolicy.waitMillis(true,NOW,NOW,0,NOW-1));
    }

    @Test public void ordinaryFailuresBackOffExponentiallyWithUpperBound(){
        int count=0;long[] expected={60_000,120_000,240_000,480_000,900_000,900_000};
        for(long delay:expected){
            count=UpdateCheckPolicy.nextFailureCount(count);
            assertEquals(delay,UpdateCheckPolicy.failureDelayMillis(count));
        }
        assertEquals(300_000,UpdateCheckPolicy.waitMillis(false,NOW,0,NOW+300_000,0));
    }

    @Test public void readsRetryAfterSecondsAndPrimaryReset(){
        assertEquals(120_000,UpdateCheckPolicy.serverDelayMillis("120",null,null,NOW));
        assertEquals(3_600_000,UpdateCheckPolicy.serverDelayMillis(null,"0",
            Long.toString(NOW/1000+3600),NOW));
        assertEquals(3_600_000,UpdateCheckPolicy.serverDelayMillis("120","0",
            Long.toString(NOW/1000+3600),NOW));
    }

    @Test public void ignoresPrimaryResetUnlessQuotaIsExhausted(){
        assertEquals(60_000,UpdateCheckPolicy.serverDelayMillis(null,"50",
            Long.toString(NOW/1000+3600),NOW));
    }

    @Test public void readsHttpDateRetryAfter(){
        // Wed, 21 Oct 2015 07:28:00 GMT is 1445412480000 milliseconds.
        assertEquals(120_000,UpdateCheckPolicy.serverDelayMillis(
            "Wed, 21 Oct 2015 07:28:00 GMT",null,null,1_445_412_360_000L));
    }

    @Test public void invalidAndExtremeHeadersAreBounded(){
        assertEquals(60_000,UpdateCheckPolicy.serverDelayMillis("invalid","0","invalid",NOW));
        assertEquals(60_000,UpdateCheckPolicy.serverDelayMillis("-1",null,null,NOW));
        assertEquals(60_000,UpdateCheckPolicy.serverDelayMillis(null,"0","1",NOW));
        assertEquals(UpdateCheckPolicy.MAX_SERVER_BACKOFF_MILLIS,
            UpdateCheckPolicy.serverDelayMillis(Long.toString(Long.MAX_VALUE),null,null,NOW));
        assertEquals(UpdateCheckPolicy.MAX_SERVER_BACKOFF_MILLIS,
            UpdateCheckPolicy.serverDelayMillis(null,"0",Long.toString(Long.MAX_VALUE),NOW));
    }

    @Test public void backwardsClockDoesNotTreatFutureSuccessAsFresh(){
        assertFalse(UpdateCheckPolicy.cacheFresh(NOW+60_000,NOW));
        assertEquals(0,UpdateCheckPolicy.waitMillis(false,NOW,NOW+60_000,0,0));
        assertEquals(UpdateCheckPolicy.AUTO_INTERVAL_MILLIS,
            UpdateCheckPolicy.waitMillis(false,NOW,0,Long.MAX_VALUE,0));
    }
}
