package tv.retwitch.mod;

/**
 * JVM self-check for {@link HomeOrientation#mapOutsideTheatre}. From kitchen/:
 *   CP=/opt/android-sdk/platforms/android-36/android.jar:libs/okhttp-4.12.0.jar:libs/okio-jvm-3.6.0.jar:libs/kotlin-stdlib-2.0.0.jar:libs/appmetrica-analytics-8.6.0.jar
 *   javac -cp $CP -d work/selfcheck mod/src/tv/retwitch/mod/*.java mod/src/tv/retwitch/mod/ads/*.java \
 *       mod/src/tv/retwitch/mod/emotes/*.java mod/test/tv/retwitch/mod/HomeOrientationSelfCheck.java
 *   java -ea -cp work/selfcheck:$CP tv.retwitch.mod.HomeOrientationSelfCheck
 */
public final class HomeOrientationSelfCheck {
    public static void main(String[] args) {
        int portrait = 7, user = 2, unspecified = -1, landscape = 6, locked = 14;
        // Auto-rotate: portrait locks become USER, the rest passes.
        check(HomeOrientation.mapOutsideTheatre(1, portrait) == user, "auto: portrait -> USER");
        check(HomeOrientation.mapOutsideTheatre(1, 1) == user, "auto: PORTRAIT -> USER");
        check(HomeOrientation.mapOutsideTheatre(1, unspecified) == unspecified, "auto: UNSPECIFIED passes");
        check(HomeOrientation.mapOutsideTheatre(1, landscape) == landscape, "auto: landscape passes");
        // Keep: Back from the theatre asks USER, UNSPECIFIED, SENSOR_PORTRAIT - all of them would turn it portrait.
        check(HomeOrientation.mapOutsideTheatre(2, user) == locked, "keep: USER -> LOCKED");
        check(HomeOrientation.mapOutsideTheatre(2, unspecified) == locked, "keep: UNSPECIFIED -> LOCKED");
        check(HomeOrientation.mapOutsideTheatre(2, portrait) == locked, "keep: portrait -> LOCKED");
        check(HomeOrientation.mapOutsideTheatre(2, landscape) == landscape, "keep: landscape passes");
        System.out.println("HomeOrientationSelfCheck OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
