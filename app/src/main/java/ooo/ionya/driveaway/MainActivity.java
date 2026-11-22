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
    private final BrandMenuFetcher brandMenus = new BrandMenuFetcher();
    private final java.util.concurrent.ExecutorService menuWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private List<BrandMenuFetcher.Entry> placeMenu = Collections.emptyList();
    private String placeMenuStatus = "", placeTab;
    private int placeMenuRequest;
    private ActivityResultLauncher<String[]> locationPermission;
    private String nearbyArea, nearbyStatus = "";
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
        favorites.addAll(getPreferences(MODE_PRIVATE).getStringSet("favorites", Collections.emptySet()));
        dtOnly = getPreferences(MODE_PRIVATE).getBoolean("dtOnly", false);
        nearbyStatus = "현재 위치 주변 매장을 찾는 중입니다.";
        restoreState(state);
        locationPermission = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), grants -> {
            if (destroyed) return;
            if (checkSelfPermission(FusedLocationProbe.PERMISSION) == PackageManager.PERMISSION_GRANTED) startNearby();
            else useSampleStores("위치 권한이 없어 샘플 매장을 표시합니다.");
        });
        nearby = new NearbyFinder(this, new NearbyFinder.Listener() {
            @Override public void onStores(List<PichleApi.StoreSummary> found, String area) {
                if (destroyed) return;
                stores.clear(); stores.addAll(found);
                nearbyStores.clear(); nearbyStores.addAll(found);
                searchQuery = ""; searching = false;
                nearbyArea = area; nearbyLoaded = true;
                nearbyStatus = "네이버 지도 기준 · 가까운 순";
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
                toast(message);
                if (foreground) render();
            }
            @Override public void onError(String message) {
                if (destroyed) return;
                useSampleStores(message + " 샘플 매장을 표시합니다.");
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
        saveDraftNote();
        if (screen == OrderFlow.VOICE && next != OrderFlow.VOICE && speech != null) speech.stop();
        if (screen == OrderFlow.PICKUP && next != OrderFlow.PICKUP) closePickupWindowQuietly();
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
        if (!canEditOrder() || nearby == null || searching) return;
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
        if (nearbyLoaded || nearby == null || nearby.isBusy() || destroyed) return;
        if (checkSelfPermission(FusedLocationProbe.PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            nearby.start();
        } else if (!locationAsked && canEditOrder()) {
            locationAsked = true;
            try { locationPermission.launch(FusedLocationProbe.requiredPermissions()); }
            catch (RuntimeException error) { useSampleStores("위치 권한을 요청하지 못해 샘플 매장을 표시합니다."); }
        }
    }

    private void useSampleStores(String status) {
        stores.clear();
        try {
            stores.addAll(api.findNearbyStores("songdo"));
            stores.sort((a, b) -> Integer.compare(a.driveMinutes + a.waitMinutes, b.driveMinutes + b.waitMinutes));
        } catch (Exception error) { toast("매장 정보를 불러오지 못했습니다."); }
        nearbyArea = "송도동"; nearbyLoaded = true; nearbyStatus = status;
        if (foreground) render();
    }

    private static boolean fromNaver(PichleApi.StoreSummary store) {
        return store != null && store.id.startsWith(NearbyFinder.ID_PREFIX);
    }

    private boolean reducedUi() {
        return uxState != null && !uxState.canUseComplexUi();
    }

    private boolean canEditOrder() {
        return foreground && !destroyed && !reducedUi();
    }

    private String uxText(String value) {
        return uxState != null && uxState.connected ? uxState.limitText(value) : value;
    }

    private boolean drivingContentAllowed(int items) {
        return foreground && !destroyed && uxState != null && uxState.connected
                && uxState.contentAllowed() && (!uxState.limitsContent()
                || (uxState.maxDepth >= 1 && uxState.maxItems >= items));
    }

    private boolean preparedVoiceAllowed() {
        return selected != null && !cart.isEmpty() && "voice".equals(method)
                && drivingContentAllowed(2);
    }

    private void onUxChanged(CarUxGate.State next) {
        if (destroyed) return;
        saveDraftNote();
        uxState = next;
        if (reducedUi()) {
            developerTapCount = 0;
            View focused = getCurrentFocus();
            if (focused != null) {
                InputMethodManager input = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (input != null) input.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                focused.clearFocus();
            }
            if (storeChangeDialog != null) { storeChangeDialog.dismiss(); storeChangeDialog = null; }
            if (!preparedVoiceAllowed() && speech != null) speech.stop();
        }
        if (foreground) render();
        if (foreground && !reducedUi()) startNearby();
    }

    /** One fixed view: no menus, text entry, order transcript, or further view traversal. */
    private void showDrivingUi() {
        page("이동 · 음성", "메뉴 선택과 주문 변경은 주차 후 이용해주세요.");
        if (uxState == null || !uxState.connected) {
            addText(content, "차량 상태 확인 중", 26, INK, true, 8);
            addText(content, "연결이 확인되면 사용할 수 있습니다.", 18, MUTED, false, 14);
            return;
        }
        if (!drivingContentAllowed(1)) {
            addText(content, "주차 후 이용해주세요", 26, INK, true, 8);
            return;
        }
        if (selected == null) {
            addText(content, "선택한 매장이 없습니다", 26, INK, true, 8);
            return;
        }
        LinearLayout destination = card();
        TextView name = text(selected.name, 26, INK, true);
        name.setMaxLines(1); destination.addView(name);
        LinearLayout controls = row();
        Button navigate = button("길안내", true, () -> {
            if (!drivingContentAllowed(1) || selected == null) { render(); return; }
            navigation.navigate(selected.latitude, selected.longitude, selected.name, selected.address);
        });
        controls.addView(navigate, new LinearLayout.LayoutParams(0, dp(68), 1));
        if (preparedVoiceAllowed()) {
            speechPlay = button("주문 읽기", true, this::playOrderSpeech);
            speechStop = button("중지", false, () -> speech.stop());
            LinearLayout.LayoutParams play = new LinearLayout.LayoutParams(0, dp(68), 1);
            play.setMarginStart(dp(12)); controls.addView(speechPlay, play);
            LinearLayout.LayoutParams stop = new LinearLayout.LayoutParams(0, dp(68), 1);
            stop.setMarginStart(dp(12)); controls.addView(speechStop, stop);
        }
        add(destination, controls, 18, -2);
        navigationStatusView = text(navigationStatus, 17, MUTED, false);
        navigationStatusView.setMaxLines(1); add(destination, navigationStatusView, 10, -2);
        if (speechPlay != null) {
            voiceStatusView = text(voiceStatus, 17, MUTED, false);
            voiceStatusView.setMaxLines(1); add(destination, voiceStatusView, 8, -2);
        }
        content.addView(destination);
        updateSpeechViews();
    }

    private void playOrderSpeech() {
        if (!foreground || destroyed || (reducedUi() && !preparedVoiceAllowed())) return;
        if (speech.isBusy() || orderWindowPending) return;
        // Never move a window while the car is restricted (driving); just read the order then.
        if (reducedUi()) { speech.speak(orderText()); return; }
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
            toast("수령이 완료됐습니다."); go(OrderFlow.STORES);
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
        voiceStatusView = null; navigationStatusView = null; windowStatusView = null;
        speechPlay = null; speechStop = null; optionNote = null;
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
            Button resume = button(receipt == null ? "장바구니 " + count() : "접수 내역", false,
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
        page(!searchQuery.isEmpty() ? "\u2018" + searchQuery + "\u2019 검색 결과" : nearbyArea == null ? "주변 매장" : nearbyArea + " 주변 매장",
                searching ? "검색 중입니다." : !searchQuery.isEmpty() ? "네이버 지도 기준 · 현재 위치에서 가까운 순" : nearbyStatus);
        LinearLayout searchRow = row();
        android.widget.EditText searchBox = new android.widget.EditText(this);
        searchBox.setSingleLine(true); searchBox.setTextSize(18); searchBox.setText(searchDraft);
        searchBox.setHint("장소 검색 (예: 스타벅스, 김밥)"); searchBox.setTextColor(INK); searchBox.setHintTextColor(MUTED);
        searchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) { searchDraft = e.toString(); }
        });
        searchBox.setOnEditorActionListener((v, action, event) -> { runSearch(); return true; });
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0, dp(56), 1));
        Button searchGo = button("검색", true, this::runSearch);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(110), dp(56)); sp.setMarginStart(dp(10));
        searchRow.addView(searchGo, sp);
        if (!searchQuery.isEmpty()) {
            Button searchClear = button("지우기", false, this::clearSearch);
            LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(dp(110), dp(56)); cp2.setMarginStart(dp(10));
            searchRow.addView(searchClear, cp2);
        }
        add(content, searchRow, 8, -2);
        LinearLayout dtRow = row();
        dtRow.addView(text("드라이브스루(DT)만 보기", 19, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Switch dtSwitch = new Switch(this);
        dtSwitch.setChecked(dtOnly);
        dtSwitch.setContentDescription("드라이브스루 매장만 보기");
        dtSwitch.setThumbTintList(android.content.res.ColorStateList.valueOf(dtOnly ? PRIMARY : MUTED));
        dtSwitch.setTrackTintList(android.content.res.ColorStateList.valueOf(dtOnly ? PRIMARY : LINE));
        dtSwitch.setOnCheckedChangeListener((view, checked) -> {
            dtOnly = checked;
            getPreferences(MODE_PRIVATE).edit().putBoolean("dtOnly", checked).apply();
            render();
        });
        dtRow.addView(dtSwitch, new LinearLayout.LayoutParams(-2, dp(48)));
        add(content, dtRow, 4, -2);
        LinearLayout filters = row();
        for (String filter : new String[]{"전체", "식당", "커피", "버거", "샌드위치", "즐겨찾기"}) {
            Button chip = button(filter, filter.equals(category), () -> { category = filter; render(); });
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
            addText(card, naver ? "예상 이동시간 · 대기 정보 없음" : "이동 " + store.driveMinutes + "분 · 대기 " + store.waitMinutes + "분", 17, MUTED, false, 8);
            addText(card, naver ? (BrandMenuFetcher.supports(store.name) ? "공식 메뉴 보기" : "길안내 가능")
                    : store.electronicOrdering ? "전자 주문 가능" : "음성 주문", 15, PRIMARY, true, 18);
            card.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
            Button choose = button(naver && !BrandMenuFetcher.supports(store.name) ? "상세 · 길안내" : "메뉴 보기", true, () -> chooseStore(store));
            add(card, choose, 24, dp(58));
            addStoreCell(group, card, storeIndex++);
        }
        // Reserve empty columns so one or two filtered stores keep the same card width.
        while (storeIndex % 3 != 0) addStoreCell(group, new View(this), storeIndex++);
        add(content, group, 18, -2);
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
        if (!canEditOrder()) return;
        if (selected != null && !selected.id.equals(store.id) && !cart.isEmpty()) {
            storeChangeDialog = new AlertDialog.Builder(this).setTitle("매장을 변경할까요?")
                    .setMessage("현재 장바구니의 메뉴가 삭제됩니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("변경", (dialog, which) -> selectStore(store)).show();
        } else selectStore(store);
    }

    private void selectStore(PichleApi.StoreSummary store) {
        if (!canEditOrder()) return;
        if (selected == null || !selected.id.equals(store.id)) {
            cart.clear(); receipt = null; method = ""; arrived = false; voiceDelivered = false;
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

    /** Stores found on Naver map: reference menu (if known) and navigation only; no ordering. */
    private void showPlaceDetail() {
        page(selected.name, String.format(Locale.KOREA, "%.1fkm · 약 %d분", selected.distanceMeters / 1000.0, selected.driveMinutes)
                + " · " + selected.category);
        LinearLayout info = card();
        if (!selected.address.isEmpty()) addText(info, selected.address, 18, INK, false, 0);
        addText(info, "대기 시간·주문 가능 여부는 제공되지 않습니다.", 16, MUTED, false, 10);
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
            PichleApi.MenuItem item = toMenuItem(entry);
            LinearLayout card = card();
            card.addView(menuPhoto(entry), new LinearLayout.LayoutParams(-1, dp(150)));
            addText(card, entry.name, 21, INK, true, 12);
            addText(card, entry.detail.isEmpty() ? "칼로리 정보 없음" : entry.detail, 16, MUTED, false, 8);
            card.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
            add(card, button("선택", false, () -> {
                if (!canEditOrder()) return;
                draftMenu = item; draftQuantity = 1; draftOptions.clear(); draftNote = ""; go(OrderFlow.OPTIONS);
            }), 16, dp(54));
            addStoreCell(grid, card, index++);
        }
        while (index % 3 != 0) addStoreCell(grid, new View(this), index++);
        add(content, grid, 16, -2);
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
        if (selected == null || !BrandMenuFetcher.supports(selected.name)) { placeMenuStatus = ""; return; }
        placeMenuStatus = "공식 메뉴를 불러오는 중입니다.";
        final int request = ++placeMenuRequest;
        final String name = selected.name;
        menuWorker.execute(() -> {
            List<BrandMenuFetcher.Entry> loaded = Collections.emptyList(); String error = "";
            try { loaded = brandMenus.load(name); }
            catch (Exception e) { error = e.getMessage() == null ? "메뉴를 불러오지 못했습니다." : e.getMessage(); }
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
        page(selected.name, "이동 " + selected.driveMinutes + "분 · 대기 " + selected.waitMinutes + "분"
                + " · " + (selected.electronicOrdering ? "전자 주문 가능" : "음성 주문"));
        LinearLayout group = itemGroup();
        for (PichleApi.MenuItem item : menu) {
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
        footerAction(count() == 0 ? "메뉴를 담아주세요" : count() + "개 · " + money(total()) + "  장바구니 보기",
                true, count() > 0, () -> go(OrderFlow.REVIEW));
    }

    private void showOptions() {
        page(draftMenu.name, priced() ? money(draftMenu.priceKrw) : "가격 정보 없음");
        LinearLayout options = card(); options.addView(text("옵션", 21, INK, true));
        for (String raw : draftMenu.optionSchema.split(",")) {
            String choice = raw.trim(); if (choice.isEmpty()) continue;
            Button option = button((draftOptions.contains(choice) ? "✓  " : "") + choice, draftOptions.contains(choice), () -> { });
            option.setOnClickListener(view -> {
                if (!canEditOrder()) return;
                if (!draftOptions.remove(choice)) draftOptions.add(choice);
                option.setText((draftOptions.contains(choice) ? "✓  " : "") + choice);
                styleButton(option, draftOptions.contains(choice));
            });
            add(options, option, 12, dp(54));
        }
        content.addView(options);
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
        less.setOnClickListener(view -> { if (!canEditOrder()) return; if (draftQuantity > 1) draftQuantity--; value.setText(String.valueOf(draftQuantity)); updateAddFooter(); });
        more.setOnClickListener(view -> { if (!canEditOrder()) return; if (draftQuantity < 20) draftQuantity++; value.setText(String.valueOf(draftQuantity)); updateAddFooter(); });
        quantity.addView(quantityRow); add(content, quantity, 14, -2);
        LinearLayout note = card(); note.addView(text("요청사항", 21, INK, true));
        optionNote = new EditText(this); optionNote.setText(draftNote); optionNote.setTextSize(18);
        optionNote.setHint("예: 얼음 적게"); optionNote.setTextColor(INK); optionNote.setHintTextColor(MUTED);
        optionNote.setSingleLine(false); optionNote.setMaxLines(3);
        optionNote.setPadding(dp(12), dp(12), dp(12), dp(12));
        add(note, optionNote, 12, dp(96)); add(content, note, 14, -2);
        updateAddFooter();
    }

    private void updateAddFooter() {
        footer.removeAllViews();
        footerAction(draftQuantity + "개 담기" + (priced() ? " · " + money(draftMenu.priceKrw * draftQuantity) : ""), true, true, () -> {
            saveDraftNote();
            String options = String.join(", ", draftOptions);
            if (!draftNote.trim().isEmpty()) options += (options.isEmpty() ? "" : ", ") + draftNote.trim();
            cart.add(new CartItem(draftMenu.id, draftMenu.name, draftMenu.priceKrw, draftQuantity, options));
            receipt = null; voiceDelivered = false;
            toast("장바구니에 담았습니다."); go(OrderFlow.MENU);
        });
    }

    private void showReview() {
        page("장바구니", selected.name);
        if (cart.isEmpty()) {
            addText(content, "담은 메뉴가 없습니다.", 21, MUTED, false, 20);
            footerAction("메뉴 보기", true, true, () -> go(OrderFlow.MENU)); return;
        }
        for (int i = 0; i < cart.size(); i++) {
            final int index = i;
            CartItem item = cart.get(i); LinearLayout card = card();
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
        if (receipt == null) add(content, button("+ 메뉴 추가", false, () -> go(OrderFlow.MENU)), 16, dp(54));
        if (priced()) addTotal(content);
        footerAction(receipt != null ? "접수 내역 보기" : selected.electronicOrdering ? "주문 방법 선택" : "매장으로 이동 · 길안내 시작",
                true, true, () -> {
                    if (receipt != null) go(OrderFlow.SEND);
                    else if (selected.electronicOrdering) go(OrderFlow.METHOD);
                    else { method = "voice"; startTravel(); }
                });
    }

    private void showMethod() {
        if (cart.isEmpty()) { go(OrderFlow.REVIEW); return; }
        page("주문 방법", selected.name + " · " + count() + "개 · " + money(total()));
        LinearLayout group = itemGroup();
        if (selected.electronicOrdering) {
            LinearLayout electronic = card();
            electronic.addView(text("매장으로 전송", 28, INK, true));
            addText(electronic, "선택한 주문을 매장에 보냅니다.", 18, MUTED, false, 14);
            add(electronic, button("주문 전송 화면으로", true, () -> { method = "electronic"; go(OrderFlow.SEND); }), 32, dp(68));
            addGrouped(group, electronic);
        }
        LinearLayout voice = card(); voice.addView(text("음성으로 주문", 28, INK, true));
        addText(voice, "주문대에서 차량 스피커로 읽어줍니다.", 18, MUTED, false, 14);
        add(voice, button("매장으로 이동 후 음성 주문", !selected.electronicOrdering, () -> { method = "voice"; startTravel(); }), 32, dp(68));
        addGrouped(group, voice); content.addView(group);
    }

    private void showSend() {
        if (cart.isEmpty()) { go(OrderFlow.REVIEW); return; }
        page(receipt == null ? "주문 전송" : "주문 접수 완료", selected.name);
        if (receipt != null) {
            LinearLayout result = card();
            result.addView(text("접수 번호", 19, MUTED, false));
            addText(result, receipt.pickupCode, 52, INK, true, 12);
            addText(result, "수령 창구에서 접수 번호를 확인해주세요.", 18, MUTED, false, 18);
            content.addView(result);
            orderSummary(content); addTotal(content);
            footerAction("매장으로 이동", true, true, () -> go(OrderFlow.TRAVEL));
        } else {
            LinearLayout target = card();
            target.addView(text(selected.name + "에 보낼 주문", 23, INK, true)); content.addView(target);
            orderSummary(content); addTotal(content);
            if (submitting) addText(content, "주문 전송 중…", 20, MUTED, false, 20);
            footerAction(submitting ? "전송 중…" : money(total()) + "  주문 전송", true, !submitting, this::submitOrder);
        }
    }

    private void submitOrder() {
        if (!canEditOrder() || submitting || receipt != null || cart.isEmpty()
                || selected == null || !selected.electronicOrdering) return;
        submitting = true; render();
        new android.os.Handler(getMainLooper()).post(() -> {
            if (destroyed) return;
            if (!canEditOrder()) { submitting = false; if (foreground) render(); return; }
            try {
                ArrayList<String> ids = new ArrayList<>();
                for (CartItem item : cart) for (int i = 0; i < item.quantity; i++) ids.add(item.id);
                receipt = api.submitOrder(new PichleApi.OrderRequest(selected.id, ids, orderText()));
            } catch (Exception error) { toast("주문을 전송하지 못했습니다. 다시 시도해주세요."); }
            submitting = false;
            if (screen == OrderFlow.SEND) render();
        });
    }

    private void showVoice() {
        if (cart.isEmpty()) { go(OrderFlow.REVIEW); return; }
        page("음성 주문", selected.name + (arrived ? " · 주문대 도착" : " · 주문 문장 확인"));
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
        addText(content, "정차 후 재생해주세요.", 16, MUTED, false, 16);
        footerAction(arrived ? "주문 전달 완료 · 수령으로" : "매장으로 이동", false, true, () -> {
            if (arrived) { voiceDelivered = true; go(OrderFlow.PICKUP); }
            else go(OrderFlow.TRAVEL);
        });
        updateSpeechViews();
    }

    private void updateSpeechViews() {
        if (voiceStatusView != null) voiceStatusView.setText(uxText(voiceStatus));
        boolean busy = (speech != null && speech.isBusy()) || orderWindowPending;
        if (speechPlay != null) {
            speechPlay.setText(uxText(reducedUi() ? (busy ? "재생 중" : "주문 읽기")
                    : (orderWindowPending ? "창문 여는 중…" : busy ? "음성 재생 중…" : "▶  주문하기 · 창문 열고 음성 재생")));
            boolean enabled = !busy && foreground && (!reducedUi() || preparedVoiceAllowed());
            speechPlay.setEnabled(enabled); speechPlay.setAlpha(enabled ? 1f : 0.6f);
        }
        if (speechStop != null) { speechStop.setEnabled(busy); speechStop.setAlpha(busy ? 1f : 0.4f); }
    }

    /** Go to the store first: start navigation, then the order is placed once at the window. */
    private void startTravel() {
        go(OrderFlow.TRAVEL);
        if (canEditOrder() && selected != null && navigation != null)
            navigation.navigate(selected.latitude, selected.longitude, selected.name, selected.address);
    }

    private void showTravel() {
        page("매장으로 이동", selected.name);
        LinearLayout destination = card();
        destination.addView(text(selected.name, 27, INK, true));
        addText(destination, selected.address, 17, MUTED, false, 10);
        addText(destination, "이동 약 " + selected.driveMinutes + "분", 32, INK, true, 28);
        if (!fromNaver(selected)) addText(destination, "매장 대기 약 " + selected.waitMinutes + "분", 18, MUTED, false, 10);
        add(destination, button("차량 내비게이션으로 길안내", true,
                () -> navigation.navigate(selected.latitude, selected.longitude, selected.name, selected.address)), 28, dp(72));
        navigationStatusView = text(navigationStatus, 17, MUTED, false);
        add(destination, navigationStatusView, 14, -2); content.addView(destination);
        if (receipt != null) {
            LinearLayout accepted = card();
            accepted.addView(text("접수 번호  " + receipt.pickupCode, 24, INK, true));
            addText(accepted, count() + "개 · " + money(total()), 17, MUTED, false, 10);
            add(content, accepted, 16, -2);
        } else {
            addText(content, "주문대에 도착하면 주문하기를 눌러 주세요. 창문이 열리고 주문 음성이 나옵니다.", 18, MUTED, false, 20);
        }
        footerAction(receipt == null && !voiceDelivered ? "주문대 도착 · 주문하기" : "매장 도착 · 수령하기", false, true, () -> {
            arrived = true;
            go(receipt == null && !voiceDelivered ? OrderFlow.VOICE : OrderFlow.PICKUP);
        });
    }

    private void showPickup() {
        if (cart.isEmpty()) { go(OrderFlow.STORES); return; }
        page("상품 수령", selected.name);
        if (receipt != null) {
            LinearLayout code = card(); code.addView(text("접수 번호", 18, MUTED, false));
            addText(code, receipt.pickupCode, 44, INK, true, 10); content.addView(code);
        }
        LinearLayout window = card();
        window.addView(text("운전석 창문", 24, INK, true));
        addText(window, "수령 창구에 정차한 후 눌러주세요. 수령 완료를 누르면 창문이 닫힙니다.", 18, MUTED, false, 12);
        add(window, button("수령 · 창문 열기", true, this::openPickupWindow), 24, dp(76));
        windowStatusView = text(windowStatus, 17, MUTED, false); add(window, windowStatusView, 14, -2);
        add(content, window, receipt == null ? 0 : 16, -2);
        orderSummary(content);
        footerAction(pickupFinishing ? "창문 닫는 중…" : "수령 완료", false, !pickupFinishing, this::completePickup);
    }

    private void goBack() {
        if (reducedUi()) { moveTaskToBack(true); return; }
        switch (screen) {
            case MENU: go(OrderFlow.STORES); break;
            case OPTIONS: go(OrderFlow.MENU); break;
            case REVIEW: go(OrderFlow.MENU); break;
            case METHOD: go(OrderFlow.REVIEW); break;
            case SEND: go(receipt == null ? OrderFlow.METHOD : OrderFlow.REVIEW); break;
            case VOICE: go(arrived ? OrderFlow.TRAVEL : selected.electronicOrdering ? OrderFlow.METHOD : OrderFlow.REVIEW); break;
            case TRAVEL: go(receipt != null ? OrderFlow.SEND : selected.electronicOrdering ? OrderFlow.METHOD : OrderFlow.REVIEW); break;
            case PICKUP: go(receipt == null ? OrderFlow.VOICE : OrderFlow.TRAVEL); break;
            default: moveTaskToBack(true);
        }
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
            LinearLayout line = row();
            line.addView(text(item.name + " " + item.quantity + "개", 18, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
            if (priced()) line.addView(text(money(item.price * item.quantity), 18, INK, true));
            add(summary, line, 18, -2);
            if (!item.options.isEmpty()) addText(summary, item.options, 15, MUTED, false, 6);
        }
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
        boolean requiresParkedUi = !reducedUi();
        Button b = new Button(this); b.setText(uxText(label)); b.setTextSize(18); b.setAllCaps(false); b.setTypeface(null, Typeface.BOLD);
        b.setPadding(dp(14), dp(6), dp(14), dp(6)); b.setMinWidth(0); b.setMinimumWidth(0); b.setElevation(0);
        styleButton(b, primary); b.setOnClickListener(view -> {
            if (!foreground || destroyed) return;
            if (requiresParkedUi && !canEditOrder()) { render(); return; }
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
    private void saveDraftNote() { if (optionNote != null) draftNote = optionNote.getText().toString(); }

    private void restoreState(Bundle state) {
        if (state == null) return;
        try { screen = OrderFlow.valueOf(state.getString("page", "STORES")); } catch (IllegalArgumentException ignored) { }
        for (PichleApi.StoreSummary store : stores) if (store.id.equals(state.getString("storeId"))) selected = store;
        loadMenu();
        ArrayList<Bundle> savedCart = state.getParcelableArrayList("cart");
        if (selected != null && savedCart != null) for (Bundle item : savedCart) cart.add(CartItem.from(item));
        String orderId = state.getString("orderId");
        if (orderId != null && selected != null && !cart.isEmpty()) receipt = new PichleApi.OrderReceipt(orderId, "RECEIVED", state.getString("pickupCode", ""));
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
        if (selected != null) state.putString("storeId", selected.id);
        ArrayList<Bundle> items = new ArrayList<>(); for (CartItem item : cart) items.add(item.save());
        state.putParcelableArrayList("cart", items);
        if (receipt != null) { state.putString("orderId", receipt.orderId); state.putString("pickupCode", receipt.pickupCode); }
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
        if (uxGate != null) uxGate.start();
        if (!reducedUi()) startNearby();
    }

    @Override protected void onStop() {
        saveDraftNote();
        foreground = false;
        if (speech != null) speech.stop();
        if (storeChangeDialog != null) { storeChangeDialog.dismiss(); storeChangeDialog = null; }
        if (uxGate != null) uxGate.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
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
