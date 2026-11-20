package net.sourceforge.servestream.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import net.sourceforge.servestream.utils.StationImages;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Read-only access to station images for media browsers such as Android Auto,
 * which load artwork in their own process and need a content:// URI.
 * URI form: content://&lt;package&gt;.stationimages/&lt;station id&gt;
 */
public class StationImageProvider extends ContentProvider {

    public static Uri uriFor(Context c, long stationId) {
        return new Uri.Builder().scheme("content")
                .authority(c.getPackageName() + ".stationimages")
                .appendPath(String.valueOf(stationId)).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("read only");
        }
        List<String> segments = uri.getPathSegments();
        long id;
        try {
            id = Long.parseLong(segments.size() == 1 ? segments.get(0) : "");
        } catch (NumberFormatException e) {
            throw new FileNotFoundException("bad station id");
        }
        File f = StationImages.file(getContext(), id);
        if (!f.exists()) {
            throw new FileNotFoundException("no image");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
