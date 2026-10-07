package android.util;

/**
 * JVM unit-test stub for [android.util.Log].
 *
 * The mockable android.jar used by local unit tests throws
 * `RuntimeException("Method ... not mocked")` for every Log call, which makes
 * any production path that logs (e.g. RagManager ingestion, the
 * SequentialImportQueue failure path) impossible to exercise in a fast JVM
 * test. This no-op stub shadows that jar on the unit-test classpath. It has no
 * effect on device/instrumented builds.
 */
public final class Log {
    private Log() {}

    public static int v(String tag, String msg) { return 0; }
    public static int v(String tag, String msg, Throwable tr) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int d(String tag, String msg, Throwable tr) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int i(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, Throwable tr) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable tr) { return 0; }
    public static int wtf(String tag, String msg) { return 0; }
    public static int wtf(String tag, String msg, Throwable tr) { return 0; }
    public static int wtf(String tag, Throwable tr) { return 0; }
    public static String getStackTraceString(Throwable tr) { return ""; }
    public static boolean isLoggable(String tag, int level) { return false; }
    public static int println(int priority, String tag, String msg) { return 0; }
}
