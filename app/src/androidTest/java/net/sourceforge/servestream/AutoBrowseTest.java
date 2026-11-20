package net.sourceforge.servestream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import net.sourceforge.servestream.bean.UriBean;
import net.sourceforge.servestream.database.StreamDatabase;
import net.sourceforge.servestream.service.MediaPlaybackService;
import net.sourceforge.servestream.transport.TransportFactory;
import net.sourceforge.servestream.utils.AutoLibrary;
import net.sourceforge.servestream.utils.StationImages;

import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Connects to the playback service exactly like Android Auto does (MediaBrowser +
 * MediaController) and checks the station list, station artwork and playback.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AutoBrowseTest {
    private static final String STREAM = "https://stream1.rcast.net/73730";
    private static final String NAME = "Auto Test Radio";

    private Context ctx;
    private UriBean station;
    private MediaBrowserCompat browser;
    private MediaControllerCompat controller;

    @Before
    public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        StreamDatabase db = new StreamDatabase(ctx);
        station = TransportFactory.findUri(db, Uri.parse(STREAM));
        if (station == null) {
            station = TransportFactory.getTransport("https").createUri(Uri.parse(STREAM));
            station.setNickname(NAME);
            station = db.saveUri(station);
        }
        db.close();

        final CountDownLatch connected = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                browser = new MediaBrowserCompat(ctx, new ComponentName(ctx, MediaPlaybackService.class),
                        new MediaBrowserCompat.ConnectionCallback() {
                            @Override
                            public void onConnected() {
                                connected.countDown();
                            }
                        }, null);
                browser.connect();
            }
        });
        assertTrue("media browser did not connect", connected.await(30, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                controller = new MediaControllerCompat(ctx, browser.getSessionToken());
            }
        });
    }

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                if (browser != null) browser.disconnect();
            }
        });
    }

    private List<MediaBrowserCompat.MediaItem> loadRoot() throws Exception {
        final String root = browser.getRoot();
        final List<MediaBrowserCompat.MediaItem> out = new ArrayList<MediaBrowserCompat.MediaItem>();
        final CountDownLatch loaded = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                browser.subscribe(root, new MediaBrowserCompat.SubscriptionCallback() {
                    @Override
                    public void onChildrenLoaded(String parentId, List<MediaBrowserCompat.MediaItem> children) {
                        out.addAll(children);
                        loaded.countDown();
                    }
                });
            }
        });
        assertTrue("station list did not load", loaded.await(30, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                browser.unsubscribe(root);
            }
        });
        return out;
    }

    private MediaBrowserCompat.MediaItem findStation(List<MediaBrowserCompat.MediaItem> items) {
        for (MediaBrowserCompat.MediaItem item : items) {
            if (AutoLibrary.mediaIdFor(station.getId()).equals(item.getMediaId())) {
                return item;
            }
        }
        return null;
    }

    private void waitForState(int wanted, long timeoutMs) {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        int last = -1;
        while (SystemClock.elapsedRealtime() < end) {
            PlaybackStateCompat s = controller.getPlaybackState();
            last = s == null ? -1 : s.getState();
            if (last == wanted) return;
            SystemClock.sleep(500);
        }
        fail("playback state " + last + ", wanted " + wanted);
    }

    private void pauseIfPlaying() {
        PlaybackStateCompat s = controller.getPlaybackState();
        if (s != null && s.getState() == PlaybackStateCompat.STATE_PLAYING) {
            controller.getTransportControls().pause();
            waitForState(PlaybackStateCompat.STATE_PAUSED, 15000);
        }
    }

    @Test
    public void a_stationListShowsSavedStationsWithImages() throws Exception {
        // give the station a picture and check the car can open it
        Bitmap bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.BLUE);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, jpeg);
        assertTrue(StationImages.saveBytes(ctx, station.getId(), jpeg.toByteArray()));
        try {
            MediaBrowserCompat.MediaItem item = findStation(loadRoot());
            assertNotNull("saved station missing from the Android Auto list", item);
            assertTrue(item.isPlayable());
            assertEquals(AutoLibrary.title(station), String.valueOf(item.getDescription().getTitle()));
            Uri icon = item.getDescription().getIconUri();
            assertNotNull(icon);
            assertEquals("content", icon.getScheme());
            InputStream in = ctx.getContentResolver().openInputStream(icon);
            Bitmap back = BitmapFactory.decodeStream(in);
            in.close();
            assertNotNull("station image not readable through the provider", back);
            assertEquals(64, back.getWidth());
        } finally {
            StationImages.delete(ctx, station.getId());
        }
    }

    @Test
    public void b_playFromStationList() {
        pauseIfPlaying();
        controller.getTransportControls().playFromMediaId(AutoLibrary.mediaIdFor(station.getId()), null);
        waitForState(PlaybackStateCompat.STATE_PLAYING, 60000);
        // the car highlights the playing station by its media id
        String wanted = AutoLibrary.mediaIdFor(station.getId());
        String got = null;
        long end = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < end) {
            MediaMetadataCompat meta = controller.getMetadata();
            got = meta == null ? null : meta.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
            if (wanted.equals(got)) break;
            SystemClock.sleep(500);
        }
        assertEquals(wanted, got);
    }

    @Test
    public void c_playFromVoiceSearch() {
        pauseIfPlaying();
        // part of the station name, different case and spacing
        String name = AutoLibrary.title(station);
        String query = name.substring(0, Math.min(name.length(), 6)).toUpperCase();
        controller.getTransportControls().playFromSearch(query, null);
        waitForState(PlaybackStateCompat.STATE_PLAYING, 60000);
    }
}
