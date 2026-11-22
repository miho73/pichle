package ooo.ionya.driveaway;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Handler;
import android.os.Looper;

import ai.pleos.playground.location.fused.FusedLocation;
import ai.pleos.playground.location.fused.listener.FusedLocationListener;
import ai.pleos.playground.location.fused.model.LocationData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds restaurants around the car: current location from the Pleos fused-location SDK,
 * places from Naver map search. The exact coordinate never leaves the device; Naver only
 * receives text queries (brand names and a coarse area name).
 */
public final class NearbyFinder implements AutoCloseable {
    public interface Listener {
        void onStores(List<PichleApi.StoreSummary> stores, String area);
        void onError(String message);
        void onSearch(String query, List<PichleApi.StoreSummary> stores);
        void onSearchError(String query, String message);
    }

    public static final String ID_PREFIX = "naver-";
    private static final String[] BRANDS = {"스타벅스", "맥도날드"};
    private static final int BRAND_RADIUS_M = 10_000, AREA_RADIUS_M = 5_000, DT_RADIUS_M = 10_000, PER_BRAND = 15, AREA_MAX = 24;
    private static final long LOCATION_TIMEOUT_MS = 20_000;
    private static final Pattern DRIVE_THROUGH = Pattern.compile("(?i)(?<![A-Za-z])DT(?![A-Za-z])|드라이브\\s*(스루|인)");
    private static final Pattern DONG = Pattern.compile("([가-힣]+[0-9]*(?:동|읍|면))(?:\\s|$)");

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final NaverPlaceCrawler crawler = new NaverPlaceCrawler();
    private FusedLocation sdk;
    private FusedLocationListener updates;
    private Runnable timeout;
    private boolean busy, closed, searching;
    private double lastLat = Double.NaN, lastLon = Double.NaN;
    private String lastArea;

    public NearbyFinder(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isBusy() { return busy; }

    /** Main thread. Requires the FUSED_LOCATION permission to be granted already. */
    public void start() {
        if (busy || closed) return;
        busy = true;
        try {
            sdk = new FusedLocation(context);
            if (!sdk.isSupported()) { fail("차량 위치 서비스를 사용할 수 없습니다."); return; }
            sdk.initialize();
            sdk.getLastKnownLocation(new FusedLocationListener() {
                @Override public void onChanged(LocationData location) {
                    main.post(() -> { if (location != null) located(location); else waitForUpdate(); });
                }
                @Override public void onError(Exception error) { main.post(() -> waitForUpdate()); }
            });
        } catch (RuntimeException | LinkageError error) {
            fail("위치 서비스를 시작하지 못했습니다.");
        }
    }

    private void waitForUpdate() {
        if (!busy || closed || updates != null) return;
        updates = new FusedLocationListener() {
            @Override public void onChanged(LocationData location) {
                main.post(() -> { if (location != null) located(location); });
            }
            @Override public void onError(Exception error) { main.post(() -> fail("현재 위치를 받지 못했습니다.")); }
        };
        timeout = () -> fail("현재 위치를 받지 못했습니다. 차량 위치 서비스를 확인해 주세요.");
        main.postDelayed(timeout, LOCATION_TIMEOUT_MS);
        try { sdk.registerFusedLocationCallback(updates); }
        catch (RuntimeException | LinkageError error) { fail("현재 위치를 받지 못했습니다."); }
    }

    private void located(LocationData location) {
        if (!busy || closed) return;
        double lat = location.getLatitude(), lon = location.getLongitude();
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || (lat == 0 && lon == 0)) return;
        releaseLocation();
        lastLat = lat; lastLon = lon;
        worker.execute(() -> {
            try {
                Found found = search(lat, lon);
                main.post(() -> finish(found, null));
            } catch (Exception error) {
                String message = error.getMessage() == null ? "주변 매장을 불러오지 못했습니다." : error.getMessage();
                main.post(() -> finish(null, message));
            }
        });
    }

    private void releaseLocation() {
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
        try { if (sdk != null && updates != null) sdk.unregisterFusedLocationCallback(updates); }
        catch (RuntimeException | LinkageError ignored) { }
        updates = null;
    }

    private void fail(String message) { releaseLocation(); finish(null, message); }

    private void finish(Found found, String error) {
        busy = false;
        if (closed) return;
        if (found != null) listener.onStores(found.stores, found.area); else listener.onError(error);
    }

    // ---- worker thread ----

    private static final class Found {
        final List<PichleApi.StoreSummary> stores; final String area;
        Found(List<PichleApi.StoreSummary> stores, String area) { this.stores = stores; this.area = area; }
    }

