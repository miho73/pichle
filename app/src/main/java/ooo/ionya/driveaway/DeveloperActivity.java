package ooo.ionya.driveaway;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Hidden SDK console and connection settings. Probes run only after an explicit query. */
public final class DeveloperActivity extends AppCompatActivity {
    private static final int MAX_EVENTS = 80;
    private int background, surface, ink, muted, primary, onPrimary, divider;
    private String tab = "위치", domain = "전체", locationResult = "아직 조회하지 않았습니다.";
    private FusedLocationProbe location;
    private VehicleStateProbe vehicle;
    private LinearLayout body;
    private TextView locationView, eventView;
    private final Map<String, Result> results = new LinkedHashMap<>();
    private final Map<String, TextView> resultViews = new LinkedHashMap<>();
    private final ArrayDeque<JSONObject> events = new ArrayDeque<>();
    private ActivityResultLauncher<String[]> permissionRequest;
    private Runnable pendingLocationAction;
    private CarUxGate uxGate;
    private CarUxGate.State uxState;
    private boolean destroyed, foreground;
    private DillOrderClient orderClient;
    private String serverUrlDraft;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AppTaskIcon.apply(this);
        background = getColor(R.color.pichle_background); surface = getColor(R.color.pichle_surface);
        ink = getColor(R.color.pichle_text); muted = getColor(R.color.pichle_text_secondary);
        primary = getColor(R.color.pichle_primary); onPrimary = getColor(R.color.pichle_on_primary);
        divider = getColor(R.color.pichle_divider);
        restore(state);
        orderClient = new DillOrderClient(this);
        serverUrlDraft = state == null ? orderClient.getBaseUrl()
                : state.getString("serverUrlDraft", orderClient.getBaseUrl());
        permissionRequest = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), grants -> {
            Runnable action = pendingLocationAction; pendingLocationAction = null;
            if (!canUseDiagnostics()) return;
            boolean allowed = true;
            for (String permission : FusedLocationProbe.requiredPermissions())
                allowed &= checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
            if (allowed && action != null) action.run();
            else {
                locationResult = "위치 권한이 허용되지 않았습니다.\n" + FusedLocationProbe.PERMISSION;
                record("FusedLocation", "permission", "PERMISSION_DENIED", locationResult);
                if (locationView != null) locationView.setText(locationResult);
            }
        });
        location = new FusedLocationProbe(this, (title, detail) -> runOnUiThread(() -> {
            if (destroyed || !canUseDiagnostics()) return;
            locationResult = title + "\n" + detail;
            if (locationView != null) locationView.setText(locationResult);
            record("FusedLocation", title, "RESULT", detail);
        }));
        vehicle = new VehicleStateProbe(this, (id, status, value) -> runOnUiThread(() -> {
            if (destroyed || !canUseDiagnostics()) return;
            Result result = new Result(status, value, System.currentTimeMillis());
            results.put(id, result);
            TextView view = resultViews.get(id);
            if (view != null) view.setText(result.describe());
            record("Vehicle", id, status, value);
        }));
        Handler main = new Handler(getMainLooper());
        uxGate = new CarUxGate(this, stateNow -> main.post(() -> {
            if (destroyed) return;
            uxState = stateNow;
            if (!canUseDiagnostics()) suspendDiagnostics();
            render();
        }));
        uxState = uxGate.getState();
        render();
    }

    private void render() {
        locationView = null; eventView = null; resultViews.clear();
        LinearLayout root = column(); root.setBackgroundColor(background);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(root);
        LinearLayout header = row(); header.setPadding(dp(24), dp(12), dp(24), dp(12)); header.setBackgroundColor(surface);
        header.addView(text("개발자 모드", 26, ink, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (canUseDiagnostics() && !tab.equals("주문 서버")) header.addView(button("JSON 복사", false, this::copyResults), params(dp(144), dp(52)));
        Button close = button("닫기", false, this::finish);
        close.setOnClickListener(view -> finish());
        LinearLayout.LayoutParams closeLp = params(dp(96), dp(52)); closeLp.setMarginStart(dp(12)); header.addView(close, closeLp);
        root.addView(header, params(-1, -2));
        if (!canUseDiagnostics()) {
            LinearLayout message = column();
            message.setPadding(dp(28), dp(32), dp(28), dp(28));
            message.addView(text("차량 화면 제한 적용 중", 28, ink, true));
            add(message, text("상세 진단은 화면 제한이 해제되면 이용할 수 있습니다.", 19, muted, false), 14);
            root.addView(message, params(-1, -2));
            return;
        }
        LinearLayout tabs = row(); tabs.setPadding(dp(24), dp(16), dp(24), dp(16));
        for (String name : new String[]{"위치", "주행 정보", "결과 로그", "주문 서버"}) {
            Button select = button(name, name.equals(tab), () -> { tab = name; render(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(56), 1);
            if (tabs.getChildCount() > 0) p.setMarginStart(dp(8)); tabs.addView(select, p);
        }
        root.addView(tabs, params(-1, -2));
        ScrollView scroll = new ScrollView(this);
        body = column(); body.setPadding(dp(24), 0, dp(24), dp(24)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (tab.equals("위치")) locationPage();
        else if (tab.equals("주행 정보")) vehiclePage();
        else if (tab.equals("주문 서버")) orderServerPage();
        else logPage();
    }

    private void orderServerPage() {
        LinearLayout settings = panel();
        settings.addView(text("주문 서버", 24, ink, true));
        add(settings, text("피클 주문을 받을 dill 서버의 주소를 입력하세요.", 17, muted, false), 10);
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2048)});
        address.setTextSize(18); address.setTextColor(ink); address.setHintTextColor(muted);
        address.setHint(DillOrderClient.DEFAULT_BASE_URL); address.setText(serverUrlDraft);
        address.setPadding(dp(12), dp(12), dp(12), dp(12));
        address.setContentDescription("주문 서버 주소");
        address.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable value) { serverUrlDraft = value.toString(); }
        });
        add(settings, address, 18);
        LinearLayout actions = row();
        weighted(actions, button("저장", true, () -> {
            try {
                orderClient.setBaseUrl(serverUrlDraft);
                serverUrlDraft = orderClient.getBaseUrl();
                Toast.makeText(this, "주문 서버 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
                render();
            } catch (IllegalArgumentException error) { address.setError(error.getMessage()); }
        }));
        weighted(actions, button("기본값", false, () -> {
            orderClient.resetBaseUrl(); serverUrlDraft = orderClient.getBaseUrl(); render();
            Toast.makeText(this, "기본 서버 주소로 변경했습니다.", Toast.LENGTH_SHORT).show();
        }));
        add(settings, actions, 14); body.addView(settings, params(-1, -2));
        LinearLayout help = panel();
        help.addView(text("매니저 화면", 22, ink, true));
        TextView manager = raw(orderClient.getManagerUrl()); add(help, manager, 12);
        add(help, text("브라우저에서 매니저 화면에 로그인하면 접수된 주문을 확인할 수 있습니다.", 17, muted, false), 12);
        add(help, text("기본 주소는 https://pichle.ionya.ooo입니다. 기본값을 누르면 이 주소로 돌아갑니다.", 16, muted, false), 12);
        add(help, text("로컬 개발 서버를 사용할 때만 주소를 변경해주세요. HTTP는 지정된 개발용 호스트에만 허용됩니다.", 16, muted, false), 12);
        add(help, text("주소 저장 시 서버에 요청을 보내지 않습니다. 주문을 전송할 때 저장한 주소를 사용합니다.", 16, muted, false), 12);
        add(body, help, 16);
    }

    private void locationPage() {
        LinearLayout controls = panel();
        controls.addView(text("Fused Location", 24, ink, true));
        add(controls, text("현재 위치는 다음 위치 이벤트를 수신합니다. 최근 위치는 캐시된 값입니다.", 16, muted, false), 10);
        add(controls, text(FusedLocationProbe.PERMISSION + "  ·  "
                + (checkSelfPermission(FusedLocationProbe.PERMISSION) == PackageManager.PERMISSION_GRANTED ? "허용됨" : "조회 시 권한 요청"), 14, muted, false), 10);
        LinearLayout once = row();
        weighted(once, button("현재 위치 수신", true, () -> withLocationPermission(location::getCurrentLocation)));
        weighted(once, button("최근 위치 (캐시)", false, () -> withLocationPermission(location::getLastKnownLocation)));
        add(controls, once, 20);
        LinearLayout live = row();
        weighted(live, button("연속 수신 시작", false, () -> withLocationPermission(location::startUpdates)));
        weighted(live, button("수신 중지", false, location::stopUpdates)); add(controls, live, 10);
        body.addView(controls, params(-1, -2));
        LinearLayout result = panel(); result.addView(text("수신 결과", 22, ink, true));
        locationView = raw(locationResult); add(result, locationView, 16); add(body, result, 16);
        add(body, text("위치 응답은 이 화면과 복사한 JSON에서만 확인할 수 있으며 앱 서버로 전송하지 않습니다.", 15, muted, false), 16);
    }

    private void withLocationPermission(Runnable action) {
        if (!canUseDiagnostics() || pendingLocationAction != null) return;
        ArrayList<String> missing = new ArrayList<>();
        for (String permission : FusedLocationProbe.requiredPermissions())
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        if (missing.isEmpty()) { action.run(); return; }
        pendingLocationAction = action;
        try { permissionRequest.launch(missing.toArray(new String[0])); }
        catch (RuntimeException error) {
            pendingLocationAction = null;
            locationResult = "위치 권한을 요청하지 못했습니다.\n" + error;
            if (locationView != null) locationView.setText(locationResult);
            record("FusedLocation", "permission", "ERROR", locationResult);
        }
    }

    private void vehiclePage() {
        LinearLayout controls = panel();
        controls.addView(text("Vehicle State · " + vehicle.getQueries().size() + "개 조회", 24, ink, true));
        add(controls, text("API별 반환 타입·값·단위와 오류를 표시합니다. 크루즈 설정 속도는 실제 주행 속도와 다릅니다.", 16, muted, false), 10);
        LinearLayout buttons = row();
        weighted(buttons, button("전체 GET 조회", true, vehicle::readAll));
        weighted(buttons, button("조회 중지", false, vehicle::stopAll)); add(controls, buttons, 18);
        ArrayList<String> domains = new ArrayList<>(); domains.add("전체");
        for (VehicleStateProbe.Query q : vehicle.getQueries()) if (!domains.contains(q.domain)) domains.add(q.domain);
        Spinner picker = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, domains);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); picker.setAdapter(adapter);
        picker.setSelection(Math.max(0, domains.indexOf(domain)));
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!canUseDiagnostics()) return;
                String next = domains.get(position);
                if (!next.equals(domain)) { domain = next; render(); }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        add(controls, picker, 14); body.addView(controls, params(-1, -2));
        for (VehicleStateProbe.Query query : vehicle.getQueries()) {
            if (!domain.equals("전체") && !domain.equals(query.domain)) continue;
            LinearLayout card = panel(); LinearLayout title = row();
            title.addView(text(query.title, 21, ink, true), new LinearLayout.LayoutParams(0, -2, 1));
            title.addView(button("GET 조회", false, () -> vehicle.read(query.id)), params(dp(132), dp(52)));
            card.addView(title);
            add(card, text(query.domain + " · " + query.sdkMethod, 15, muted, false), 10);
            boolean allowed = checkSelfPermission(query.permission) == PackageManager.PERMISSION_GRANTED;
            add(card, text(query.permission + " · " + (allowed ? "허용됨" : "권한 없음")
                    + (query.unit == null || query.unit.isEmpty() ? "" : " · 단위 " + query.unit), 14, muted, false), 6);
            Result result = results.get(query.id);
            TextView value = raw(result == null ? "미조회" : result.describe());
            resultViews.put(query.id, value); add(card, value, 16); add(body, card, 12);
        }
    }

    private void logPage() {
        LinearLayout tools = row();
        weighted(tools, button("전체 결과 JSON 복사", true, this::copyResults));
        weighted(tools, button("결과 지우기", false, () -> {
            events.clear(); results.clear(); locationResult = "아직 조회하지 않았습니다."; render();
        }));
        body.addView(tools, params(-1, -2));
        add(body, text("최근 " + MAX_EVENTS + "개 이벤트 · SDK 응답과 오류를 그대로 기록", 16, muted, false), 16);
        eventView = raw(logText()); add(body, eventView, 16);
    }

    private void record(String source, String api, String status, String value) {
        if (destroyed || !canUseDiagnostics()) return;
        try {
            JSONObject event = new JSONObject();
            event.put("timeMillis", System.currentTimeMillis()); event.put("source", source);
            event.put("api", api); event.put("status", status); event.put("result", value);
            events.addFirst(event); while (events.size() > MAX_EVENTS) events.removeLast();
        } catch (JSONException ignored) { }
        if (eventView != null) eventView.setText(logText());
    }

    private String logText() {
        if (events.isEmpty()) return "기록된 결과가 없습니다.";
        StringBuilder output = new StringBuilder();
        for (JSONObject e : events) output.append(clock(e.optLong("timeMillis"))).append("  ")
                .append(e.optString("source")).append(" / ").append(e.optString("api"))
                .append(" / ").append(e.optString("status")).append('\n')
                .append(e.optString("result")).append("\n\n");
        return output.toString();
    }

    private JSONObject snapshot() throws JSONException {
        JSONObject json = new JSONObject(); json.put("capturedAtMillis", System.currentTimeMillis());
        json.put("location", locationResult);
        json.put("fusedLocationPermissionGranted", checkSelfPermission(FusedLocationProbe.PERMISSION) == PackageManager.PERMISSION_GRANTED);
        JSONArray values = new JSONArray();
        for (VehicleStateProbe.Query q : vehicle.getQueries()) {
            JSONObject entry = new JSONObject(); entry.put("id", q.id); entry.put("domain", q.domain);
            entry.put("api", q.sdkMethod); entry.put("permission", q.permission); entry.put("unit", q.unit);
            entry.put("permissionGranted", checkSelfPermission(q.permission) == PackageManager.PERMISSION_GRANTED);
            Result result = results.get(q.id);
            if (result != null) { entry.put("status", result.status); entry.put("result", result.value); entry.put("timeMillis", result.time); }
            else entry.put("status", "NOT_REQUESTED");
            values.put(entry);
        }
        json.put("vehicle", values); JSONArray log = new JSONArray(); for (JSONObject event : events) log.put(event);
        json.put("events", log); return json;
    }

    private void copyResults() {
        if (!canUseDiagnostics()) return;
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Pichle SDK 결과", snapshot().toString(2)));
                Toast.makeText(this, "JSON을 복사했습니다.", Toast.LENGTH_SHORT).show();
            }
        } catch (JSONException error) { Toast.makeText(this, "결과를 복사하지 못했습니다.", Toast.LENGTH_SHORT).show(); }
    }

    private void restore(Bundle state) {
        if (state == null) return;
        tab = state.getString("tab", "위치"); domain = state.getString("domain", "전체");
        locationResult = state.getString("location", locationResult);
        try {
            JSONArray saved = new JSONArray(state.getString("results", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.getJSONObject(i);
                String status = item.optString("status");
                if (status.equals("RUNNING")) status = "CANCELLED";
                results.put(item.getString("id"), new Result(status, item.optString("result"), item.optLong("timeMillis")));
            }
            JSONArray log = new JSONArray(state.getString("events", "[]"));
            for (int i = 0; i < Math.min(log.length(), MAX_EVENTS); i++) events.addLast(log.getJSONObject(i));
        } catch (JSONException ignored) { }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("tab", tab); state.putString("domain", domain); state.putString("location", locationResult);
        state.putString("serverUrlDraft", serverUrlDraft);
        JSONArray values = new JSONArray();
        for (Map.Entry<String, Result> entry : results.entrySet()) try {
            JSONObject item = new JSONObject(); item.put("id", entry.getKey()); item.put("status", entry.getValue().status);
            item.put("result", entry.getValue().value); item.put("timeMillis", entry.getValue().time); values.put(item);
        } catch (JSONException ignored) { }
        state.putString("results", values.toString());
        JSONArray log = new JSONArray(); for (JSONObject event : events) log.put(event); state.putString("events", log.toString());
        super.onSaveInstanceState(state);
    }

    private boolean canUseDiagnostics() {
        CarUxGate.State current = uxGate == null ? uxState : uxGate.getState();
        return foreground && current != null && current.canUseComplexUi();
    }

    private void suspendDiagnostics() {
        pendingLocationAction = null;
        if (location != null) location.stopUpdates();
        if (vehicle != null) vehicle.stopAll();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Result> entry : results.entrySet()) {
            if (entry.getValue().status.equals("RUNNING")) {
                entry.setValue(new Result("CANCELLED", "차량 상태 변경으로 조회를 중지했습니다.", now));
            }
        }
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        if (uxGate != null) { uxGate.start(); uxState = uxGate.getState(); }
        render();
    }

    @Override protected void onStop() {
        foreground = false;
        suspendDiagnostics();
        if (uxGate != null) uxGate.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (uxGate != null) uxGate.close();
        if (location != null) location.close();
        if (vehicle != null) vehicle.close();
        super.onDestroy();
    }

    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout panel() {
        LinearLayout v = column(); v.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable bg = fill(surface); bg.setStroke(dp(1), divider); v.setBackground(bg); return v;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(uxState != null && uxState.connected ? uxState.limitText(value) : value);
        v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(null, Typeface.BOLD); return v;
    }
    private TextView raw(String value) {
        TextView v = text(value, 16, ink, false); v.setTypeface(Typeface.MONOSPACE);
        v.setTextIsSelectable(true); v.setSaveEnabled(false); return v;
    }
    private Button button(String value, boolean selected, Runnable action) {
        Button b = new Button(this); b.setText(value); b.setTextSize(17); b.setAllCaps(false);
        b.setTextColor(selected ? onPrimary : ink); b.setBackgroundTintList(null);
        GradientDrawable bg = fill(selected ? primary : surface); if (!selected) bg.setStroke(dp(1), divider);
        b.setBackground(bg); b.setOnClickListener(view -> { if (canUseDiagnostics()) action.run(); }); return b;
    }
    private GradientDrawable fill(int color) { GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(3)); return bg; }
    private void weighted(LinearLayout parent, View child) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(58), 1);
        if (parent.getChildCount() > 0) p.setMarginStart(dp(10)); parent.addView(child, p);
    }
    private void add(LinearLayout parent, View child, int margin) { LinearLayout.LayoutParams p = params(-1, -2); p.topMargin = dp(margin); parent.addView(child, p); }
    private LinearLayout.LayoutParams params(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String clock(long time) { return new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new java.util.Date(time)); }

    private static final class Result {
        final String status, value; final long time;
        Result(String status, String value, long time) { this.status = status; this.value = value; this.time = time; }
        String describe() { return clock(time) + " · " + status + "\n" + value; }
    }
}
