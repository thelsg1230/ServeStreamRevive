package net.sourceforge.servestream.transport;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Opens an HTTP(S) connection and follows redirects by hand. The platform
 * does not follow redirects that switch between http and https, which
 * leaves callers looking at the redirect page instead of the stream.
 */
final class HttpRedirectHelper {
    private static final int MAX_REDIRECTS = 5;

    interface Configurator {
        void configure(HttpURLConnection connection);
    }

    private HttpRedirectHelper() {
    }

    static HttpURLConnection open(URL start, Configurator configurator) throws IOException {
        URL current = start;

        for (int redirects = 0; ; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "ServeStream");
            if (configurator != null) {
                configurator.configure(connection);
            }

            int code = connection.getResponseCode();
            if (isRedirect(code) && redirects < MAX_REDIRECTS) {
                String location = connection.getHeaderField("Location");
                if (location != null) {
                    URL next = new URL(current, location);
                    connection.disconnect();
                    current = next;
                    continue;
                }
            }

            return connection;
        }
    }

    private static boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307
                || code == 308;
    }
}
