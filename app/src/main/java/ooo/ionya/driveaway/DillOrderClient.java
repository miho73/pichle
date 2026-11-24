package ooo.ionya.driveaway;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.NetworkSecurityPolicy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Blocking HTTP transport. Call submitOrder from the order worker, never the UI thread. */
public final class DillOrderClient {
    public static final String DEFAULT_BASE_URL = "https://pichle.ionya.ooo";
    private static final String APP_API_KEY = "pichle-pleos-pt-2026";
    private static final String BASE_URL_KEY = "base_url";
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_REQUEST_BYTES = 128 * 1024;
    private final SharedPreferences preferences;

    public DillOrderClient(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences("pichle_order_server", Context.MODE_PRIVATE);
    }

    public String getBaseUrl() {
        try { return normalizeBaseUrl(preferences.getString(BASE_URL_KEY, DEFAULT_BASE_URL)); }
        catch (IllegalArgumentException error) { return DEFAULT_BASE_URL; }
    }

    public void setBaseUrl(String value) {
        String normalized = normalizeBaseUrl(value);
        requireAllowedTransport(normalized);
        preferences.edit().putString(BASE_URL_KEY, normalized).apply();
    }

    public void resetBaseUrl() { preferences.edit().remove(BASE_URL_KEY).apply(); }

    public String getManagerUrl() { return getBaseUrl() + "/manager"; }

