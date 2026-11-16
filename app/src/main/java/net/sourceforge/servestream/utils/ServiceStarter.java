package net.sourceforge.servestream.utils;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts a service without crashing the app when the platform refuses the
 * start (Android 8+ background execution limits, Android 12+ background
 * start restrictions).
 */
public final class ServiceStarter {
    private static final String TAG = ServiceStarter.class.getSimpleName();

    private ServiceStarter() {
    }

    public static boolean startServiceSafely(Context context, Intent intent) {
        try {
            context.startService(intent);
            return true;
        } catch (IllegalStateException e) {
            Log.w(TAG, "Service start not allowed from background", e);
        } catch (SecurityException e) {
            Log.w(TAG, "Service start refused", e);
        }
        return false;
    }
}
