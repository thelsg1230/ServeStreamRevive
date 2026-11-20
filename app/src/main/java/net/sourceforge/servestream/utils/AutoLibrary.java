package net.sourceforge.servestream.utils;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;

import net.sourceforge.servestream.R;
import net.sourceforge.servestream.bean.UriBean;
import net.sourceforge.servestream.database.StreamDatabase;
import net.sourceforge.servestream.provider.StationImageProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Saved stations as a media library for Android Auto and other media browsers
 * (car head units, assistants, watches).
 */
public final class AutoLibrary {
    public static final String ROOT_ID = "root";
    private static final String STATION_PREFIX = "station:";

    // Android Auto content style hints
    private static final String CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED";
    private static final String CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    private static final int CONTENT_STYLE_LIST = 1;
    private static final String SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED";

    private AutoLibrary() {
    }

    public static Bundle rootExtras() {
        Bundle extras = new Bundle();
        extras.putBoolean(CONTENT_STYLE_SUPPORTED, true);
        extras.putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST);
        extras.putBoolean(SEARCH_SUPPORTED, true);
        return extras;
    }

    public static String mediaIdFor(long stationId) {
        return STATION_PREFIX + stationId;
    }

    public static Uri iconFor(Context c, long stationId) {
        if (StationImages.has(c, stationId)) {
            return StationImageProvider.uriFor(c, stationId);
        }
        return Uri.parse("android.resource://" + c.getPackageName() + "/" + R.drawable.app_logo);
    }

    /** Saved stations in the same order as the app's list. Call off the main thread. */
    public static List<MediaItem> stations(Context c) {
        List<MediaItem> items = new ArrayList<MediaItem>();
        for (UriBean bean : load(c)) {
            MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                    .setMediaId(mediaIdFor(bean.getId()))
                    .setTitle(title(bean))
                    .setSubtitle(bean.getUri() != null ? bean.getUri().getHost() : null)
                    .setIconUri(iconFor(c, bean.getId()))
                    .build();
            items.add(new MediaItem(desc, MediaItem.FLAG_PLAYABLE));
        }
        return items;
    }

    public static String title(UriBean bean) {
        String name = bean.getNickname();
        if (name == null || name.trim().length() == 0) {
            name = bean.getUri() != null ? bean.getUri().toString() : "";
        }
        return name;
    }

    public static UriBean findByMediaId(Context c, String mediaId) {
        if (mediaId == null || !mediaId.startsWith(STATION_PREFIX)) {
            return null;
        }
        long id;
        try {
            id = Long.parseLong(mediaId.substring(STATION_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
        for (UriBean bean : load(c)) {
            if (bean.getId() == id) {
                return bean;
            }
        }
        return null;
    }

    /**
     * Voice search ("play K-Radio on ServeStream"): best station name match.
     * An empty query means "play something": the most recently used station.
     */
    public static UriBean search(Context c, String query) {
        List<UriBean> all = load(c);
        if (all.isEmpty()) {
            return null;
        }
        String q = normalize(query);
        if (q.length() == 0) {
            UriBean recent = all.get(0);
            for (UriBean bean : all) {
                if (bean.getLastConnect() > recent.getLastConnect()) {
                    recent = bean;
                }
            }
            return recent;
        }
        UriBean best = null;
        int bestScore = 0;
        for (UriBean bean : all) {
            String name = normalize(title(bean));
            int score = 0;
            if (name.equals(q)) {
                score = 3;
            } else if (name.startsWith(q)) {
                score = 2;
            } else if (name.contains(q) || q.contains(name)) {
                score = 1;
            }
            if (score > bestScore) {
                best = bean;
                bestScore = score;
            }
        }
        return best;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]", "");
    }

    private static List<UriBean> load(Context c) {
        StreamDatabase db = new StreamDatabase(c);
        try {
            return db.getUris();
        } finally {
            db.close();
        }
    }
}
