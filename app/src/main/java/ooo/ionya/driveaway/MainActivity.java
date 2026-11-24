package ooo.ionya.driveaway;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Screen-based ordering flow. Vehicle operations stay in the dedicated SDK controllers. */
public class MainActivity extends AppCompatActivity {
    private int BG, INK, MUTED, LINE, PRIMARY, SURFACE, ON_PRIMARY;
    private final PichleApi api = new LocalPichleApi();
    private DillOrderClient ordersClient;
    private final java.util.concurrent.ExecutorService orderWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private ActivityResultLauncher<String> orderNetworkPermission;
    private String orderError = "", pendingOrderRequestId = "", pendingOrderFingerprint = "";
    private final android.os.Handler orderStatusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable orderStatusTick = this::refreshOrderStatus;
    private boolean orderStatusFetching, orderStatusReconnecting;
    private TextView orderConnectionView;
    private final List<PichleApi.StoreSummary> stores = new ArrayList<>();
    private final List<PichleApi.MenuItem> menu = new ArrayList<>();
    private final List<CartItem> cart = new ArrayList<>();
    private final HashSet<String> favorites = new HashSet<>();
    private OrderFlow screen = OrderFlow.STORES;
    private PichleApi.StoreSummary selected;
    private PichleApi.MenuItem draftMenu;
    private NearbyFinder nearby;
    private final List<PichleApi.StoreSummary> nearbyStores = new ArrayList<>();
    private String searchDraft = "", searchQuery = "";
    private boolean searching;
    private boolean orderWindowOpen, orderWindowPending, pickupWindowOpen, pickupFinishing, pickupCloseFailed;
    private final ImageLoader images = new ImageLoader();
    private BrandMenuFetcher brandMenus;
    private final java.util.concurrent.ExecutorService menuWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private List<BrandMenuFetcher.Entry> placeMenu = Collections.emptyList();
    private String placeMenuStatus = "", placeTab;
    private int placeMenuRequest;
    private ActivityResultLauncher<String[]> locationPermission;
    private String nearbyArea, nearbyStatus = "";
    private StoreCache storeCache;
    private String preferredArea = "송도동";
    private boolean browseSelectedArea, areaFallbackAttempted, usingCurrentLocation, showingRecentStores;
    private boolean dtOnly;
    private boolean nearbyLoaded, locationAsked;
    private PichleApi.OrderReceipt receipt;
    private OrderSpeechController speech;
    private PleosNavigation navigation;
    private PleosWindowController windows;
    private CarUxGate uxGate;
    private CarUxGate.State uxState;
    private AlertDialog storeChangeDialog;
    private LinearLayout content, footer;
    private TextView voiceStatusView, navigationStatusView, windowStatusView;
    private Button speechPlay, speechStop;
    private EditText optionNote;
    private final ArrayList<String> draftOptions = new ArrayList<>();
    private int draftQuantity = 1;
    private String draftNote = "", category = "전체", method = "";
    private String voiceStatus = "음성 준비 중", navigationStatus = "", windowStatus = "";
    private boolean arrived, voiceDelivered, submitting, destroyed, foreground;
    private int developerTapCount;
    private long developerTapStarted;
    private final HashSet<String> uxVisitedContent = new HashSet<>();
    private final ArrayList<OrderFlow> uxContentPath = new ArrayList<>();
    private int uxPageItems;
    private boolean uxContentTruncated, uxGoingBack;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AppTaskIcon.apply(this);
        BG = getColor(R.color.pichle_background);
        INK = getColor(R.color.pichle_text);
        MUTED = getColor(R.color.pichle_text_secondary);
        LINE = getColor(R.color.pichle_divider);
        PRIMARY = getColor(R.color.pichle_primary);
        SURFACE = getColor(R.color.pichle_surface);
        ON_PRIMARY = getColor(R.color.pichle_on_primary);
        brandMenus = new BrandMenuFetcher(this);
        favorites.addAll(getPreferences(MODE_PRIVATE).getStringSet("favorites", Collections.emptySet()));
        dtOnly = getPreferences(MODE_PRIVATE).getBoolean("dtOnly", false);
        storeCache = new StoreCache(this);
        StoreCache.Snapshot cachedStores = storeCache.load();
        stores.addAll(cachedStores.stores); nearbyStores.addAll(cachedStores.stores);
        nearbyArea = cachedStores.area;
        showingRecentStores = !stores.isEmpty();
        preferredArea = getPreferences(MODE_PRIVATE).getString("storeArea", "송도동");
        browseSelectedArea = getPreferences(MODE_PRIVATE).getBoolean("browseSelectedArea", false);
        areaFallbackAttempted = browseSelectedArea;
        nearbyStatus = showingRecentStores ? "최근 본 매장" : "매장을 찾고 있습니다.";
        restoreState(state);
        ordersClient = new DillOrderClient(this);
        orderNetworkPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (destroyed) return;
            if (granted && canEditOrder() && screen == OrderFlow.SEND) submitOrder();
            else if (!granted) {
                orderError = "주문 서버에 연결하려면 근처 기기 권한을 허용해주세요.";
                if (foreground) render();
            }
        });
        locationPermission = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), grants -> {
            if (destroyed) return;
            if (checkSelfPermission(FusedLocationProbe.PERMISSION) == PackageManager.PERMISSION_GRANTED) startNearby();
            else handleNearbyError("Fused location permission was not granted.");
        });
        nearby = new NearbyFinder(this, new NearbyFinder.Listener() {
            @Override public void onStores(List<PichleApi.StoreSummary> found, String area) {
                if (destroyed) return;
                nearbyStores.clear(); nearbyStores.addAll(found);
                if (!searching && searchQuery.isEmpty()) { stores.clear(); stores.addAll(found); }
                nearbyArea = area; nearbyLoaded = true;
                usingCurrentLocation = nearby.isUsingCurrentLocation(); showingRecentStores = false;
                nearbyStatus = usingCurrentLocation ? "현재 위치 기준 · 가까운 순" : area + " 기준 · 가까운 순";
                if (java.util.Arrays.asList(NearbyFinder.areaNames()).contains(area)) {
                    preferredArea = area;
                    getPreferences(MODE_PRIVATE).edit().putString("storeArea", area).apply();
                }
                storeCache.save(found, area);
                if (foreground) render();
            }
            @Override public void onSearch(String query, List<PichleApi.StoreSummary> found) {
                if (destroyed) return;
                searching = false; searchQuery = query;
                stores.clear(); stores.addAll(found);
                category = "전체";
                if (foreground) render();
            }
            @Override public void onSearchError(String query, String message) {
                if (destroyed) return;
                searching = false;
                android.util.Log.w("PichleNearby", "Place search: " + message);
                toast("검색어를 바꿔 다시 찾아보세요.");
                if (foreground) render();
            }
            @Override public void onError(String message) {
                if (destroyed) return;
                handleNearbyError(message);
            }
        });
        speech = new OrderSpeechController(this, status -> runOnUiThread(() -> {
            if (destroyed) return;
            voiceStatus = status;
            updateSpeechViews();
        }));
        speech.setFinishListener(completed -> { if (!destroyed) closeOrderWindow(); });
        navigation = new PleosNavigation(this, status -> runOnUiThread(() -> {
            if (destroyed) return;
            navigationStatus = status;
            if (navigationStatusView != null) navigationStatusView.setText(uxText(status));
        }));
        windows = new PleosWindowController(this, status -> runOnUiThread(() -> {
            if (destroyed) return;
            windowStatus = status;
            if (windowStatusView != null) windowStatusView.setText(uxText(status));
        }));
        uxGate = new CarUxGate(this, this::onUxChanged);
        uxState = uxGate.getState();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { goBack(); }
        });
        render();
    }

    private void go(OrderFlow next) {
        if (submitting && next != screen) { toast("주문을 전송하고 있습니다. 잠시 기다려주세요."); return; }
        saveDraftNote();
        if (!canOpenScreen(next)) return;
        if (screen == OrderFlow.VOICE && next != OrderFlow.VOICE && speech != null) speech.stop();
        if (screen == OrderFlow.PICKUP && next != OrderFlow.PICKUP) closePickupWindowQuietly();
        recordContentScreen(next);
        screen = next;
        render();
    }

    private void render() {
        if (reducedUi()) { showDrivingUi(); return; }
        if (screen != OrderFlow.STORES && selected == null) screen = OrderFlow.STORES;
        switch (screen) {
            case MENU: showMenu(); break;
            case OPTIONS: if (draftMenu == null) go(OrderFlow.MENU); else showOptions(); break;
            case REVIEW: showReview(); break;
            case METHOD: showMethod(); break;
            case SEND: showSend(); break;
            case VOICE: showVoice(); break;
            case TRAVEL: showTravel(); break;
            case PICKUP: showPickup(); break;
            default: showStores();
        }
    }

    private void runSearch() {
        if (!canFilterContent() || nearby == null || searching) return;
        String query = searchDraft.trim();
        if (query.isEmpty()) return;
        View focused = getCurrentFocus();
        InputMethodManager input = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (focused != null && input != null) input.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        searching = true;
        render();
        nearby.searchPlaces(query);
    }

    private void clearSearch() {
        searchQuery = ""; searchDraft = ""; searching = false;
        stores.clear(); stores.addAll(nearbyStores);
        category = "전체";
        render();
    }

    private void startNearby() {
        if (!canEditOrder() || nearbyLoaded || nearby == null || nearby.isBusy()) return;
        if (browseSelectedArea) { nearby.browseArea(preferredArea); return; }
        if (checkSelfPermission(FusedLocationProbe.PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            nearby.start();
        } else if (!locationAsked && canEditOrder() && uxState != null && uxState.setupAllowed()) {
            locationAsked = true;
            try { locationPermission.launch(FusedLocationProbe.requiredPermissions()); }
            catch (RuntimeException error) { handleNearbyError("Location permission request: " + error); }
        } else if (!locationAsked && uxState != null && !uxState.setupAllowed()) {
            handleNearbyError("Location permission setup is restricted by the vehicle.");
        }
    }

    private void handleNearbyError(String detail) {
        android.util.Log.w("PichleNearby", "Nearby lookup: " + detail);
        nearbyLoaded = true;
        if (!nearbyStores.isEmpty()) {
            showingRecentStores = true; usingCurrentLocation = false;
            nearbyStatus = "최근 본 매장";
        } else nearbyStatus = "지역을 선택해 매장을 둘러보세요.";
        if (!areaFallbackAttempted && nearby != null) {
            areaFallbackAttempted = true;
            if (nearbyStores.isEmpty()) nearbyStatus = "매장을 찾고 있습니다.";
            nearby.browseArea(preferredArea);
        }
        if (foreground) render();
    }

    private void refreshCurrentLocation() {
        if (!canFilterContent() || nearby == null) return;
        browseSelectedArea = false; areaFallbackAttempted = false; nearbyLoaded = false; locationAsked = false;
        getPreferences(MODE_PRIVATE).edit().putBoolean("browseSelectedArea", false).apply();
        clearSearch();
        nearbyStatus = "현재 위치로 매장을 찾고 있습니다.";
        if (checkSelfPermission(FusedLocationProbe.PERMISSION) == PackageManager.PERMISSION_GRANTED) nearby.start();
        else startNearby();
        render();
    }

    private void chooseStoreArea() {
        if (!canFilterContent() || nearby == null) return;
        if (!canOpenAuxiliaryView()) return;
        ArrayList<String> allowedAreas = new ArrayList<>();
        for (String area : NearbyFinder.areaNames()) {
            if (!claimContentItem("area:" + area)) break;
            allowedAreas.add(area);
        }
        if (allowedAreas.isEmpty()) { toast("다른 지역은 주차 후 선택해주세요."); return; }
        String[] areas = allowedAreas.toArray(new String[0]);
        storeChangeDialog = new AlertDialog.Builder(this).setTitle("매장 지역 선택")
                .setItems(areas, (dialog, which) -> {
                    if (!canFilterContent()) return;
                    preferredArea = areas[which]; browseSelectedArea = true;
                    areaFallbackAttempted = true; nearbyLoaded = false;
                    getPreferences(MODE_PRIVATE).edit().putString("storeArea", preferredArea)
                            .putBoolean("browseSelectedArea", true).apply();
                    clearSearch(); category = "전체";
                    nearbyStatus = "매장을 찾고 있습니다.";
                    nearby.browseArea(preferredArea);
                    render();
                }).setNegativeButton("닫기", null).show();
    }

    private static boolean fromNaver(PichleApi.StoreSummary store) {
        return store != null && store.id.startsWith(NearbyFinder.ID_PREFIX);
    }

    private boolean reducedUi() {
        return uxState == null || !uxState.contentAllowed();
    }

    private boolean canEditOrder() {
        return foreground && !destroyed && !reducedUi();
    }

    private String uxText(String value) {
        return uxState != null && uxState.connected ? uxState.limitText(value) : value;
    }

    private boolean canFilterContent() {
        return canEditOrder() && uxState.filteringAllowed();
    }

    private boolean keyboardInputAllowed() {
        return !reducedUi() && uxState.keyboardAllowed();
    }

    private void applyKeyboardPolicy(EditText input) {
        boolean allowed = keyboardInputAllowed();
        input.setShowSoftInputOnFocus(allowed);
        input.setFocusable(allowed);
        input.setFocusableInTouchMode(allowed);
        input.setCursorVisible(allowed);
        input.setLongClickable(allowed);
        if (!allowed) { input.setKeyListener(null); input.clearFocus(); }
    }

    /** Count stable content identities once per restriction period, never once per render. */
    private boolean claimContentItem(String stableKey) {
        if (uxState == null || !uxState.limitsContent()) return true;
        if (uxPageItems >= uxState.maxItems || (!uxVisitedContent.contains(stableKey)
                && uxVisitedContent.size() >= uxState.maxItems)) {
            uxContentTruncated = true;
            return false;
        }
        ++uxPageItems; uxVisitedContent.add(stableKey);
        return true;
    }

    private void addContentLimitNotice(LinearLayout parent) {
        if (uxContentTruncated) addText(parent, "차량의 콘텐츠 표시 한도에 도달했습니다.", 16, MUTED, false, 16);
    }

    private boolean canOpenAuxiliaryView() {
        if (!canEditOrder()) return false;
        if (!uxState.limitsContent() || uxContentPath.size() + 1 <= uxState.maxDepth) return true;
        toast("차량의 화면 탐색 한도에 도달했습니다.");
        return false;
    }

    private boolean canOpenScreen(OrderFlow next) {
        if (destroyed || reducedUi()) return false;
        if (uxGoingBack || !uxState.limitsContent() || next == screen || next == OrderFlow.STORES) return true;
        int existing = uxContentPath.lastIndexOf(next);
        int depth = existing >= 0 ? existing + 1 : uxContentPath.size() + 1;
        if (depth <= uxState.maxDepth) return true;
        toast("차량의 화면 탐색 한도에 도달했습니다.");
        return false;
    }

    private void recordContentScreen(OrderFlow next) {
        if (uxState == null || !uxState.limitsContent()) return;
        if (next == OrderFlow.STORES) { uxContentPath.clear(); uxContentPath.add(next); return; }
        int existing = uxContentPath.lastIndexOf(next);
        if (uxGoingBack && existing < 0 && !uxContentPath.isEmpty()) uxContentPath.remove(uxContentPath.size() - 1);
        if (existing < 0) uxContentPath.add(next);
        else while (uxContentPath.size() > existing + 1) uxContentPath.remove(uxContentPath.size() - 1);
    }

    private void onUxChanged(CarUxGate.State next) {
        if (destroyed) return;
        saveDraftNote();
        uxState = next;
        if (storeChangeDialog != null && (!next.filteringAllowed() || next.limitsContent())) {
            storeChangeDialog.dismiss(); storeChangeDialog = null;
        }
        if (next.connected && !next.limitsContent()) {
            uxVisitedContent.clear(); uxContentPath.clear();
        } else if (next.limitsContent() && uxContentPath.isEmpty()) uxContentPath.add(screen);
        if (reducedUi() || !keyboardInputAllowed()) {
            developerTapCount = 0;
            View focused = getCurrentFocus();
            if (focused != null) {
                InputMethodManager input = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (input != null) input.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                focused.clearFocus();
            }
        }
        if (reducedUi()) {
            if (storeChangeDialog != null) { storeChangeDialog.dismiss(); storeChangeDialog = null; }
            if (speech != null) speech.stop();
        }
        if (foreground) render();
        if (foreground && !reducedUi()) startNearby();
    }

    /** A temporary guard only while the actual car service cannot allow any content. */
    private void showDrivingUi() {
        page("차량 연결 확인", null);
        if (uxState == null || !uxState.connected) {
            addText(content, "차량 상태 확인 중", 26, INK, true, 8);
            addText(content, "차량의 화면 표시 조건을 확인하고 있습니다.", 18, MUTED, false, 14);
            return;
        }
        addText(content, "차량의 콘텐츠 표시가 제한되어 있습니다.", 26, INK, true, 8);
    }

    private void playOrderSpeech() {
        if (!canEditOrder() || selected == null || cart.isEmpty()) return;
        if (speech.isBusy() || orderWindowPending) return;
        orderWindowPending = true;
        windowStatus = "운전석 창문을 열고 있어요.";
        updateSpeechViews();
        windows.lowerDriverWindow((success, message) -> runOnUiThread(() -> {
            orderWindowPending = false;
            if (destroyed) return;
            windowStatus = message;
            // The open request was sent even if its confirmation timed out, so always try to close later.
            orderWindowOpen = true;
            if (windowStatusView != null) windowStatusView.setText(uxText(message));
            // Speak even if the window could not be opened; the status line says why.
            speech.speak(orderText());
            if (!speech.isBusy()) closeOrderWindow();
            updateSpeechViews();
        }));
    }

    /** Closes the window that the order playback opened. Volume is restored by the speech controller. */
    private void closeOrderWindow() {
        if (!orderWindowOpen || destroyed) return;
        orderWindowOpen = false;
        windowStatus = "음성이 끝나 창문을 닫고 있어요.";
        if (windowStatusView != null) windowStatusView.setText(uxText(windowStatus));
        windows.raiseDriverWindow((success, message) -> runOnUiThread(() -> {
            if (destroyed) return;
            if (!success) orderWindowOpen = true;
            windowStatus = success ? "운전석 창문을 닫았어요." : message + " 창문이 열려 있을 수 있어요.";
            if (windowStatusView != null) windowStatusView.setText(uxText(windowStatus));
            if (!success) toast("창문을 닫지 못했어요. 직접 확인해 주세요.");
        }));
    }

    private void openPickupWindow() {
        if (!canEditOrder() || pickupWindowOpen || pickupFinishing) return;
        windowStatus = "운전석 창문을 열고 있어요.";
        if (windowStatusView != null) windowStatusView.setText(uxText(windowStatus));
        windows.lowerDriverWindow((success, message) -> runOnUiThread(() -> {
            if (destroyed) return;
            pickupWindowOpen = true; pickupCloseFailed = false;
            windowStatus = message;
            if (windowStatusView != null) windowStatusView.setText(uxText(message));
        }));
    }

    private void completePickup() {
        if (pickupFinishing) return;
        Runnable done = () -> {
            cart.clear(); receipt = null; method = ""; voiceDelivered = false; arrived = false;
            pendingOrderRequestId = ""; pendingOrderFingerprint = ""; orderError = "";
            orderStatusHandler.removeCallbacks(orderStatusTick);
            orderStatusReconnecting = false;
            go(OrderFlow.STORES);
        };
        if (!pickupWindowOpen) { done.run(); return; }
        pickupFinishing = true;
        windowStatus = "창문을 닫고 있어요.";
        if (windowStatusView != null) windowStatusView.setText(uxText(windowStatus));
        windows.raiseDriverWindow((success, message) -> runOnUiThread(() -> {
            pickupFinishing = false;
            if (destroyed) return;
            if (success) { pickupWindowOpen = false; done.run(); return; }
            if (pickupCloseFailed) { pickupWindowOpen = false; done.run(); return; }
            pickupCloseFailed = true;
            windowStatus = message + " 창문 상태를 확인하고, 그대로 끝내려면 수령 완료를 한 번 더 눌러 주세요.";
            if (windowStatusView != null) windowStatusView.setText(uxText(windowStatus));
        }));
    }

    /** Leaving the pickup screen another way (e.g. back) must not leave the window open. */
    private void closePickupWindowQuietly() {
        if (!pickupWindowOpen) return;
        pickupWindowOpen = false;
        windows.raiseDriverWindow((success, message) -> runOnUiThread(() -> {
            if (destroyed) return;
            if (!success) { pickupWindowOpen = true; toast("창문을 닫지 못했어요. 직접 확인해 주세요."); }
        }));
    }

    private void page(String title, String subtitle) {
        uxPageItems = 0; uxContentTruncated = false;
        voiceStatusView = null; navigationStatusView = null; windowStatusView = null;
        speechPlay = null; speechStop = null; optionNote = null;
        orderConnectionView = null;
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(root);
        LinearLayout appbar = row();
        appbar.setBackgroundColor(SURFACE);
        appbar.setPadding(dp(24), dp(8), dp(24), dp(8));
        if (screen != OrderFlow.STORES || reducedUi()) {
            Button back = button(reducedUi() ? "닫기" : "‹ 뒤로", false, this::goBack);
            back.setOnClickListener(view -> { if (foreground && !destroyed) goBack(); });
            back.setBackgroundColor(Color.TRANSPARENT);
            appbar.addView(back, params(dp(104), dp(52)));
        }
        TextView brand = text("Pichle", 23, INK, true);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        if (!reducedUi()) brand.setOnClickListener(view -> openDeveloperMode());
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(0, dp(52), 1);
        brandLp.setMarginStart(screen == OrderFlow.STORES ? 0 : dp(18));
        appbar.addView(brand, brandLp);
        if (!reducedUi() && selected != null && screen == OrderFlow.STORES && !cart.isEmpty()) {
            Button resume = button(receipt == null ? "장바구니 " + count() : "주문 현황", false,
                    () -> go(receipt == null ? OrderFlow.REVIEW : OrderFlow.SEND));
            appbar.addView(resume, params(dp(160), dp(52)));
        }
        root.addView(appbar, params(-1, -2));
        View rule = new View(this); rule.setBackgroundColor(LINE); root.addView(rule, params(-1, dp(1)));
        LinearLayout heading = column();
        heading.setPadding(dp(28), dp(24), dp(28), dp(18));
        heading.addView(text(title, 30, INK, true));
        if (subtitle != null && !subtitle.isEmpty()) addText(heading, subtitle, 16, MUTED, false, 8);
        root.addView(heading, params(-1, -2));
        content = column(); content.setPadding(dp(28), 0, dp(28), dp(24));
        if (reducedUi()) {
            root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(false);
            scroll.setClipToPadding(false);
            scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        footer = column(); footer.setBackgroundColor(SURFACE);
        footer.setPadding(dp(24), dp(14), dp(24), dp(18));
        footer.setVisibility(View.GONE);
        root.addView(footer, params(-1, -2));
    }

    private void showStores() {
        page(!searchQuery.isEmpty() ? "\u2018" + searchQuery + "\u2019 검색 결과" : nearbyArea == null ? "매장 둘러보기"
                        : nearbyArea + (usingCurrentLocation && !showingRecentStores ? " 주변 매장" : " 매장"),
                searching ? "검색 중이에요" : null);
        LinearLayout regionRow = row();
        Button currentLocation = button("내 위치", false, this::refreshCurrentLocation);
        currentLocation.setEnabled(canFilterContent());
        regionRow.addView(currentLocation, params(dp(132), dp(52)));
        Button region = button("지역 선택", false, this::chooseStoreArea);
        region.setEnabled(canFilterContent());
        LinearLayout.LayoutParams regionLp = params(dp(148), dp(52)); regionLp.setMarginStart(dp(10));
        regionRow.addView(region, regionLp);
        add(content, regionRow, 0, -2);
        LinearLayout searchRow = row();
        android.widget.EditText searchBox = new android.widget.EditText(this);
        searchBox.setSingleLine(true); searchBox.setTextSize(18); searchBox.setText(searchDraft);
        searchBox.setHint("매장 검색"); searchBox.setTextColor(INK); searchBox.setHintTextColor(MUTED);
        searchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) { searchDraft = e.toString(); }
        });
        searchBox.setEnabled(canFilterContent());
        applyKeyboardPolicy(searchBox);
        searchBox.setOnEditorActionListener((v, action, event) -> { runSearch(); return true; });
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0, dp(56), 1));
        Button searchGo = button("검색", true, this::runSearch);
        searchGo.setEnabled(canFilterContent());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(110), dp(56)); sp.setMarginStart(dp(10));
        searchRow.addView(searchGo, sp);
        if (!searchQuery.isEmpty()) {
            Button searchClear = button("지우기", false, () -> { if (canFilterContent()) clearSearch(); });
            searchClear.setEnabled(canFilterContent());
            LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(dp(110), dp(56)); cp2.setMarginStart(dp(10));
            searchRow.addView(searchClear, cp2);
        }
        add(content, searchRow, 8, -2);
        LinearLayout dtRow = row();
        dtRow.addView(text("드라이브스루(DT)만 보기", 19, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Switch dtSwitch = new Switch(this);
        dtSwitch.setChecked(dtOnly);
        dtSwitch.setEnabled(canFilterContent());
        dtSwitch.setContentDescription("드라이브스루 매장만 보기");
        dtSwitch.setThumbTintList(android.content.res.ColorStateList.valueOf(dtOnly ? PRIMARY : MUTED));
        dtSwitch.setTrackTintList(android.content.res.ColorStateList.valueOf(dtOnly ? PRIMARY : LINE));
        dtSwitch.setOnCheckedChangeListener((view, checked) -> {
            if (!canFilterContent()) return;
            dtOnly = checked;
            getPreferences(MODE_PRIVATE).edit().putBoolean("dtOnly", checked).apply();
            render();
        });
        dtRow.addView(dtSwitch, new LinearLayout.LayoutParams(-2, dp(48)));
        add(content, dtRow, 4, -2);
        LinearLayout filters = row();
        for (String filter : new String[]{"전체", "식당", "커피", "버거", "샌드위치", "즐겨찾기"}) {
            Button chip = button(filter, filter.equals(category), () -> { if (canFilterContent()) { category = filter; render(); } });
            chip.setEnabled(canFilterContent());
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(50), 1);
            filters.addView(chip, cp);
        }
        content.addView(filters, params(-1, -2));
        List<PichleApi.StoreSummary> visible = new ArrayList<>();
        for (PichleApi.StoreSummary store : stores) {
            if (dtOnly && !NearbyFinder.isDriveThrough(store.name, store.category)) continue;
            if (category.equals("즐겨찾기") && !favorites.contains(store.id)) continue;
            if (!category.equals("전체") && !category.equals("즐겨찾기") && !store.category.contains(category)) continue;
            visible.add(store);
        }
        visible.sort((a, b) -> {
            boolean aMenu = !fromNaver(a) || BrandMenuFetcher.supports(a.name);
            boolean bMenu = !fromNaver(b) || BrandMenuFetcher.supports(b.name);
            int availableFirst = Boolean.compare(bMenu, aMenu);
            return availableFirst != 0 ? availableFirst : Integer.compare(a.distanceMeters, b.distanceMeters);
        });
        if (stores.isEmpty()) {
            addText(content, nearbyStatus, 18, MUTED, false, 32);
            return;
        }
        if (visible.isEmpty()) {
            addText(content, category.equals("즐겨찾기") ? "즐겨찾기한 매장이 없습니다." : dtOnly ? "주변에 드라이브스루 매장이 없습니다." : "표시할 매장이 없습니다.", 18, MUTED, false, 32);
            return;
        }
        GridLayout group = new GridLayout(this);
        group.setColumnCount(3);
        group.setAlignmentMode(GridLayout.ALIGN_MARGINS);
        group.setUseDefaultMargins(false);
        int storeIndex = 0;
        for (PichleApi.StoreSummary store : visible) {
            if (!claimContentItem("store:" + store.id)) break;
            LinearLayout card = card();
            LinearLayout title = row();
            title.addView(text(store.name, 22, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            Button heart = button(favorites.contains(store.id) ? "♥" : "♡", false, () -> {
                if (!favorites.add(store.id)) favorites.remove(store.id);
                getPreferences(MODE_PRIVATE).edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
                render();
            });
            heart.setBackgroundColor(Color.TRANSPARENT);
            heart.setContentDescription(store.name + " 즐겨찾기");
            title.addView(heart, params(dp(52), dp(52))); card.addView(title);
            addText(card, store.category + " · " + String.format(Locale.KOREA, "%.1fkm", store.distanceMeters / 1000.0), 16, MUTED, false, 8);
            boolean naver = fromNaver(store);
            addText(card, naver ? "약 " + store.driveMinutes + "분" : "총 " + (store.driveMinutes + store.waitMinutes) + "분", 30, INK, true, 26);
            if (!naver) addText(card, "이동 " + store.driveMinutes + "분 · 대기 " + store.waitMinutes + "분", 17, MUTED, false, 8);
            card.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
            Button choose = button(naver && !BrandMenuFetcher.supports(store.name) ? "매장 정보" : "메뉴 보기", true, () -> chooseStore(store));
            add(card, choose, 24, dp(58));
            addStoreCell(group, card, storeIndex++);
        }
        // Reserve empty columns so one or two filtered stores keep the same card width.
        while (storeIndex % 3 != 0) addStoreCell(group, new View(this), storeIndex++);
        add(content, group, 18, -2);
        addContentLimitNotice(content);
    }

    private void openDeveloperMode() {
        if (!canEditOrder()) { developerTapCount = 0; return; }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - developerTapStarted > 4000) { developerTapCount = 0; developerTapStarted = now; }
        if (++developerTapCount < 7) return;
        developerTapCount = 0;
        saveDraftNote();
        if (speech != null) speech.stop();
        startActivity(new Intent(this, DeveloperActivity.class));
    }

    private void addStoreCell(GridLayout grid, View child, int index) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams(
                GridLayout.spec(index / 3, GridLayout.FILL), GridLayout.spec(index % 3, 1f));
        p.width = 0; p.height = -2;
        p.leftMargin = index % 3 == 0 ? 0 : dp(16);
        p.topMargin = index < 3 ? 0 : dp(16);
        grid.addView(child, p);
    }

    private void chooseStore(PichleApi.StoreSummary store) {
        if (!canEditOrder() || !canOpenScreen(OrderFlow.MENU)) return;
        if (selected != null && !selected.id.equals(store.id) && !cart.isEmpty()) {
            storeChangeDialog = new AlertDialog.Builder(this).setTitle("매장을 변경할까요?")
                    .setMessage("현재 장바구니의 메뉴가 삭제됩니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("변경", (dialog, which) -> selectStore(store)).show();
        } else selectStore(store);
    }

    private void selectStore(PichleApi.StoreSummary store) {
        if (!canEditOrder() || !canOpenScreen(OrderFlow.MENU)) return;
        if (selected == null || !selected.id.equals(store.id)) {
            cart.clear(); receipt = null; method = ""; arrived = false; voiceDelivered = false;
            pendingOrderRequestId = ""; pendingOrderFingerprint = ""; orderError = "";
            navigationStatus = ""; windowStatus = "";
        }
        selected = store; loadMenu(); loadPlaceMenu(); go(OrderFlow.MENU);
    }

    private void loadMenu() {
        menu.clear();
        try {
            if (selected != null && !fromNaver(selected)) menu.addAll(api.getMenu(selected.id));
        }
        catch (Exception error) { toast("메뉴를 불러오지 못했습니다."); }
    }

    /** Stores found on Naver map: menu selection, server ordering, and navigation. */
    private void showPlaceDetail() {
        page(selected.name, String.format(Locale.KOREA, "%.1fkm · 약 %d분", selected.distanceMeters / 1000.0, selected.driveMinutes)
                + " · " + selected.category);
        LinearLayout info = card();
        if (!selected.address.isEmpty()) addText(info, selected.address, 18, INK, false, 0);
        Button navigate = button("길안내", true, () -> {
            if (!canEditOrder() || selected == null) return;
            navigation.navigate(selected.latitude, selected.longitude, selected.name, selected.address);
        });
        add(info, navigate, 18, dp(58));
        navigationStatusView = text(navigationStatus, 17, MUTED, false);
        add(info, navigationStatusView, 10, -2);
        content.addView(info);
        if (!BrandMenuFetcher.supports(selected.name)) return;
        addText(content, "메뉴", 24, INK, true, 22);
        if (placeMenu.isEmpty()) { addText(content, placeMenuStatus, 18, MUTED, false, 16); return; }
        List<String> sections = new ArrayList<>();
        for (BrandMenuFetcher.Entry entry : placeMenu) if (!sections.contains(entry.section)) sections.add(entry.section);
        if (placeTab == null || !sections.contains(placeTab)) placeTab = sections.get(0);
        LinearLayout tabs = row();
        for (String section : sections) {
            if (!claimContentItem("section:" + selected.id + ":" + section)) break;
            Button tab = button(section, section.equals(placeTab), () -> { placeTab = section; render(); });
            tab.setMinWidth(dp(120)); tab.setPadding(dp(20), 0, dp(20), 0);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, dp(50));
            tp.setMarginEnd(dp(8)); tabs.addView(tab, tp);
        }
        android.widget.HorizontalScrollView tabScroll = new android.widget.HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabs);
        add(content, tabScroll, 16, -2);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setUseDefaultMargins(false);
        int index = 0;
        for (BrandMenuFetcher.Entry entry : placeMenu) {
            if (!entry.section.equals(placeTab)) continue;
            if (!claimContentItem("menu:" + selected.id + ":" + entry.name)) break;
            PichleApi.MenuItem item = toMenuItem(entry);
            LinearLayout card = card();
            card.addView(menuPhoto(entry), new LinearLayout.LayoutParams(-1, dp(150)));
            addText(card, entry.name, 21, INK, true, 12);
            if (!entry.detail.isEmpty()) addText(card, entry.detail, 16, MUTED, false, 8);
            card.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
            add(card, button("선택", false, () -> {
                if (!canEditOrder()) return;
                draftMenu = item; draftQuantity = 1; draftOptions.clear(); draftNote = ""; go(OrderFlow.OPTIONS);
            }), 16, dp(54));
            addStoreCell(grid, card, index++);
        }
        while (index % 3 != 0) addStoreCell(grid, new View(this), index++);
        add(content, grid, 16, -2);
        addContentLimitNotice(content);
        footerAction(count() == 0 ? "메뉴를 담아주세요" : count() + "개 담김  장바구니 보기", true, count() > 0, () -> go(OrderFlow.REVIEW));
    }

    /** Menu photo with a plain placeholder; the bitmap is filled in when it arrives. */
    private android.widget.ImageView menuPhoto(BrandMenuFetcher.Entry entry) {
        android.widget.ImageView photo = new android.widget.ImageView(this);
        photo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        photo.setBackgroundColor(BG);
        photo.setContentDescription(entry.name);
        final String url = entry.imageUrl;
        if (url != null && !url.isEmpty()) {
            photo.setTag(url);
            images.load(url, bitmap -> runOnUiThread(() -> {
                if (!destroyed && url.equals(photo.getTag())) photo.setImageBitmap(bitmap);
            }));
        }
        return photo;
    }

    private PichleApi.MenuItem toMenuItem(BrandMenuFetcher.Entry entry) {
        boolean starbucks = selected != null && selected.name.contains("스타벅스");
        boolean burger = entry.section.equals("버거") || entry.section.equals("맥런치") || entry.section.equals("맥모닝");
        String options = starbucks ? (entry.drink ? "Tall, Grande, Venti, 샷 추가, 얼음 적게, 시럽 적게" : "데워주세요, 개별 포장")
                : burger ? "단품으로, 소스 추가, 피클 빼고, 양파 빼고"
                : entry.drink ? "Medium, Large, 얼음 적게" : "소스 추가";
        return new PichleApi.MenuItem((entry.drink ? "d:" : "f:") + entry.name, entry.name, 0, options);
    }

    private void loadPlaceMenu() {
        placeMenu = Collections.emptyList(); placeTab = null;
        final int request = ++placeMenuRequest;
        if (selected == null || !BrandMenuFetcher.supports(selected.name)) { placeMenuStatus = ""; return; }
        placeMenuStatus = "메뉴를 불러오고 있어요";
        final String name = selected.name;
        menuWorker.execute(() -> {
            List<BrandMenuFetcher.Entry> loaded = Collections.emptyList(); String error = "";
            try { loaded = brandMenus.load(name); }
            catch (Exception e) { error = "메뉴를 불러오지 못했어요"; }
            final List<BrandMenuFetcher.Entry> result = loaded; final String message = error;
            runOnUiThread(() -> {
                if (destroyed || request != placeMenuRequest) return;
                placeMenu = result; placeMenuStatus = message;
                menu.clear();
                for (BrandMenuFetcher.Entry entry : result) menu.add(toMenuItem(entry));
                if (foreground) render();
            });
        });
    }

    private void showMenu() {
        if (fromNaver(selected)) { showPlaceDetail(); return; }
        page(selected.name, null);
        LinearLayout group = itemGroup();
        for (PichleApi.MenuItem item : menu) {
            if (!claimContentItem("menu:" + selected.id + ":" + item.id)) break;
            LinearLayout card = card();
            card.addView(text(item.name, 23, INK, true));
            addText(card, money(item.priceKrw), 22, INK, true, 14);
            addText(card, item.optionSchema, 16, MUTED, false, 12);
            Button pick = button("선택", false, () -> {
                draftMenu = item; draftQuantity = 1; draftOptions.clear(); draftNote = ""; go(OrderFlow.OPTIONS);
            });
            add(card, pick, 24, dp(58)); addGrouped(group, card);
        }
        content.addView(group);
        addContentLimitNotice(content);
        footerAction(count() == 0 ? "메뉴를 담아주세요" : count() + "개 · " + money(total()) + "  장바구니 보기",
                true, count() > 0, () -> go(OrderFlow.REVIEW));
    }

    private void showOptions() {
        page(draftMenu.name, priced() ? money(draftMenu.priceKrw) : null);
        LinearLayout options = card(); options.addView(text("옵션", 21, INK, true));
        for (String raw : draftMenu.optionSchema.split(",")) {
            String choice = raw.trim(); if (choice.isEmpty()) continue;
            if (!claimContentItem("option:" + selected.id + ":" + draftMenu.id + ":" + choice)) break;
            Button option = button((draftOptions.contains(choice) ? "✓  " : "") + choice, draftOptions.contains(choice), () -> { });
            option.setOnClickListener(view -> {
                if (!canEditOrder()) return;
                if (!draftOptions.remove(choice)) draftOptions.add(choice);
                option.setText(uxText((draftOptions.contains(choice) ? "✓  " : "") + choice));
                styleButton(option, draftOptions.contains(choice));
            });
            add(options, option, 12, dp(54));
        }
        content.addView(options);
        addContentLimitNotice(content);
        LinearLayout quantity = card();
        LinearLayout quantityRow = row();
        quantityRow.addView(text("수량", 21, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button less = button("−", false, () -> { });
        TextView value = text(String.valueOf(draftQuantity), 23, INK, true);
        value.setGravity(Gravity.CENTER);
        Button more = button("+", false, () -> { });
        less.setContentDescription("수량 줄이기"); more.setContentDescription("수량 늘리기");
        quantityRow.addView(less, params(dp(64), dp(56)));
        quantityRow.addView(value, params(dp(72), dp(56)));
        quantityRow.addView(more, params(dp(64), dp(56)));
        less.setOnClickListener(view -> { if (!canEditOrder()) return; if (draftQuantity > 1) draftQuantity--; value.setText(uxText(String.valueOf(draftQuantity))); updateAddFooter(); });
        more.setOnClickListener(view -> { if (!canEditOrder()) return; if (draftQuantity < 20) draftQuantity++; value.setText(uxText(String.valueOf(draftQuantity))); updateAddFooter(); });
        quantity.addView(quantityRow); add(content, quantity, 14, -2);
        LinearLayout note = card(); note.addView(text("요청사항", 21, INK, true));
        optionNote = new EditText(this); optionNote.setText(uxText(draftNote)); optionNote.setTextSize(18);
        optionNote.setTag(optionNote.getText().toString());
        if (uxState != null && uxState.limitsStrings()) optionNote.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(Math.max(0, uxState.maxStringLength))});
        optionNote.setHint(uxText("예: 얼음 적게")); optionNote.setTextColor(INK); optionNote.setHintTextColor(MUTED);
        optionNote.setSingleLine(false); optionNote.setMaxLines(3);
        optionNote.setPadding(dp(12), dp(12), dp(12), dp(12));
        applyKeyboardPolicy(optionNote);
        add(note, optionNote, 12, dp(96)); add(content, note, 14, -2);
        updateAddFooter();
    }

    private void updateAddFooter() {
        footer.removeAllViews();
        footerAction(draftQuantity + "개 담기" + (priced() ? " · " + money(draftMenu.priceKrw * draftQuantity) : ""), true, true, () -> {
            if (cart.size() >= 60) { toast("한 번에 메뉴 60개까지 담을 수 있습니다."); return; }
            saveDraftNote();
            String options = String.join(", ", draftOptions);
            if (!draftNote.trim().isEmpty()) options += (options.isEmpty() ? "" : ", ") + draftNote.trim();
            cart.add(new CartItem(draftMenu.id, draftMenu.name, draftMenu.priceKrw, draftQuantity, options));
            receipt = null; voiceDelivered = false;
            toast("장바구니에 담았습니다."); go(OrderFlow.MENU);
        });
    }

    private void showReview() {
        page("담은 메뉴를 확인해주세요", selected.name);
        if (cart.isEmpty()) {
            addText(content, "담은 메뉴가 없습니다.", 21, MUTED, false, 20);
            footerAction("메뉴 보기", true, true, () -> go(OrderFlow.MENU)); return;
        }
        for (int i = 0; i < cart.size(); i++) {
            final int index = i;
            CartItem item = cart.get(i);
            if (!claimContentItem("cart:" + selected.id + ":" + item.id + ":" + item.options)) break;
            LinearLayout card = card();
            LinearLayout heading = row();
            heading.addView(text(item.name + "  " + item.quantity + "개", 22, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            if (receipt == null) {
                Button remove = button("삭제", false, () -> { cart.remove(index); voiceDelivered = false; render(); });
                heading.addView(remove, params(dp(84), dp(48)));
            }
            card.addView(heading);
            if (!item.options.isEmpty()) addText(card, item.options, 17, MUTED, false, 10);
            if (priced()) addText(card, money(item.price * item.quantity), 22, INK, true, 18);
            add(content, card, i == 0 ? 0 : 12, -2);
        }
        addContentLimitNotice(content);
        if (receipt == null) add(content, button("+ 메뉴 추가", false, () -> go(OrderFlow.MENU)), 16, dp(54));
        if (priced()) addTotal(content);
        if (receipt != null) footerAction("주문 현황 보기", true, true, () -> go(OrderFlow.SEND));
        else footerChoices("길안내", this::startTravel, "주문하기", () -> {
            arrived = false;
            openOrder();
        });
    }

    private void openOrder() {
        if (!canEditOrder() || selected == null || cart.isEmpty()) return;
        if (receipt != null) go(OrderFlow.SEND);
        else go(OrderFlow.METHOD);
    }

    private void showMethod() {
        if (cart.isEmpty()) { go(OrderFlow.REVIEW); return; }
        page("어떻게 주문할까요?", selected.name);
        LinearLayout group = itemGroup();
        LinearLayout electronic = card();
        electronic.addView(text("앱으로 주문", 28, INK, true));
        add(electronic, button("주문 확인하기", true, () -> { method = "electronic"; go(OrderFlow.SEND); }), 32, dp(68));
        addGrouped(group, electronic);
        LinearLayout voice = card(); voice.addView(text("음성으로 주문", 28, INK, true));
        add(voice, button("음성으로 주문하기", false, () -> { method = "voice"; go(OrderFlow.VOICE); }), 32, dp(68));
        addGrouped(group, voice); content.addView(group);
    }

    private void showSend() {
        if (cart.isEmpty()) { go(OrderFlow.REVIEW); return; }
        page(receipt != null ? orderStatusTitle() : submitting ? "주문을 보내고 있어요" : "주문을 확인해주세요", selected.name);
        if (receipt != null) {
            addOrderStatusCard(content, false);
            orderSummary(content); if (priced()) addTotal(content);
            if ("CANCELLED".equals(receipt.status)) {
                footerAction("다시 주문하기", true, true, () -> {
                    receipt = null; orderError = ""; orderStatusReconnecting = false;
                    pendingOrderRequestId = ""; pendingOrderFingerprint = "";
                    go(OrderFlow.REVIEW);
                });
            } else if ("COMPLETED".equals(receipt.status)) {
                footerAction("확인", true, !pickupFinishing, this::completePickup);
            } else if ("READY".equals(receipt.status)) {
                footerAction("수령하기", true, true, () -> go(OrderFlow.PICKUP));
            } else footerAction("매장으로 이동", true, true, () -> go(OrderFlow.TRAVEL));
        } else {
            orderSummary(content); if (priced()) addTotal(content);
            if (!orderError.isEmpty()) addText(content, orderError, 18, INK, false, 16);
            footerAction(submitting ? "보내는 중…" : (priced() ? money(total()) + "  " : "") + "주문하기", true, !submitting, this::submitOrder);
        }
    }

    private String orderStatusTitle() {
        switch (receipt.status) {
            case "PREPARING": return "주문이 접수됐어요";
            case "READY": return "주문하신 메뉴가 준비됐어요";
            case "COMPLETED": return "수령이 완료됐어요";
            case "CANCELLED": return "주문이 취소됐어요";
            default: return "매장에서 주문을 확인하고 있어요";
        }
    }

    private String orderStatusMessage() {
        switch (receipt.status) {
            case "PREPARING": return "매장에서 메뉴를 준비하고 있어요.";
            case "READY": return "수령 코드를 매장에 보여주세요.";
            case "COMPLETED": return "";
            case "CANCELLED": return "주문 내역을 확인해주세요.";
            default: return "잠시만 기다려주세요.";
        }
    }

    private void addOrderStatusCard(LinearLayout parent, boolean includeTitle) {
        LinearLayout status = card();
        if (includeTitle) status.addView(text(orderStatusTitle(), 24, INK, true));
        String message = orderStatusMessage();
        if (!message.isEmpty()) addText(status, message, 22, INK, true, includeTitle ? 12 : 0);
        if (!"CANCELLED".equals(receipt.status)) {
            int step = "RECEIVED".equals(receipt.status) ? 0 : "PREPARING".equals(receipt.status) ? 1 : 2;
            LinearLayout steps = row();
            String[] labels = {"주문 확인", "메뉴 준비", "준비 완료"};
            for (int i = 0; i < labels.length; i++) {
                TextView label = text(labels[i], 17, i <= step ? PRIMARY : MUTED, i <= step);
                label.setGravity(Gravity.CENTER);
                steps.addView(label, new LinearLayout.LayoutParams(0, dp(44), 1));
            }
            add(status, steps, 18, -2);
        }
        addText(status, "수령 코드", 17, MUTED, false, 22);
        addText(status, receipt.pickupCode, 46, INK, true, 12);
        orderConnectionView = addText(status, "주문 상태를 다시 확인하고 있어요", 16, MUTED, false, 16);
        orderConnectionView.setVisibility(orderStatusReconnecting ? View.VISIBLE : View.GONE);
        parent.addView(status);
    }

    private boolean shouldCheckOrderStatus() {
        return foreground && !destroyed && receipt != null && ordersClient != null
                && !"COMPLETED".equals(receipt.status) && !"CANCELLED".equals(receipt.status);
    }

    private void scheduleOrderStatus(long delay) {
        orderStatusHandler.removeCallbacks(orderStatusTick);
        if (shouldCheckOrderStatus()) orderStatusHandler.postDelayed(orderStatusTick, delay);
    }

    private void refreshOrderStatus() {
        if (!shouldCheckOrderStatus() || orderStatusFetching) return;
        final String orderId = receipt.orderId;
        orderStatusFetching = true;
        orderWorker.execute(() -> {
            PichleApi.OrderReceipt fetched = null;
            try { fetched = ordersClient.getOrderStatus(orderId); }
            catch (java.io.IOException | RuntimeException ignored) { }
            final PichleApi.OrderReceipt result = fetched;
            runOnUiThread(() -> {
                orderStatusFetching = false;
                if (destroyed) return;
                if (receipt == null || !orderId.equals(receipt.orderId)) { scheduleOrderStatus(0); return; }
                orderStatusReconnecting = result == null;
                if (result != null) {
                    boolean changed = !receipt.status.equals(result.status) || !receipt.pickupCode.equals(result.pickupCode);
                    receipt = result;
                    if (foreground && changed) {
                        if (screen == OrderFlow.SEND || screen == OrderFlow.TRAVEL || screen == OrderFlow.PICKUP) render();
                        else toast(orderStatusTitle());
                    }
                }
                if (foreground && orderConnectionView != null)
                    orderConnectionView.setVisibility(orderStatusReconnecting ? View.VISIBLE : View.GONE);
                scheduleOrderStatus(3000);
            });
        });
    }

    private void submitOrder() {
        if (!canEditOrder() || submitting || receipt != null || cart.isEmpty()
                || selected == null || ordersClient == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 37 && ordersClient.needsLocalNetworkPermission()
                && checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            if (uxState == null || !uxState.setupAllowed()) {
                orderError = "서버 연결 권한은 주차 후 허용해주세요.";
                render(); return;
            }
            try { orderNetworkPermission.launch(LOCAL_NETWORK_PERMISSION); }
            catch (RuntimeException error) { orderError = "서버 연결 권한을 확인해주세요."; render(); }
            return;
        }
        final PichleApi.OrderRequest request;
        try {
            ArrayList<PichleApi.OrderLine> items = new ArrayList<>();
            for (CartItem item : cart) items.add(new PichleApi.OrderLine(item.id, item.name,
                    item.quantity, priced() ? Integer.valueOf(item.price) : null, item.options));
            PichleApi.OrderRequest draft = new PichleApi.OrderRequest("", selected.id, selected.name, items, "");
            String fingerprint = DillOrderClient.encodeRequest(draft);
            if (pendingOrderRequestId.isEmpty() || !fingerprint.equals(pendingOrderFingerprint)) {
                pendingOrderRequestId = java.util.UUID.randomUUID().toString();
                pendingOrderFingerprint = fingerprint;
            }
            request = new PichleApi.OrderRequest(pendingOrderRequestId, selected.id, selected.name, items, "");
        } catch (java.io.IOException | RuntimeException error) {
            orderError = "주문 내용을 확인하고 다시 전송해주세요."; render(); return;
        }
        submitting = true; orderError = ""; render();
        orderWorker.execute(() -> {
            PichleApi.OrderReceipt accepted = null;
            String failure = "";
            try {
                accepted = ordersClient.submitOrder(request);
            } catch (Exception error) {
                failure = error instanceof java.io.IOException && error.getMessage() != null
                        ? error.getMessage() : "주문을 전송하지 못했습니다. 다시 시도해주세요.";
            }
            final PichleApi.OrderReceipt result = accepted;
            final String message = failure;
            runOnUiThread(() -> {
                if (destroyed) return;
                submitting = false;
                if (result != null) {
                    receipt = result; orderError = ""; orderStatusReconnecting = false;
                    pendingOrderRequestId = ""; pendingOrderFingerprint = "";
                }
                else orderError = message;
                if (foreground) render();
                scheduleOrderStatus(1000);
            });
        });
    }

    private void showVoice() {
        if (cart.isEmpty()) { go(OrderFlow.REVIEW); return; }
        page("주문 내용을 읽어드릴게요", selected.name);
        LinearLayout player = card();
        player.addView(text("주문 읽어주기", 28, INK, true));
        voiceStatusView = text(voiceStatus, 18, MUTED, false); add(player, voiceStatusView, 10, -2);
        LinearLayout controls = row();
        speechPlay = button("▶  주문 음성 재생", true, this::playOrderSpeech);
        speechStop = button("■  중지", false, () -> speech.stop());
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(0, dp(80), 3); playLp.setMarginEnd(dp(12));
        controls.addView(speechPlay, playLp); controls.addView(speechStop, new LinearLayout.LayoutParams(0, dp(80), 1));
        add(player, controls, 24, -2);
        windowStatusView = text(uxText(windowStatus), 17, MUTED, false); add(player, windowStatusView, 10, -2);
        content.addView(player);
        LinearLayout phrase = card();
        phrase.addView(text("주문 내용", 19, MUTED, true));
        TextView spoken = addText(phrase, orderText(), 26, INK, true, 18);
        spoken.setLineSpacing(dp(8), 1); add(content, phrase, 16, -2);
        footerAction("수령하기", false, true, () -> {
            voiceDelivered = true;
            go(OrderFlow.PICKUP);
        });
        updateSpeechViews();
    }

    private void updateSpeechViews() {
        if (voiceStatusView != null) voiceStatusView.setText(uxText(voiceStatus));
        boolean busy = (speech != null && speech.isBusy()) || orderWindowPending;
        if (speechPlay != null) {
            speechPlay.setText(uxText(orderWindowPending ? "창문 여는 중…" : busy ? "음성 재생 중…" : "▶  주문하기 · 창문 열고 음성 재생"));
            boolean enabled = !busy && canEditOrder();
            speechPlay.setEnabled(enabled); speechPlay.setAlpha(enabled ? 1f : 0.6f);
        }
        if (speechStop != null) { speechStop.setEnabled(busy); speechStop.setAlpha(busy ? 1f : 0.4f); }
    }

    /** Navigation starts only after the user explicitly chooses it. */
    private void startTravel() {
        if (!canEditOrder() || !canOpenScreen(OrderFlow.TRAVEL)) return;
        go(OrderFlow.TRAVEL);
        navigateToSelectedStore();
    }

    private void navigateToSelectedStore() {
        if (!canEditOrder() || selected == null || navigation == null) return;
        navigation.navigate(selected.latitude, selected.longitude, selected.name, selected.address);
    }

    private void showTravel() {
        page("매장으로 이동해요", selected.name);
        LinearLayout destination = card();
        destination.addView(text(selected.name, 27, INK, true));
        addText(destination, selected.address, 17, MUTED, false, 10);
        addText(destination, "이동 약 " + selected.driveMinutes + "분", 32, INK, true, 28);
        if (!fromNaver(selected)) addText(destination, "매장 대기 약 " + selected.waitMinutes + "분", 18, MUTED, false, 10);
        navigationStatusView = text(navigationStatus, 17, MUTED, false);
        add(destination, navigationStatusView, 14, -2); content.addView(destination);
        if (receipt != null) {
            addOrderStatusCard(content, true);
            footerChoices("길안내", this::navigateToSelectedStore,
                    "READY".equals(receipt.status) ? "수령하기" : "주문 현황", () -> {
                arrived = true;
                go("READY".equals(receipt.status) ? OrderFlow.PICKUP : OrderFlow.SEND);
            });
            return;
        }
        footerChoices("길안내", this::navigateToSelectedStore,
                receipt == null && !voiceDelivered ? "주문하기" : "수령하기", () -> {
            arrived = true;
            if (receipt == null && !voiceDelivered) openOrder();
            else go(OrderFlow.PICKUP);
        });
    }

    private void showPickup() {
        if (cart.isEmpty()) { go(OrderFlow.STORES); return; }
        if (receipt != null && !"READY".equals(receipt.status)) { showSend(); return; }
        page("메뉴를 받아주세요", selected.name);
        if (receipt != null) {
            addOrderStatusCard(content, false);
        }
        LinearLayout window = card();
        window.addView(text("운전석 창문", 24, INK, true));
        add(window, button("창문 열기", true, this::openPickupWindow), 24, dp(76));
        windowStatusView = text(windowStatus, 17, MUTED, false); add(window, windowStatusView, 14, -2);
        add(content, window, receipt == null ? 0 : 16, -2);
        orderSummary(content);
        footerAction(pickupFinishing ? "창문 닫는 중…" : "수령 완료", false, !pickupFinishing, this::completePickup);
    }

    private void goBack() {
        if (reducedUi()) { moveTaskToBack(true); return; }
        uxGoingBack = true;
        try { switch (screen) {
            case MENU: go(OrderFlow.STORES); break;
            case OPTIONS: go(OrderFlow.MENU); break;
            case REVIEW: go(OrderFlow.MENU); break;
            case METHOD: go(OrderFlow.REVIEW); break;
            case SEND: go(receipt == null ? OrderFlow.METHOD : OrderFlow.REVIEW); break;
            case VOICE: go(arrived ? OrderFlow.TRAVEL : OrderFlow.METHOD); break;
            case TRAVEL: go(receipt != null ? OrderFlow.SEND : OrderFlow.REVIEW); break;
            case PICKUP: go(receipt == null ? OrderFlow.VOICE : OrderFlow.TRAVEL); break;
            default: moveTaskToBack(true);
        } } finally { uxGoingBack = false; }
    }

    private static final String[] NATIVE = {"", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열",
            "열한", "열두", "열세", "열네", "열다섯", "열여섯", "열일곱", "열여덟", "열아홉", "스무"};

    /** The line spoken at the order window: greeting, each item with a Korean counter, closing. */
    private String orderText() {
        ArrayList<String> phrases = new ArrayList<>();
        for (CartItem item : cart) {
            String unit = item.id.startsWith("d:") ? "잔" : "개";
            String count = item.quantity < NATIVE.length ? NATIVE[item.quantity] + " " + unit : item.quantity + unit;
            String name = item.name.replaceAll("[®™]", "");
            String options = item.options.replace("Tall", "톨").replace("Grande", "그란데").replace("Venti", "벤티")
                    .replace("Medium", "미디엄").replace("Large", "라지");
            phrases.add(name + " " + count + " 주세요" + (options.isEmpty() ? "" : ". 옵션은 " + options + "입니다"));
        }
        return "안녕하세요. 주문할게요. " + String.join(". ", phrases) + ". 이상입니다. 감사합니다.";
    }

    private void orderSummary(LinearLayout parent) {
        LinearLayout summary = card(); summary.addView(text("주문 내역", 20, INK, true));
        for (CartItem item : cart) {
            if (!claimContentItem("cart:" + selected.id + ":" + item.id + ":" + item.options)) break;
            LinearLayout line = row();
            line.addView(text(item.name + " " + item.quantity + "개", 18, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
            if (priced()) line.addView(text(money(item.price * item.quantity), 18, INK, true));
            add(summary, line, 18, -2);
            if (!item.options.isEmpty()) addText(summary, item.options, 15, MUTED, false, 6);
        }
        addContentLimitNotice(summary);
        add(parent, summary, 16, -2);
    }

    private void addTotal(LinearLayout parent) {
        LinearLayout total = card(), line = row();
        line.addView(text("합계", 22, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(text(money(total()), 26, INK, true)); total.addView(line); add(parent, total, 16, -2);
    }

    private void footerAction(String label, boolean primary, boolean enabled, Runnable action) {
        footer.setVisibility(View.VISIBLE);
        Button button = button(label, primary, action); button.setTextSize(20);
        button.setEnabled(enabled); button.setAlpha(enabled ? 1f : 0.4f);
        footer.addView(button, params(-1, dp(66)));
    }

    private void footerChoices(String secondaryLabel, Runnable secondaryAction,
                               String primaryLabel, Runnable primaryAction) {
        footer.setVisibility(View.VISIBLE);
        LinearLayout actions = row();
        Button secondary = button(secondaryLabel, false, secondaryAction);
        Button primary = button(primaryLabel, true, primaryAction);
        secondary.setTextSize(20); primary.setTextSize(20);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(66), 1);
        left.setMarginEnd(dp(12));
        actions.addView(secondary, left);
        actions.addView(primary, new LinearLayout.LayoutParams(0, dp(66), 1));
        footer.addView(actions, params(-1, -2));
    }

    private LinearLayout itemGroup() {
        LinearLayout group = column();
        if (getResources().getConfiguration().screenWidthDp >= 1000) group.setOrientation(LinearLayout.HORIZONTAL);
        return group;
    }

    private void addGrouped(LinearLayout parent, LinearLayout child) {
        LinearLayout.LayoutParams p = parent.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, -2, 1) : params(-1, -2);
        if (parent.getChildCount() > 0) {
            if (parent.getOrientation() == LinearLayout.HORIZONTAL) p.setMarginStart(dp(16)); else p.topMargin = dp(14);
        }
        parent.addView(child, p);
    }

    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout card() {
        LinearLayout c = column(); c.setPadding(dp(24), dp(22), dp(24), dp(22));
        GradientDrawable background = fill(SURFACE, 4); background.setStroke(dp(1), LINE); c.setBackground(background); return c;
    }
    private Button button(String label, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(uxText(label)); b.setTextSize(18); b.setAllCaps(false); b.setTypeface(null, Typeface.BOLD);
        b.setPadding(dp(14), dp(6), dp(14), dp(6)); b.setMinWidth(0); b.setMinimumWidth(0); b.setElevation(0);
        styleButton(b, primary); b.setOnClickListener(view -> {
            if (!foreground || destroyed) return;
            if (!canEditOrder()) { render(); return; }
            action.run();
        }); return b;
    }
    private void styleButton(Button b, boolean primary) {
        b.setBackgroundTintList(null);
        GradientDrawable background = fill(primary ? PRIMARY : SURFACE, 3);
        if (!primary) background.setStroke(dp(1), LINE);
        b.setBackground(background); b.setTextColor(primary ? ON_PRIMARY : INK);
    }
    private TextView text(String label, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(uxText(label)); v.setTextSize(size); v.setTextColor(color);
        v.setIncludeFontPadding(false); if (bold) v.setTypeface(null, Typeface.BOLD); return v;
    }
    private TextView addText(LinearLayout parent, String label, int size, int color, boolean bold, int margin) {
        TextView v = text(label, size, color, bold); add(parent, v, margin, -2); return v;
    }
    private void add(LinearLayout parent, View child, int top, int height) {
        LinearLayout.LayoutParams p = params(-1, height); p.topMargin = dp(top); parent.addView(child, p);
    }
    private GradientDrawable fill(int color, int radius) { GradientDrawable b = new GradientDrawable(); b.setColor(color); b.setCornerRadius(dp(radius)); return b; }
    private LinearLayout.LayoutParams params(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private boolean priced() { return !fromNaver(selected); }
    private String money(int value) { return String.format(Locale.KOREA, "%,d원", value); }
    private int total() { int n = 0; for (CartItem i : cart) n += i.price * i.quantity; return n; }
    private int count() { int n = 0; for (CartItem i : cart) n += i.quantity; return n; }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void saveDraftNote() {
        if (optionNote == null) return;
        String visible = optionNote.getText().toString();
        // Keep the original draft when a newly restricted view only displays a shortened version.
        if (!visible.equals(optionNote.getTag())) draftNote = visible;
    }

    private void restoreState(Bundle state) {
        if (state == null) return;
        pendingOrderRequestId = state.getString("pendingOrderRequestId", "");
        pendingOrderFingerprint = state.getString("pendingOrderFingerprint", "");
        orderError = state.getString("orderError", "");
        try { screen = OrderFlow.valueOf(state.getString("page", "STORES")); } catch (IllegalArgumentException ignored) { }
        for (PichleApi.StoreSummary store : stores) if (store.id.equals(state.getString("storeId"))) selected = store;
        Bundle savedStore = state.getBundle("selectedStore");
        if (savedStore != null) selected = new PichleApi.StoreSummary(savedStore.getString("id", ""),
                savedStore.getString("name", ""), savedStore.getString("category", ""),
                savedStore.getInt("distance"), savedStore.getInt("drive"), savedStore.getInt("wait"),
                savedStore.getBoolean("electronic"), savedStore.getDouble("latitude"),
                savedStore.getDouble("longitude"), savedStore.getString("address", ""));
        loadMenu();
        ArrayList<Bundle> savedCart = state.getParcelableArrayList("cart");
        if (selected != null && savedCart != null) for (Bundle item : savedCart) cart.add(CartItem.from(item));
        String orderId = state.getString("orderId");
        if (orderId != null && selected != null && !cart.isEmpty()) receipt = new PichleApi.OrderReceipt(orderId,
                state.getString("orderStatus", "RECEIVED"), state.getString("pickupCode", ""));
        method = state.getString("method", ""); category = state.getString("category", "전체");
        arrived = state.getBoolean("arrived"); voiceDelivered = state.getBoolean("voiceDelivered");
        for (PichleApi.MenuItem item : menu) if (item.id.equals(state.getString("draftId"))) draftMenu = item;
        draftQuantity = Math.max(1, Math.min(20, state.getInt("draftQuantity", 1)));
        draftNote = state.getString("draftNote", "");
        ArrayList<String> choices = state.getStringArrayList("draftOptions"); if (choices != null) draftOptions.addAll(choices);
        navigationStatus = state.getString("navigationStatus", ""); windowStatus = state.getString("windowStatus", "");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        saveDraftNote(); state.putString("page", screen.name());
        state.putString("pendingOrderRequestId", pendingOrderRequestId);
        state.putString("pendingOrderFingerprint", pendingOrderFingerprint);
        state.putString("orderError", orderError);
        if (selected != null) state.putString("storeId", selected.id);
        if (selected != null) {
            Bundle savedStore = new Bundle();
            savedStore.putString("id", selected.id); savedStore.putString("name", selected.name);
            savedStore.putString("category", selected.category); savedStore.putString("address", selected.address);
            savedStore.putInt("distance", selected.distanceMeters); savedStore.putInt("drive", selected.driveMinutes);
            savedStore.putInt("wait", selected.waitMinutes); savedStore.putBoolean("electronic", selected.electronicOrdering);
            savedStore.putDouble("latitude", selected.latitude); savedStore.putDouble("longitude", selected.longitude);
            state.putBundle("selectedStore", savedStore);
        }
        ArrayList<Bundle> items = new ArrayList<>(); for (CartItem item : cart) items.add(item.save());
        state.putParcelableArrayList("cart", items);
        if (receipt != null) {
            state.putString("orderId", receipt.orderId); state.putString("pickupCode", receipt.pickupCode);
            state.putString("orderStatus", receipt.status);
        }
        state.putString("method", method); state.putString("category", category);
        state.putBoolean("arrived", arrived); state.putBoolean("voiceDelivered", voiceDelivered);
        if (draftMenu != null) state.putString("draftId", draftMenu.id);
        state.putInt("draftQuantity", draftQuantity); state.putString("draftNote", draftNote);
        state.putStringArrayList("draftOptions", new ArrayList<>(draftOptions));
        state.putString("navigationStatus", navigationStatus); state.putString("windowStatus", windowStatus);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        if (!browseSelectedArea && nearby != null && !nearby.isBusy()) {
            nearbyLoaded = false;
            areaFallbackAttempted = false;
        }
        if (uxGate != null) { uxState = uxGate.getState(); uxGate.start(); }
        render();
        if (!reducedUi()) startNearby();
        scheduleOrderStatus(0);
    }

    @Override protected void onStop() {
        saveDraftNote();
        foreground = false;
        orderStatusHandler.removeCallbacks(orderStatusTick);
        if (speech != null) speech.stop();
        if (storeChangeDialog != null) { storeChangeDialog.dismiss(); storeChangeDialog = null; }
        if (uxGate != null) { uxGate.stop(); uxState = uxGate.getState(); }
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        orderStatusHandler.removeCallbacks(orderStatusTick);
        orderWorker.shutdown();
        if (uxGate != null) uxGate.close();
        if (nearby != null) nearby.close();
        brandMenus.close(); menuWorker.shutdownNow(); images.close();
        if (speech != null) { speech.setFinishListener(null); speech.close(); }
        if (navigation != null) navigation.close();
        if (windows != null) {
            if (orderWindowOpen || pickupWindowOpen) {
                // Ask for the window to be closed and keep the vehicle connection a little longer.
                windows.raiseDriverWindow((success, message) -> { });
                final PleosWindowController pending = windows;
                new android.os.Handler(getMainLooper()).postDelayed(pending::close, 20000);
            } else windows.close();
        }
        super.onDestroy();
    }

    private static final class CartItem {
        final String id, name, options; final int price, quantity;
        CartItem(String id, String name, int price, int quantity, String options) {
            this.id = id; this.name = name; this.price = price; this.quantity = quantity; this.options = options;
        }
        Bundle save() {
            Bundle b = new Bundle(); b.putString("id", id); b.putString("name", name); b.putString("options", options);
            b.putInt("price", price); b.putInt("quantity", quantity); return b;
        }
        static CartItem from(Bundle b) { return new CartItem(b.getString("id", ""), b.getString("name", ""),
                b.getInt("price"), Math.max(1, b.getInt("quantity", 1)), b.getString("options", "")); }
    }
}