    /** Local-address classification only; this method never resolves DNS or opens a socket. */
    public boolean needsLocalNetworkPermission() {
        String host = URI.create(getBaseUrl()).getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) return true;
        if (host.contains(":")) {
            if (host.equals("::1") || host.startsWith("fc") || host.startsWith("fd")
                    || host.startsWith("fe8") || host.startsWith("fe9")
                    || host.startsWith("fea") || host.startsWith("feb")) return true;
            return host.startsWith("::ffff:") && privateIpv4(host.substring(7));
        }
        if (!host.contains(".")) return true;
        return privateIpv4(host);
    }

    private static boolean privateIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("[0-9]{1,3}")) return false;
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) return false;
        }
        return octets[0] == 10 || octets[0] == 127 || octets[0] == 0
                || (octets[0] == 169 && octets[1] == 254)
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31);
    }

    private static void requireAllowedTransport(String baseUrl) {
        URI address = URI.create(baseUrl);
        if ("http".equalsIgnoreCase(address.getScheme())
                && !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(address.getHost()))
            throw new IllegalArgumentException("이 주소는 HTTPS로 연결해주세요.");
    }

    public static String normalizeBaseUrl(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 2048)
            throw new IllegalArgumentException("주문 서버 주소를 입력해주세요.");
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https"))
                    || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() < -1
                    || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException("http:// 또는 https://로 시작하는 서버 주소를 입력해주세요.");
            String path = uri.getPath() == null ? "" : uri.getPath();
            String normalized = new URI(scheme, null, uri.getHost(), uri.getPort(), path, null, null)
                    .normalize().toASCIIString();
            while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
            return normalized;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("서버 주소 형식을 확인해주세요.", error);
        }
    }

    /** Stable field order also lets the caller fingerprint an unchanged draft for safe retries. */
    public static String encodeRequest(PichleApi.OrderRequest request) throws IOException {
        if (request == null || request.items == null || request.items.isEmpty())
            throw new OrderException("전송할 주문을 확인해주세요.");
        try {
            JSONObject body = new JSONObject();
            body.put("clientRequestId", request.clientRequestId);
            body.put("storeId", request.storeId);
            body.put("storeName", request.storeName);
            JSONArray items = new JSONArray();
            for (PichleApi.OrderLine line : request.items) {
                if (line == null) throw new OrderException("주문 메뉴를 확인해주세요.");
                JSONObject item = new JSONObject();
                item.put("menuItemId", line.menuItemId);
                item.put("name", line.name);
                item.put("quantity", line.quantity);
                item.put("unitPriceKrw", line.unitPriceKrw == null ? JSONObject.NULL : line.unitPriceKrw);
                item.put("options", line.options == null ? "" : line.options);
                items.put(item);
            }
            body.put("items", items);
            body.put("customerNote", request.customerNote == null ? "" : request.customerNote);
            return body.toString();
        } catch (JSONException error) {
            throw new OrderException("주문 내용을 준비하지 못했습니다.", error);
        }
    }

    public PichleApi.OrderReceipt submitOrder(PichleApi.OrderRequest request) throws IOException {
        if (request == null || request.clientRequestId == null)
            throw new OrderException("주문 요청을 다시 확인해주세요.");
        try { UUID.fromString(request.clientRequestId); }
        catch (IllegalArgumentException error) { throw new OrderException("주문 요청을 다시 확인해주세요.", error); }
        final String baseUrl = getBaseUrl();
        try { requireAllowedTransport(baseUrl); }
        catch (IllegalArgumentException error) { throw new OrderException(error.getMessage(), error); }
        final String endpoint = baseUrl + "/v1/orders";
        byte[] payload = encodeRequest(request).getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_REQUEST_BYTES) throw new OrderException("주문 내용이 너무 깁니다. 요청사항을 줄여주세요.");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-API-Key", APP_API_KEY);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream stream = connection.getOutputStream()) { stream.write(payload); }
            int status = connection.getResponseCode();
            if (status != 200 && status != 201) throw statusError(status);
            String response;
            try (InputStream stream = connection.getInputStream()) { response = readResponse(stream); }
            JSONObject body = new JSONObject(response);
            String orderId = requiredString(body, "orderId");
            String pickupCode = requiredString(body, "pickupCode");
            String state = requiredString(body, "status");
            if (!(state.equals("RECEIVED") || state.equals("PREPARING") || state.equals("READY")
                    || state.equals("COMPLETED") || state.equals("CANCELLED")))
                throw new OrderException("주문 접수 응답을 확인하지 못했습니다. 다시 전송해 확인해주세요.");
            if (body.has("clientRequestId") && !request.clientRequestId.equals(body.optString("clientRequestId")))
                throw new OrderException("주문 접수 응답을 확인하지 못했습니다. 다시 전송해 확인해주세요.");
            if (body.has("storeId") && !request.storeId.equals(body.optString("storeId")))
                throw new OrderException("주문 매장 응답을 확인하지 못했습니다. 다시 전송해 확인해주세요.");
            return new PichleApi.OrderReceipt(orderId, state, pickupCode);
        } catch (SocketTimeoutException error) {
            throw new OrderException("접수 확인이 늦어지고 있습니다. 다시 전송하면 같은 주문의 접수 결과를 확인합니다.", error);
        } catch (JSONException error) {
            throw new OrderException("주문 접수 응답을 읽지 못했습니다. 다시 전송해 확인해주세요.", error);
        } catch (IOException error) {
            if (error instanceof OrderException) throw error;
            throw new OrderException("주문 서버에 연결하지 못했습니다. 서버 주소와 네트워크를 확인해주세요.", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String requiredString(JSONObject object, String key) throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty() || ((String) value).length() > 200)
            throw new OrderException("주문 접수 응답을 확인하지 못했습니다. 다시 전송해 확인해주세요.");
        return (String) value;
    }

    private static String readResponse(InputStream stream) throws IOException {
        if (stream == null) throw new OrderException("주문 접수 응답이 없습니다. 다시 전송해 확인해주세요.");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (result.size() + count > MAX_RESPONSE_BYTES)
                throw new OrderException("주문 접수 응답을 확인하지 못했습니다. 다시 전송해 확인해주세요.");
            result.write(buffer, 0, count);
        }
        return result.toString(StandardCharsets.UTF_8.name());
    }

    private static IOException statusError(int status) {
        if (status == 409) return new OrderException("이 요청의 주문 내용이 다릅니다. 장바구니를 확인해주세요.");
        if (status == 400 || status == 413 || status == 422)
            return new OrderException("주문 내용과 요청사항을 확인해주세요.");
        if (status == 401 || status == 403)
            return new OrderException("주문 접수 권한을 확인해주세요.");
        if (status == 404 || (status >= 300 && status < 400))
            return new OrderException("주문 서버 주소를 확인해주세요.");
        if (status == 429) return new OrderException("주문이 몰리고 있습니다. 잠시 후 다시 전송해주세요.");
        return new OrderException("주문 서버에서 접수를 확인하지 못했습니다. 잠시 후 다시 전송해주세요.");
    }

    private static final class OrderException extends IOException {
        OrderException(String message) { super(message); }
        OrderException(String message, Throwable cause) { super(message, cause); }
    }
}