    private Found search(double lat, double lon) throws Exception {
        String area = reverseGeocode(lat, lon);
        if (area == null) area = KoreaAreas.nearest(lat, lon);
        if (area == null) throw new java.io.IOException("현재 지역을 확인하지 못했습니다.");
        List<Scored> scored = new ArrayList<>();
        java.io.IOException failure = null;
        for (String brand : BRANDS) {
            List<Scored> hits = new ArrayList<>();
            try {
                for (NaverPlace place : crawler.search(area + " " + brand).places) {
                    if (!place.name.contains(brand)) continue;
                    double d = distance(lat, lon, place.latitude, place.longitude);
                    if (d <= BRAND_RADIUS_M && !containsPlace(scored, place)) hits.add(new Scored(place, d));
                }
            } catch (java.io.IOException error) { failure = error; }
            Collections.sort(hits, (a, b) -> Double.compare(a.meters, b.meters));
            scored.addAll(hits.subList(0, Math.min(PER_BRAND, hits.size())));
        }
        List<Scored> hits = new ArrayList<>();
        for (String query : new String[]{area + " 식당", area + " 드라이브스루"}) {
            try {
                for (NaverPlace place : crawler.search(query).places) {
                    double d = distance(lat, lon, place.latitude, place.longitude);
                    double limit = isDriveThrough(place.name, place.category) ? DT_RADIUS_M : AREA_RADIUS_M;
                    if (d <= limit && !containsPlace(scored, place) && !containsPlace(hits, place)) hits.add(new Scored(place, d));
                }
            } catch (java.io.IOException error) { failure = error; }
        }
        Collections.sort(hits, (a, b) -> Double.compare(a.meters, b.meters));
        scored.addAll(hits.subList(0, Math.min(AREA_MAX, hits.size())));
        if (scored.isEmpty()) {
            throw failure != null ? failure : new java.io.IOException("주변에서 찾은 매장이 없습니다.");
        }
        Collections.sort(scored, (a, b) -> Double.compare(a.meters, b.meters));
        List<PichleApi.StoreSummary> stores = new ArrayList<>();
        for (Scored s : scored) stores.add(toStore(s));
        lastArea = area;
        return new Found(stores, area);
    }

    public boolean isSearching() { return searching; }

    /** Main thread. Searches any place by text and lists the results by distance from the car. */
    public void searchPlaces(String input) {
        final String query = input == null ? "" : input.trim().replaceAll("\\s+", " ");
        if (closed || searching || query.isEmpty()) return;
        if (Double.isNaN(lastLat)) { listener.onSearchError(query, "현재 위치를 확인한 뒤 검색할 수 있어요."); return; }
        searching = true;
        final double lat = lastLat, lon = lastLon;
        final String area = lastArea;
        worker.execute(() -> {
            try {
                List<PichleApi.StoreSummary> result = searchText(lat, lon, area, query);
                main.post(() -> { searching = false; if (!closed) listener.onSearch(query, result); });
            } catch (Exception error) {
                String message = error.getMessage() == null ? "검색하지 못했어요." : error.getMessage();
                main.post(() -> { searching = false; if (!closed) listener.onSearchError(query, message); });
            }
        });
    }

    private List<PichleApi.StoreSummary> searchText(double lat, double lon, String area, String query) throws Exception {
        List<Scored> found = new ArrayList<>();
        java.io.IOException failure = null;
        // Naver ignores coordinates, so try the area-qualified query first, then the plain text.
        String[] queries = area == null ? new String[]{query} : new String[]{area + " " + query, query};
        for (String q : queries) {
            try {
                for (NaverPlace place : crawler.search(q).places) {
                    if (containsPlace(found, place)) continue;
                    found.add(new Scored(place, distance(lat, lon, place.latitude, place.longitude)));
                }
            } catch (java.io.IOException error) { failure = error; }
        }
        if (found.isEmpty()) throw failure != null ? failure : new java.io.IOException("\u2018" + query + "\u2019 검색 결과가 없어요.");
        Collections.sort(found, (a, b) -> Double.compare(a.meters, b.meters));
        List<PichleApi.StoreSummary> stores = new ArrayList<>();
        for (Scored s : found.subList(0, Math.min(30, found.size()))) stores.add(toStore(s));
        return stores;
    }

    private static final class Scored {
        final NaverPlace place; final double meters;
        Scored(NaverPlace place, double meters) { this.place = place; this.meters = meters; }
    }

    /** Naver has no drive-through flag, so it is inferred from the name ("…DT점", "드라이브스루"). */
    public static boolean isDriveThrough(String name, String category) {
        return (name != null && DRIVE_THROUGH.matcher(name).find()) || (category != null && category.contains("DT"));
    }

    private static boolean containsPlace(List<Scored> list, NaverPlace place) {
        for (Scored s : list) if (s.place.id.equals(place.id)) return true;
        return false;
    }

    private String reverseGeocode(double lat, double lon) {
        try {
            if (!Geocoder.isPresent()) return null;
            List<Address> addresses = new Geocoder(context, Locale.KOREA).getFromLocation(lat, lon, 1);
            if (addresses == null || addresses.isEmpty()) return null;
            Address a = addresses.get(0);
            String dong = a.getSubLocality() != null && a.getSubLocality().matches(".*[동읍면]$")
                    ? a.getSubLocality() : a.getThoroughfare();
            if (dong == null || !dong.matches(".*[동읍면]$")) return dong(a.getAddressLine(0));
            return dong;
        } catch (Exception | LinkageError error) { return null; }
    }

    private static String dong(String address) {
        if (address == null) return null;
        Matcher m = DONG.matcher(address);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    private static PichleApi.StoreSummary toStore(Scored s) {
        NaverPlace p = s.place;
        int meters = (int) Math.round(s.meters);
        return new PichleApi.StoreSummary(ID_PREFIX + p.id, p.name, label(p), meters,
                Math.max(1, (int) Math.round(meters / 500.0)), 0, false, p.latitude, p.longitude, p.address);
    }

    private static String label(NaverPlace p) {
        String raw = p.category == null ? "" : p.category;
        String kind = p.matchesCategory("커피") ? "커피" : p.matchesCategory("버거") ? "버거"
                : raw.contains("샌드위치") ? "샌드위치" : "식당";
        String detail = raw.split("[,>]")[0].trim();
        return detail.isEmpty() || detail.equals(kind) ? kind : kind + " · " + detail;
    }

    static double distance(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    @Override public void close() {
        closed = true; busy = false;
        releaseLocation();
        crawler.close();
        worker.shutdownNow();
    }
}
