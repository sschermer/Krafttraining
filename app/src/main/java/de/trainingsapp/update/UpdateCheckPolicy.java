package de.trainingsapp.update;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/** Timing policy kept independent of Android so rate-limit behavior is testable. */
final class UpdateCheckPolicy {
    static final long AUTO_INTERVAL_MILLIS=15*60_000L;
    static final long MIN_BACKOFF_MILLIS=60_000L;
    static final long MAX_SERVER_BACKOFF_MILLIS=24*60*60_000L;
    private UpdateCheckPolicy(){}

    static boolean cacheFresh(long checkedAt,long now){
        return checkedAt>0&&now>=checkedAt&&now-checkedAt<AUTO_INTERVAL_MILLIS;
    }

    static long waitMillis(boolean manual,long now,long lastSuccessAt,
                           long automaticRetryAt,long serverRetryAt){
        long serverWait=remaining(serverRetryAt,now,MAX_SERVER_BACKOFF_MILLIS);
        if(manual)return serverWait;
        long intervalWait=cacheFresh(lastSuccessAt,now)?
            AUTO_INTERVAL_MILLIS-(now-lastSuccessAt):0;
        return Math.max(serverWait,Math.max(intervalWait,
            remaining(automaticRetryAt,now,AUTO_INTERVAL_MILLIS)));
    }

    static int nextFailureCount(int previous){
        return previous>=5?5:Math.max(0,previous)+1;
    }

    static long failureDelayMillis(int failureCount){
        int exponent=Math.max(0,Math.min(4,failureCount-1));
        return Math.min(AUTO_INTERVAL_MILLIS,MIN_BACKOFF_MILLIS*(1L<<exponent));
    }

    static long serverDelayMillis(String retryAfter,String remaining,
                                  String resetEpochSeconds,long now){
        long delay=MIN_BACKOFF_MILLIS;
        if(retryAfter!=null){
            try{
                long seconds=Long.parseLong(retryAfter.trim());
                if(seconds>=0)delay=Math.max(delay,
                    Math.min(seconds,MAX_SERVER_BACKOFF_MILLIS/1000)*1000);
            }catch(NumberFormatException ignored){
                SimpleDateFormat date=new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US);
                date.setTimeZone(TimeZone.getTimeZone("GMT"));date.setLenient(false);
                try{
                    delay=Math.max(delay,remaining(date.parse(retryAfter).getTime(),now,
                        MAX_SERVER_BACKOFF_MILLIS));
                }catch(ParseException ignoredDate){}
            }
        }
        // A reset header also appears on ordinary access-denied responses.
        // Use it only when GitHub actually reports an exhausted primary limit.
        if("0".equals(remaining)&&resetEpochSeconds!=null){
            try{
                long seconds=Long.parseLong(resetEpochSeconds.trim());
                long nowSeconds=now/1000;
                if(seconds>nowSeconds){
                    long extra=seconds-nowSeconds;
                    delay=Math.max(delay,Math.min(extra,MAX_SERVER_BACKOFF_MILLIS/1000)*1000);
                }
            }catch(NumberFormatException ignored){}
        }
        return Math.min(delay,MAX_SERVER_BACKOFF_MILLIS);
    }

    private static long remaining(long until,long now,long cap){
        if(until<=now)return 0;
        // Persisted timestamps may be stale after the device clock changes.
        long difference=until-now;
        return difference<0?cap:Math.min(difference,cap);
    }
}
