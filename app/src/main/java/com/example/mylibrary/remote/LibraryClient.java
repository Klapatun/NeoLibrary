package com.example.mylibrary.remote;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.mylibrary.BuildConfig;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/**
 * The only HTTP client. Screens never see the host: the base URL and the bearer
 * token come from {@link BuildConfig}. Debug is {@code http://10.0.2.2:8080}
 * with token {@code dev-library-token}. Release leaves both empty and this
 * class does not open a socket.
 *
 * <p>Every request is {@code Authorization: Bearer <token>} and a path under
 * {@code /v1}. Redirects are off, so a response cannot send the next request
 * to another host. Loopback ({@code 127.0.0.1}, {@code localhost}) is refused.
 * A debug build also refuses any endpoint other than {@code http://10.0.2.2:8080}.</p>
 */
public final class LibraryClient {

    private static final String TAG = "LibraryClient";
    /** Catalog. The server prefix is /v1; this client does not call anything else. */
    private static final String PATH_INDEX = "/v1/index";
    /** Emulator alias of the development machine. Debug allows nothing else. */
    private static final String DEBUG_HOST = "10.0.2.2";
    private static final int DEBUG_PORT = 8080;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    /** No check yet in this process. */
    public static final int STATUS_IDLE = 0;
    /** The index request is in flight. */
    public static final int STATUS_CONNECTING = 1;
    /** The server answered 2xx. */
    public static final int STATUS_CONNECTED = 2;
    /** No HTTP response (timeout, reset, refused host). */
    public static final int STATUS_FAILED = 3;
    /** The server answered, and the code was not 2xx. {@code httpCode} is that code. */
    public static final int STATUS_HTTP = 4;

    /** Told on the main thread. The client does not keep the screen. */
    public interface Listener {
        void onConnectionStatus(int status, int httpCode);
    }

    private static volatile int lastStatus = STATUS_IDLE;
    private static volatile int lastHttpCode;
    private static volatile WeakReference<Listener> listenerRef =
            new WeakReference<Listener>(null);

    private LibraryClient() {}

    /** The screen that should show {@link #lastStatus()}. Replaces the previous one. */
    public static void bind(Listener listener) {
        listenerRef = new WeakReference<Listener>(listener);
    }

    public static boolean isConfigured() {
        return configured();
    }

    public static int lastStatus() {
        return lastStatus;
    }

    public static int lastHttpCode() {
        return lastHttpCode;
    }

    /**
     * Cold start of a configured build: {@code GET /v1/index} off the UI thread.
     * Does not write the catalog. Release (empty URL or token) returns immediately.
     * Robolectric shares the debug BuildConfig, so unit tests do not open a socket.
     */
    public static void prefetchIndex() {
        if (!configured() || runningUnderRobolectric()) return;
        deliver(STATUS_CONNECTING, 0);
        new Thread(new Runnable() {
            @Override public void run() {
                URL url = null;
                try {
                    url = endpoint(PATH_INDEX);
                    int code = get(url);
                    if (code >= 200 && code < 300) {
                        Log.i(TAG, "GET " + url + " -> " + code);
                        deliver(STATUS_CONNECTED, code);
                    } else {
                        Log.w(TAG, "GET " + url + " -> " + code);
                        deliver(STATUS_HTTP, code);
                    }
                } catch (IOException e) {
                    Log.w(TAG, "GET " + (url == null ? PATH_INDEX : url.toString()) + " failed", e);
                    deliver(STATUS_FAILED, 0);
                }
            }
        }, "library-index").start();
    }

    private static void deliver(final int status, final int httpCode) {
        lastStatus = status;
        lastHttpCode = httpCode;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyListener();
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                notifyListener();
            }
        });
    }

    private static void notifyListener() {
        Listener listener = listenerRef.get();
        if (listener != null) {
            listener.onConnectionStatus(lastStatus, lastHttpCode);
        }
    }

    private static boolean configured() {
        return text(BuildConfig.API_BASE_URL) && text(BuildConfig.API_TOKEN);
    }

    private static boolean text(String value) {
        return value != null && value.length() > 0;
    }

    /** True when this process is a Robolectric unit test, not an emulator build. */
    private static boolean runningUnderRobolectric() {
        String fp = Build.FINGERPRINT;
        if (fp != null && fp.toLowerCase(Locale.US).indexOf("robolectric") >= 0) {
            return true;
        }
        try {
            Class.forName("org.robolectric.Robolectric");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            // The test class is on the classpath but failed to initialize.
            // Still a unit test: do not open a socket.
            return true;
        }
    }

    /**
     * Blocking {@code GET}. Caller is a background thread. Returns the status
     * code after the body has been discarded.
     */
    private static int get(URL url) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            drain(in);
            return code;
        } finally {
            conn.disconnect();
        }
    }

    /** One connection. {@code url} is already the BuildConfig base plus a {@code /v1} path. */
    private static HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.API_TOKEN);
        return conn;
    }

    private static URL endpoint(String path) throws IOException {
        if (!configured()) {
            throw new IOException("library server is not configured");
        }
        if (path == null || !path.startsWith("/v1/") || path.indexOf("://") >= 0) {
            throw new IOException("library path is not under /v1");
        }
        String base = BuildConfig.API_BASE_URL;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URL url;
        try {
            url = new URL(base + path);
        } catch (MalformedURLException e) {
            throw new IOException("library URL is malformed", e);
        }
        String host = url.getHost();
        if (isLoopback(host)) {
            throw new IOException("library client refuses loopback");
        }
        if (BuildConfig.DEBUG) {
            int port = url.getPort();
            if (!"http".equals(url.getProtocol())
                    || !DEBUG_HOST.equals(host)
                    || port != DEBUG_PORT) {
                throw new IOException("debug library client refuses "
                        + url.getProtocol() + "://" + host + ":" + port);
            }
        }
        return url;
    }

    private static boolean isLoopback(String host) {
        if (host == null || host.length() == 0) return true;
        String h = host.toLowerCase(Locale.US);
        return "localhost".equals(h) || "127.0.0.1".equals(h) || "::1".equals(h);
    }

    /** Read and drop the body so the connection can finish. Does not keep the bytes. */
    private static void drain(InputStream in) throws IOException {
        if (in == null) return;
        byte[] buf = new byte[8192];
        try {
            while (in.read(buf) != -1) {
                // discarded
            }
        } finally {
            in.close();
        }
    }
}
