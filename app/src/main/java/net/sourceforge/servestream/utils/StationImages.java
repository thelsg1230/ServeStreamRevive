package net.sourceforge.servestream.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import androidx.collection.LruCache;

import net.sourceforge.servestream.bean.UriBean;
import net.sourceforge.servestream.database.StreamDatabase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * User-chosen images for saved stations. Stored as JPEG files in the app's
 * private storage, named after the station's database id.
 */
public final class StationImages {
    private static final String TAG = "StationImages";
    private static final String DIR = "station_images";
    private static final int MAX_SIZE = 1024;

    private static final LruCache<Long, Bitmap> sCache = new LruCache<Long, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private StationImages() {
    }

    public static File file(Context c, long stationId) {
        return new File(new File(c.getFilesDir(), DIR), stationId + ".jpg");
    }

    public static boolean has(Context c, long stationId) {
        return file(c, stationId).exists();
    }

    /** Copies and downsizes the picked image. Call off the main thread. */
    public static boolean save(Context c, long stationId, Uri source) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(source);
            BitmapFactory.decodeStream(in, null, bounds);
            if (in != null) in.close();

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= MAX_SIZE || bounds.outHeight / (sample * 2) >= MAX_SIZE) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = c.getContentResolver().openInputStream(source);
            Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
            if (in != null) in.close();
            if (bmp == null) {
                return false;
            }

            File f = file(c, stationId);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return false;
            }
            FileOutputStream out = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.close();
            sCache.remove(stationId);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not save station image", e);
            return false;
        }
    }

    /** Raw JPEG bytes of a station's image, or null if it has none (used by backup). */
    public static byte[] readBytes(Context c, long stationId) {
        File f = file(c, stationId);
        if (!f.exists()) {
            return null;
        }
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream((int) f.length());
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            in.close();
            return buf.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "could not read station image", e);
            return null;
        }
    }

    /** Stores image bytes taken from a backup; rejects anything that is not a decodable image. */
    public static boolean saveBytes(Context c, long stationId, byte[] data) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return false;
            }
            File f = file(c, stationId);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return false;
            }
            FileOutputStream out = new FileOutputStream(f);
            out.write(data);
            out.close();
            sCache.remove(stationId);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not restore station image", e);
            return false;
        }
    }

    /** Removes pictures whose station no longer exists (left by older versions). Call off the main thread. */
    public static void removeOrphans(Context c) {
        File[] files = new File(c.getFilesDir(), DIR).listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        java.util.Set<String> keep = new java.util.HashSet<String>();
        StreamDatabase db = new StreamDatabase(c);
        try {
            for (UriBean bean : db.getUris()) {
                keep.add(bean.getId() + ".jpg");
            }
        } finally {
            db.close();
        }
        for (File f : files) {
            if (!keep.contains(f.getName())) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                try {
                    sCache.remove(Long.parseLong(f.getName().replace(".jpg", "")));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    public static void delete(Context c, long stationId) {
        //noinspection ResultOfMethodCallIgnored
        file(c, stationId).delete();
        sCache.remove(stationId);
    }

    public static Bitmap load(Context c, long stationId) {
        Bitmap cached = sCache.get(stationId);
        if (cached != null) {
            return cached;
        }
        File f = file(c, stationId);
        if (!f.exists()) {
            return null;
        }
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (bmp != null) {
            sCache.put(stationId, bmp);
        }
        return bmp;
    }

    /** Image of the saved station that plays this stream address, or null. */
    public static Bitmap forStream(Context c, String streamUri) {
        UriBean bean = findStation(c, streamUri);
        return bean == null ? null : load(c, bean.getId());
    }

    public static UriBean findStation(Context c, String streamUri) {
        if (streamUri == null) {
            return null;
        }
        StreamDatabase db = null;
        try {
            db = new StreamDatabase(c);
            Uri target = Uri.parse(streamUri);
            for (UriBean bean : db.getUris()) {
                if (sameStream(bean.getUri(), target)) {
                    return bean;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "station lookup failed", e);
        } finally {
            if (db != null) db.close();
        }
        return null;
    }

    private static int effectivePort(Uri u) {
        int port = u.getPort();
        if (port != -1) return port;
        String s = u.getScheme();
        if ("https".equalsIgnoreCase(s)) return 443;
        if ("http".equalsIgnoreCase(s)) return 80;
        return -1;
    }

    private static String normPath(String p) {
        return (p == null || p.length() == 0) ? "/" : p;
    }

    public static boolean sameStream(Uri a, Uri b) {
        if (a == null || b == null) return false;
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b)
                && normPath(a.getPath()).equals(normPath(b.getPath()))
                && String.valueOf(a.getQuery()).equals(String.valueOf(b.getQuery()));
    }
}
