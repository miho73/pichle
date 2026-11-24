package ooo.ionya.driveaway;

import android.content.Context;
import android.security.NetworkSecurityPolicy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Reads brand menus through the Pichle server. Cache lifetime belongs to the server. */
public final class BrandMenuFetcher implements AutoCloseable {
    public static final class Entry {
        public final String section, name, detail;
        public final boolean drink;
        public final String imageUrl;
        Entry(String section, String name, String detail, boolean drink, String imageUrl) {
            this.section = section; this.name = name; this.detail = detail;
            this.drink = drink; this.imageUrl = imageUrl;
        }
    }

    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ITEMS = 2000;
    private final DillOrderClient server;
    private volatile HttpURLConnection active;
    private volatile boolean closed;

    public BrandMenuFetcher(Context context) { server = new DillOrderClient(context); }

    public static boolean supports(String storeName) { return brand(storeName) != null; }

    private static String brand(String storeName) {
        if (storeName == null) return null;
        if (storeName.contains("스타벅스")) return "starbucks";
        if (storeName.contains("맥도날드")) return "mcdonalds";
        return null;
    }

    /** Blocking worker-thread call; every load asks our server rather than a local menu cache. */
    public List<Entry> load(String storeName) throws IOException {
        ensureOpen();
        String brand = brand(storeName);
        if (brand == null) return Collections.emptyList();
        String baseUrl = server.getBaseUrl();
        URI address = URI.create(baseUrl);
        if ("http".equalsIgnoreCase(address.getScheme())
                && !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(address.getHost()))
            throw new MenuException("이 주소는 HTTPS로 연결해주세요.");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + "/v1/menus/" + brand).openConnection();
            active = connection;
            ensureOpen();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-API-Key", DillOrderClient.APP_API_KEY);
            int status = connection.getResponseCode();
            if (status != 200) throw statusError(status);
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("application/json"))
                throw formatError();
            String body;
            try (InputStream stream = connection.getInputStream()) { body = readResponse(stream); }
            ensureOpen();
            return parse(body, brand);
        } catch (SocketTimeoutException error) {
            throw new MenuException("메뉴를 불러오는 데 시간이 걸리고 있습니다. 잠시 후 다시 시도해주세요.", error);
        } catch (IOException error) {
            if (error instanceof MenuException) throw error;
            ensureOpen();
            throw new MenuException("메뉴를 불러오지 못했습니다. 서버 연결을 확인해주세요.", error);
        } finally {
            if (connection != null) connection.disconnect();
            if (active == connection) active = null;
        }
    }

    private List<Entry> parse(String body, String brand) throws IOException {
        try {
            JSONObject root = new JSONObject(body);
            if (!brand.equals(requiredText(root, "brand", 40, false))) throw formatError();
            Object rawItems = root.opt("items");
            if (!(rawItems instanceof JSONArray)) throw formatError();
            JSONArray items = (JSONArray) rawItems;
            if (items.length() == 0) throw new MenuException("현재 표시할 메뉴가 없습니다.");
            if (items.length() > MAX_ITEMS) throw formatError();
            List<Entry> entries = new ArrayList<>(items.length());
            for (int i = 0; i < items.length(); i++) {
                ensureOpen();
                Object value = items.opt(i);
                if (!(value instanceof JSONObject)) throw formatError();
                JSONObject item = (JSONObject) value;
                String section = requiredText(item, "section", 100, false);
                String name = requiredText(item, "name", 300, false);
                String detail = requiredText(item, "detail", 2000, true);
                String image = requiredText(item, "imageUrl", 4096, true);
                Object drink = item.opt("drink");
                if (!(drink instanceof Boolean)) throw formatError();
                entries.add(new Entry(section, name, detail, (Boolean) drink,
                        ImageLoader.allowed(image) ? image : ""));
            }
            return Collections.unmodifiableList(entries);
        } catch (JSONException error) {
            throw new MenuException("메뉴 응답을 읽지 못했습니다. 잠시 후 다시 시도해주세요.", error);
        }
    }

    private static String requiredText(JSONObject object, String key, int maxLength, boolean emptyAllowed)
            throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof String)) throw formatError();
        String text = ((String) value).trim();
        if (text.length() > maxLength || (!emptyAllowed && text.isEmpty())) throw formatError();
        return text;
    }

    private String readResponse(InputStream stream) throws IOException {
        if (stream == null) throw formatError();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                ensureOpen();
                if (output.size() + count > MAX_BYTES) throw formatError();
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void ensureOpen() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new MenuException("메뉴 조회가 취소되었습니다.");
    }

    private static IOException statusError(int status) {
        if (status == 401 || status == 403) return new MenuException("메뉴 조회 권한을 확인해주세요.");
        if (status == 404 || (status >= 300 && status < 400))
            return new MenuException("주문 서버의 메뉴 주소를 확인해주세요.");
        if (status == 429) return new MenuException("메뉴 조회가 몰리고 있습니다. 잠시 후 다시 시도해주세요.");
        return new MenuException("메뉴를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.");
    }

    private static IOException formatError() {
        return new MenuException("메뉴 응답을 읽지 못했습니다. 잠시 후 다시 시도해주세요.");
    }

    @Override public void close() {
        closed = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }

    private static final class MenuException extends IOException {
        MenuException(String message) { super(message); }
        MenuException(String message, Throwable cause) { super(message, cause); }
    }
}
