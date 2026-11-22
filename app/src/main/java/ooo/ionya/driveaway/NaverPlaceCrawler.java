package ooo.ionya.driveaway;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads public mobile-map SSR JSON without executing scripts. Call on a worker thread. */
public final class NaverPlaceCrawler implements AutoCloseable {
    private static final String ENDPOINT = "https://m.map.naver.com/search?sm=hty&style=v5&query=";
    private static final Pattern SCRIPT = Pattern.compile("<script\\b[^>]*>(.*?)</script\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final String STATE = "window.__RQ_STREAMING_STATE__.push(";
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final long TTL_NANOS = 5L * 60 * 1_000_000_000;
    private final PageFetcher fetcher;
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private volatile HttpURLConnection active;
    private volatile boolean closed;

    interface PageFetcher { String fetch(String query) throws IOException; }
    public NaverPlaceCrawler() { fetcher = this::download; }
    NaverPlaceCrawler(PageFetcher fetcher) { this.fetcher = fetcher; }

    public Result search(String input) throws IOException {
        String query = input == null ? "" : input.trim().replaceAll("\\s+", " ");
        if (query.isEmpty() || query.length() > 100) throw new IOException("검색어를 1~100자로 입력해 주세요.");
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("검색이 취소되었습니다.");
        Cached cached = cache.get(query);
        if (cached != null && System.nanoTime() - cached.created < TTL_NANOS) return cached.result;
        Result result = parse(query, fetcher.fetch(query));
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("검색이 취소되었습니다.");
        if (cache.size() >= 8) cache.remove(cache.keySet().iterator().next());
        cache.put(query, new Cached(result));
        return result;
    }

    private String download(String query) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT
                + URLEncoder.encode(query, StandardCharsets.UTF_8.name())).openConnection();
        active = connection;
        try {
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("검색이 취소되었습니다.");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
            connection.setRequestProperty("Accept", "text/html");
            connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9");
            int status = connection.getResponseCode();
            if (status == 403 || status == 429) throw new IOException("네이버 지도가 접속을 제한했습니다. 잠시 후 다시 검색하거나 네이버 지도에서 확인해 주세요.");
            if (status != 200) throw new IOException("네이버 지도에 연결하지 못했습니다. (HTTP " + status + ")");
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("text/html")) throw formatError();
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                long started = System.nanoTime();
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (closed || Thread.currentThread().isInterrupted()) throw new IOException("검색이 취소되었습니다.");
                    if (output.size() + count > MAX_BYTES || System.nanoTime() - started > 25_000_000_000L) throw new IOException("검색 응답이 너무 크거나 지연되고 있습니다. 검색어를 구체적으로 입력해 주세요.");
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
            if (active == connection) active = null;
        }
    }

    static Result parse(String query, String html) throws IOException {
        Matcher scripts = SCRIPT.matcher(html);
        while (scripts.find()) {
            String script = scripts.group(1);
            int start = script.indexOf(STATE);
            if (start < 0) continue;
            try {
                Object decoded = new JSONTokener(script.substring(start + STATE.length())).nextValue();
                if (!(decoded instanceof JSONObject)) continue;
                JSONArray queries = ((JSONObject) decoded).optJSONArray("queries");
                if (queries == null) continue;
                for (int i = 0; i < queries.length(); i++) {
                    JSONObject entry = queries.optJSONObject(i);
                    if (entry == null) continue;
                    JSONArray key = entry.optJSONArray("queryKey");
                    if (key == null || !"allSearchSuspense".equals(key.optString(1))) continue;
                    JSONObject state = entry.optJSONObject("state");
                    if (state == null || !"success".equals(state.optString("status"))) throw formatError();
                    JSONObject data = state.optJSONObject("data");
                    JSONArray items = data == null ? null : data.optJSONArray("items");
                    if (items == null) throw formatError();
                    List<NaverPlace> places = new ArrayList<>();
                    HashSet<String> seen = new HashSet<>();
                    for (int j = 0; j < items.length() && places.size() < 75; j++) {
                        JSONObject item = items.optJSONObject(j);
                        if (item == null) continue;
                        String id = value(item, "id");
                        String name = value(item, "name");
                        double lat = item.optDouble("latitude", Double.NaN), lon = item.optDouble("longitude", Double.NaN);
                        if (!id.matches("[0-9]+") || name.isEmpty() || !Double.isFinite(lat) || !Double.isFinite(lon)
                                || lat < -90 || lat > 90 || lon < -180 || lon > 180 || (lat == 0 && lon == 0)) continue;
                        if (!seen.add(id)) continue;
                        String address = value(item, "roadAddress");
                        if (address.isEmpty()) address = value(item, "address");
                        places.add(new NaverPlace(id, name, value(item, "category"), address,
                                value(item, "tel"), lat, lon));
                    }
                    if (items.length() > 0 && places.isEmpty()) throw formatError();
                    return new Result(query, places, Math.max(places.size(), data.optInt("totalCount", places.size())));
                }
            } catch (JSONException error) { throw formatError(); }
        }
        throw formatError();
    }

    private static IOException formatError() {
        return new IOException("네이버 지도 검색 정보를 읽지 못했습니다. 접속 제한 또는 페이지 변경일 수 있습니다. 네이버 지도에서 확인해 주세요.");
    }

    private static String value(JSONObject item, String key) {
        return item.isNull(key) ? "" : item.optString(key, "").trim();
    }

    public void cancel() { HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    @Override public void close() { closed = true; cancel(); }

    public static final class Result {
        public final String query;
        public final List<NaverPlace> places;
        public final int totalCount;
        Result(String query, List<NaverPlace> places, int totalCount) {
            this.query = query; this.places = Collections.unmodifiableList(new ArrayList<>(places)); this.totalCount = totalCount;
        }
    }
    private static final class Cached {
        final Result result; final long created = System.nanoTime();
        Cached(Result result) { this.result = result; }
    }
}
