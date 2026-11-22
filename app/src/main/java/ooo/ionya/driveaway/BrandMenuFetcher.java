package ooo.ionya.driveaway;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Reads the menu each brand publishes on its own site (read-only; nothing can be ordered).
 * Both sites expose names and nutrition but no prices. Call on a worker thread.
 */
public final class BrandMenuFetcher implements AutoCloseable {
    public static final class Entry {
        public final String section, name, detail;
        public final boolean drink;
        public final String imageUrl;
        Entry(String section, String name, String detail, boolean drink, String imageUrl) {
            this.section = section; this.name = name; this.detail = detail; this.drink = drink; this.imageUrl = imageUrl;
        }
    }

    private static final String STARBUCKS = "https://www.starbucks.co.kr/menu/productListAjax.do";
    private static final String MCDONALDS = "https://www.mcdonalds.co.kr/api/v1/kor/product/product/list?page=1&view_rows=200&mainCategory=";
    // {category code, tab label, "d" for drinks / "f" for food}
    private static final String[][] STARBUCKS_SECTIONS = {
            {"W0000171", "콜드 브루", "d"}, {"W0000060", "브루드 커피", "d"}, {"W0000003", "에스프레소", "d"},
            {"W0000004", "프라푸치노", "d"}, {"W0000005", "블렌디드", "d"}, {"W0000422", "리프레셔", "d"},
            {"W0000061", "피지오", "d"}, {"W0000075", "티", "d"}, {"W0000053", "기타 음료", "d"},
            {"W0000062", "주스", "d"}, {"W0000013", "브레드", "f"}, {"W0000032", "케이크", "f"},
            {"W0000033", "샌드위치", "f"}, {"W0000055", "과일 & 요거트", "f"}, {"W0000056", "스낵 & 디저트", "f"},
            {"W0000064", "아이스크림", "f"}};
    private static final Object[][] MCDONALDS_SECTIONS = {{1, "버거", false}, {7, "맥런치", false},
            {2, "맥모닝", false}, {8, "해피 스낵", false}, {4, "사이드 & 디저트", false},
            {3, "해피밀", false}, {5, "맥카페 & 음료", true}};
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final long TTL_NANOS = 30L * 60 * 1_000_000_000;

    private final Map<String, Cached> cache = new HashMap<>();
    private volatile HttpURLConnection active;
    private volatile boolean closed;

    public static boolean supports(String storeName) { return brand(storeName) != null; }

    private static String brand(String storeName) {
        if (storeName == null) return null;
        if (storeName.contains("스타벅스")) return "starbucks";
        if (storeName.contains("맥도날드")) return "mcdonalds";
        return null;
    }

    public List<Entry> load(String storeName) throws IOException {
        String brand = brand(storeName);
        if (brand == null) return Collections.emptyList();
        Cached cached = cache.get(brand);
        if (cached != null && System.nanoTime() - cached.created < TTL_NANOS) return cached.entries;
        List<Entry> entries = "starbucks".equals(brand) ? starbucks() : mcdonalds();
        if (entries.isEmpty()) throw new IOException("공식 사이트에서 메뉴를 찾지 못했습니다.");
        cache.put(brand, new Cached(entries));
        return entries;
    }

    private List<Entry> starbucks() throws IOException {
        List<Entry> out = new ArrayList<>();
        for (String[] section : STARBUCKS_SECTIONS) {
            JSONArray list = parse(request(STARBUCKS, "CATE_CD=" + section[0])).optJSONArray("list");
            if (list == null) continue;
            HashSet<String> seen = new HashSet<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null || "Y".equals(item.optString("sold_OUT"))) continue;
                String name = clean(item.optString("product_NM"));
                if (name.isEmpty() || !seen.add(name)) continue;
                out.add(new Entry(section[1], name, kcal(item.optString("kcal")), "d".equals(section[2]),
                        imageUrl(item.optString("img_UPLOAD_PATH"), item.optString("file_PATH"))));
            }
        }
        return out;
    }

    private List<Entry> mcdonalds() throws IOException {
        List<Entry> out = new ArrayList<>();
        for (Object[] section : MCDONALDS_SECTIONS) {
            JSONObject root = parse(request(MCDONALDS + section[0], null)).optJSONObject("resultObject");
            JSONArray list = root == null ? null : root.optJSONArray("list");
            if (list == null) continue;
            HashSet<String> seen = new HashSet<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null || !"Y".equals(item.optString("status", "Y"))) continue;
                String name = clean(item.optString("korName"));
                if (name.isEmpty() || !seen.add(name)) continue;
                out.add(new Entry((String) section[1], name, kcal(item.optString("calorie")), (Boolean) section[2],
                        imageUrl("https://www.mcdonalds.co.kr", item.optString("pcImageUrl"))));
            }
        }
        return out;
    }

    /** Absolute https URL on a known brand host, or empty. The path is percent-encoded (brackets, Korean). */
    private static String imageUrl(String base, String path) {
        if (base == null || path == null || path.trim().isEmpty()) return "";
        String url = base.trim().replaceAll("/+$", "") + android.net.Uri.encode(path.trim().startsWith("/") ? path.trim() : "/" + path.trim(), "/");
        return ImageLoader.allowed(url) ? url : "";
    }

    private static String kcal(String value) {
        String v = value == null ? "" : value.trim();
        return v.matches("[0-9]+(\\.[0-9]+)?") && !v.equals("0") ? v.replaceAll("\\.0+$", "") + "kcal" : "";
    }

    private static String clean(String html) {
        return html.replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static JSONObject parse(String body) throws IOException {
        try { return new JSONObject(body); }
        catch (JSONException error) { throw new IOException("공식 사이트의 메뉴 형식을 읽지 못했습니다."); }
    }

    private String request(String url, String formBody) throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("메뉴 조회가 취소되었습니다.");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        active = connection;
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9");
            if (formBody != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                try (OutputStream out = connection.getOutputStream()) { out.write(formBody.getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("공식 사이트에 연결하지 못했습니다. (HTTP " + status + ")");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (closed || Thread.currentThread().isInterrupted()) throw new IOException("메뉴 조회가 취소되었습니다.");
                    if (out.size() + count > MAX_BYTES) throw new IOException("메뉴 응답이 너무 큽니다.");
                    out.write(buffer, 0, count);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
            if (active == connection) active = null;
        }
    }

    @Override public void close() {
        closed = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }

    private static final class Cached {
        final List<Entry> entries; final long created = System.nanoTime();
        Cached(List<Entry> entries) { this.entries = Collections.unmodifiableList(entries); }
    }
}
