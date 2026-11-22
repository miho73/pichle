package ooo.ionya.driveaway;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small menu-photo loader: https only, brand image hosts only, size-capped, downsampled and
 * memory-cached. Failures are silent (the card simply keeps its placeholder).
 */
public final class ImageLoader implements AutoCloseable {
    public interface Callback { void onLoaded(Bitmap bitmap); }

    private static final int MAX_BYTES = 3 * 1024 * 1024, TARGET_WIDTH = 480;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean closed;

    static boolean allowed(String url) {
        return url != null && (url.startsWith("https://image.istarbucks.co.kr/")
                || url.startsWith("https://www.mcdonalds.co.kr/"));
    }

    /** Calls back on a worker thread (or immediately from cache); the caller posts to the UI. */
    public void load(String url, Callback callback) {
        if (!allowed(url) || closed) return;
        Bitmap hit = cache.get(url);
        if (hit != null) { callback.onLoaded(hit); return; }
        try {
            pool.execute(() -> {
                try {
                    Bitmap bitmap = download(url);
                    if (bitmap != null && !closed) { cache.put(url, bitmap); callback.onLoaded(bitmap); }
                } catch (IOException | RuntimeException ignored) { }
            });
        } catch (RuntimeException ignored) { }
    }

    private Bitmap download(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(12_000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) return null;
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return null;
            byte[] data;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (closed) return null;
                    if (out.size() + count > MAX_BYTES) return null;
                    out.write(buffer, 0, count);
                }
                data = out.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2;
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } finally {
            connection.disconnect();
        }
    }

    @Override public void close() { closed = true; pool.shutdownNow(); cache.evictAll(); }
}
