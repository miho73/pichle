package ooo.ionya.driveaway;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Keeps public place results available when the next lookup is unavailable. */
public final class StoreCache {
    private static final String KEY = "nearby_places";
    private static final int VERSION = 1;
    private static final int MAX_STORES = 60;
    private static final int MAX_JSON_LENGTH = 262_144;
    private final SharedPreferences preferences;

    public static final class Snapshot {
        public final List<PichleApi.StoreSummary> stores;
        public final String area;

        private Snapshot(List<PichleApi.StoreSummary> stores, String area) {
            this.stores = Collections.unmodifiableList(new ArrayList<>(stores));
            this.area = this.stores.isEmpty() ? null : area;
        }
    }

    public StoreCache(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences("pichle_store_cache", Context.MODE_PRIVATE);
    }

    public Snapshot load() {
        try {
            String raw = preferences.getString(KEY, null);
            if (raw == null || raw.length() > MAX_JSON_LENGTH) return empty();
            JSONObject root = new JSONObject(raw);
            if (root.getInt("version") != VERSION) return empty();
            String area = cleanArea(root.optString("area", ""));
            JSONArray entries = root.getJSONArray("stores");
            ArrayList<PichleApi.StoreSummary> stores = new ArrayList<>();
            HashSet<String> ids = new HashSet<>();
            for (int i = 0; i < Math.min(entries.length(), MAX_STORES); i++) {
                JSONObject item = entries.getJSONObject(i);
                PichleApi.StoreSummary store = new PichleApi.StoreSummary(
                        item.getString("id"), item.getString("name"), item.getString("category"),
                        item.getInt("distanceMeters"), item.getInt("driveMinutes"),
                        item.getInt("waitMinutes"), item.getBoolean("electronicOrdering"),
                        item.getDouble("latitude"), item.getDouble("longitude"),
                        item.getString("address"));
                if (valid(store) && ids.add(store.id)) stores.add(store);
            }
            return new Snapshot(stores, area);
        } catch (JSONException | RuntimeException error) {
            return empty();
        }
    }

    /** Stores place coordinates only; the vehicle's location is never recorded here. */
    public void save(List<PichleApi.StoreSummary> stores, String area) {
        if (stores == null) return;
        try {
            JSONArray entries = new JSONArray();
            HashSet<String> ids = new HashSet<>();
            for (PichleApi.StoreSummary store : stores) {
                if (!valid(store) || !ids.add(store.id)) continue;
                JSONObject item = new JSONObject();
                item.put("id", store.id);
                item.put("name", store.name);
                item.put("category", store.category);
                item.put("distanceMeters", store.distanceMeters);
                item.put("driveMinutes", store.driveMinutes);
                item.put("waitMinutes", store.waitMinutes);
                item.put("electronicOrdering", store.electronicOrdering);
                item.put("latitude", store.latitude);
                item.put("longitude", store.longitude);
                item.put("address", store.address);
                entries.put(item);
                if (entries.length() == MAX_STORES) break;
            }
            // An unsuccessful lookup must not replace the last useful result.
            if (entries.length() == 0) return;
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("area", cleanArea(area));
            root.put("stores", entries);
            String raw = root.toString();
            if (raw.length() <= MAX_JSON_LENGTH) preferences.edit().putString(KEY, raw).apply();
        } catch (JSONException | RuntimeException ignored) {
            // Caching is optional and must not interrupt browsing or ordering.
        }
    }

    private static Snapshot empty() {
        return new Snapshot(Collections.emptyList(), null);
    }

    private static String cleanArea(String area) {
        if (area == null) return null;
        String value = area.trim();
        return value.isEmpty() || value.length() > 80 ? null : value;
    }

    private static boolean valid(PichleApi.StoreSummary store) {
        return store != null && store.id != null
                && store.id.startsWith(NearbyFinder.ID_PREFIX)
                && store.id.length() > NearbyFinder.ID_PREFIX.length() && store.id.length() <= 100
                && store.name != null && !store.name.trim().isEmpty() && store.name.length() <= 200
                && store.category != null && store.category.length() <= 200
                && store.address != null && store.address.length() <= 500
                && Double.isFinite(store.latitude) && Double.isFinite(store.longitude)
                && store.latitude >= -90 && store.latitude <= 90
                && store.longitude >= -180 && store.longitude <= 180
                && (store.latitude != 0 || store.longitude != 0)
                && store.distanceMeters >= 0 && store.driveMinutes >= 0 && store.waitMinutes >= 0;
    }
}
