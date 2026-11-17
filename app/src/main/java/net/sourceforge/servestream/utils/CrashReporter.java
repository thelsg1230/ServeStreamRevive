package net.sourceforge.servestream.utils;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Records the last crash (Java or native) and shows it on the next launch,
 * so it can be copied without adb.
 */
public final class CrashReporter {
    private static final String FILE_NAME = "last_crash.txt";
    private static final String PREFS = "crash_reporter";
    private static final String KEY_LAST_EXIT = "last_exit_timestamp";
    private static final int MAX_CHARS = 9000;

    private CrashReporter() {
    }

    public static void install(final Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread thread, Throwable ex) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    pw.println("Java crash in thread " + thread.getName());
                    ex.printStackTrace(pw);
                    pw.flush();
                    FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), FILE_NAME));
                    out.write(sw.toString().getBytes("UTF-8"));
                    out.close();
                } catch (Throwable ignored) {
                }
                if (previous != null) {
                    previous.uncaughtException(thread, ex);
                }
            }
        });
    }

    public static void showIfAny(final Activity activity) {
        String report = null;
        try {
            report = readJavaCrash(activity);
            String exit = readExitInfo(activity);
            if (report == null && exit != null) {
                report = exit + "\n\n" + readLogcat();
            }
        } catch (Throwable ignored) {
        }

        if (report == null || report.length() == 0) {
            return;
        }

        if (report.length() > MAX_CHARS) {
            report = report.substring(0, MAX_CHARS) + "\n...";
        }

        final String text = report;
        new AlertDialog.Builder(activity)
                .setTitle("Last crash report")
                .setMessage(text)
                .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("crash", text));
                            Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static String readJavaCrash(Context context) throws Exception {
        File f = new File(context.getFilesDir(), FILE_NAME);
        if (!f.exists()) {
            return null;
        }
        String s = new String(readAll(new FileInputStream(f), 64 * 1024), "UTF-8");
        f.delete();
        return s;
    }

    /** Uses ApplicationExitInfo (Android 11+) through reflection to report native crashes. */
    private static String readExitInfo(Context context) throws Exception {
        if (Build.VERSION.SDK_INT < 30) {
            return null;
        }
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        Method m = ActivityManager.class.getMethod("getHistoricalProcessExitReasons", String.class, int.class, int.class);
        List<?> list = (List<?>) m.invoke(am, null, 0, 5);
        if (list == null || list.isEmpty()) {
            return null;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastShown = prefs.getLong(KEY_LAST_EXIT, 0);
        long newest = lastShown;
        String result = null;

        for (Object info : list) {
            Class<?> c = info.getClass();
            long ts = (Long) c.getMethod("getTimestamp").invoke(info);
            int reason = (Integer) c.getMethod("getReason").invoke(info);
            if (ts > newest) {
                newest = ts;
            }
            // 4 = CRASH, 5 = CRASH_NATIVE, 6 = ANR
            if (ts <= lastShown || result != null || (reason != 4 && reason != 5 && reason != 6)) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(reason == 5 ? "Native crash" : reason == 6 ? "ANR" : "Java crash");
            sb.append(" in ").append(c.getMethod("getProcessName").invoke(info));
            Object desc = c.getMethod("getDescription").invoke(info);
            if (desc != null) {
                sb.append("\n").append(desc);
            }
            if (reason == 5 || reason == 6) {
                InputStream in = (InputStream) c.getMethod("getTraceInputStream").invoke(info);
                if (in != null) {
                    sb.append("\n").append(extractTrace(new String(readAll(in, 512 * 1024), "UTF-8")));
                }
            }
            result = sb.toString();
        }

        prefs.edit().putLong(KEY_LAST_EXIT, newest).apply();
        return result;
    }

    /** Keeps the useful head of a tombstone: signal, abort message and backtrace. */
    private static String extractTrace(String t) {
        StringBuilder sb = new StringBuilder();
        String[] lines = t.split("\n");
        int count = 0;
        boolean inBacktrace = false;
        for (String line : lines) {
            String l = line.trim();
            if (l.startsWith("signal") || l.startsWith("Abort message") || l.startsWith("Cause")
                    || l.startsWith("Cmdline") || l.startsWith("pid:")) {
                sb.append(l).append("\n");
            } else if (l.startsWith("backtrace:")) {
                inBacktrace = true;
                sb.append(l).append("\n");
            } else if (inBacktrace) {
                if (l.length() == 0 || !l.startsWith("#")) {
                    if (count > 0) {
                        break;
                    }
                    continue;
                }
                sb.append(l).append("\n");
                if (++count >= 30) {
                    break;
                }
            }
        }
        if (sb.length() == 0) {
            return t.length() > MAX_CHARS ? t.substring(0, MAX_CHARS) : t;
        }
        return sb.toString();
    }

    /** An app can read its own (same uid) log lines, including those of its crashed process. */
    private static String readLogcat() {
        StringBuilder sb = new StringBuilder();
        String[][] cmds = {
                {"logcat", "-d", "-b", "crash", "-t", "150"},
                {"logcat", "-d", "-t", "400", "*:W"}
        };
        for (String[] cmd : cmds) {
            try {
                Process p = Runtime.getRuntime().exec(cmd);
                String out = new String(readAll(p.getInputStream(), 256 * 1024), "UTF-8");
                p.destroy();
                if (out.trim().length() > 0) {
                    sb.append(cmd.length == 6 ? "--- crash log ---\n" : "--- recent warnings ---\n");
                    sb.append(lastChars(out, 2500)).append("\n");
                }
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    private static String lastChars(String s, int n) {
        return s.length() > n ? s.substring(s.length() - n) : s;
    }

    private static byte[] readAll(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < limit) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }
}
