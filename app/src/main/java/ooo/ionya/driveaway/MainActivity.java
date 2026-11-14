package ooo.ionya.driveaway;

import android.os.Bundle;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Full Vehicle SDK workbench: the catalog is discovered from every public domain interface. */
public class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(16, 24, 32), PANEL = Color.rgb(27, 39, 50);
    private static final int INK = Color.rgb(234, 242, 247), MUTED = Color.rgb(166, 186, 199), MINT = Color.rgb(117, 224, 192);
    private static final int EXPORT = 80;
    private ApiCatalog catalog;
    private ApiEngine engine;
    private final List<ApiCatalog.Api> visible = new ArrayList<>();
    private final Map<Integer, ValueReader> editors = new LinkedHashMap<>();
    private final Deque<ApiCatalog.Api> batch = new ArrayDeque<>();
    private final List<ApiEngine.Subscription> unregisterChoices = new ArrayList<>();
    private ApiCatalog.Api selected;
    private Spinner domainFilter, kindFilter, unregisterPicker;
    private EditText search;
    private TextView status, count, title, signature, permission, result, log, batchStatus;
    private LinearLayout form, subscriptions;
    private Button connect, disconnect, run, batchStart, batchStop;
    private ArrayAdapter<ApiCatalog.Api> apiAdapter;
    private int batchGeneration, batchTotal, batchPassed, batchFailed;
    private boolean batchRunning;
    private String exportSnapshot;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean refreshScheduled;
    private interface ValueReader { Object read() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { catalog = new ApiCatalog(this); }
        catch (Exception error) {
            TextView failure = new TextView(this);
            failure.setText("SDK 목록 로드 실패\n" + ApiEngine.describe(error));
            setContentView(failure);
            return;
        }
        engine = new ApiEngine(getApplicationContext(), message -> {
            if (refreshScheduled) return;
            refreshScheduled = true;
            uiHandler.postDelayed(() -> { refreshScheduled = false; refresh(); }, 100);
        });
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        page.addView(root);
        ViewCompat.setOnApplyWindowInsetsListener(page, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(page);
        text(root, "DRIVEAWAY  /  DEVELOPER TOOLS", 12, MINT);
        text(root, "Vehicle SDK 전체 기능 테스트", 28, INK).setTypeface(null, Typeface.BOLD);
        text(root, "Vehicle 2.3.2 · " + catalog.domains.size() + "개 도메인 · " + catalog.all.size() + "개 API 시그니처 (이전 버전 포함)", 14, MUTED);
        LinearLayout service = card(root, "차량 서비스");
        status = text(service, "연결 대기", 18, INK);
        LinearLayout connectionButtons = new LinearLayout(this);
        service.addView(connectionButtons);
        connect = button(connectionButtons, "연결", () -> engine.connect(catalog));
        disconnect = button(connectionButtons, "연결 해제", () -> { stopBatch(); engine.release(); });
        batchStatus = text(service, "일괄 검사는 지원 여부만 조회합니다. 제어는 선택한 API를 직접 실행합니다.", 13, MUTED);
        LinearLayout batchButtons = new LinearLayout(this);
        service.addView(batchButtons);
        batchStart = button(batchButtons, "전체 지원 기능 검사", this::startBatch);
        batchStop = button(batchButtons, "일괄 검사 중지", this::stopBatch);

        LinearLayout workspace = new LinearLayout(this);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 840;
        workspace.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(workspace, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout browse = card(workspace, "01  API 탐색");
        LinearLayout execute = card(workspace, "02  API 실행");
        if (wide) {
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 0.9f);
            left.topMargin = dp(16); left.setMarginEnd(dp(16));
            browse.setLayoutParams(left);
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1.1f);
            right.topMargin = dp(16); execute.setLayoutParams(right);
        }
        domainFilter = spinner(browse, prepend("전체 도메인", catalog.domains));
        kindFilter = spinner(browse, Arrays.asList("전체 API", "CAPABILITY · 지원 기능", "GET · 값 조회", "CONTROL · 값 제어", "REGISTER · 구독", "UNREGISTER · 구독 해제"));
        search = new EditText(this);
        search.setSingleLine(true); search.setTextColor(INK); search.setHintTextColor(MUTED);
        search.setHint("API 이름 또는 권한 검색");
        browse.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));
        count = text(browse, "", 13, MINT);
        ListView list = new ListView(this) {
            @Override public boolean onTouchEvent(android.view.MotionEvent event) {
                getParent().requestDisallowInterceptTouchEvent(event.getActionMasked() != android.view.MotionEvent.ACTION_UP
                        && event.getActionMasked() != android.view.MotionEvent.ACTION_CANCEL);
                return super.onTouchEvent(event);
            }
        };
        list.setDividerHeight(dp(1));
        apiAdapter = new ArrayAdapter<ApiCatalog.Api>(this, android.R.layout.simple_list_item_1, visible) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
                ApiCatalog.Api api = getItem(position);
                row.setText(api.domain + " · " + api.kind + (api.deprecated ? " · LEGACY" : "") + "\n" + api.method.getName()
                        + "\n" + argumentSummary(api));
                row.setTextColor(selected == api ? MINT : INK);
                row.setTextSize(13); row.setPadding(dp(10), dp(12), dp(10), dp(12));
                row.setTypeface(Typeface.MONOSPACE);
                row.setBackgroundColor(selected == api ? Color.rgb(40, 59, 69) : PANEL);
                return row;
            }
        };
        list.setAdapter(apiAdapter);
        browse.addView(list, new LinearLayout.LayoutParams(-1, dp(wide ? 520 : 300)));
        list.setOnItemClickListener((parent, view, position, id) -> select(visible.get(position)));
        AdapterView.OnItemSelectedListener filterListener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int index, long id) { filter(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        };
        domainFilter.setOnItemSelectedListener(filterListener);
        kindFilter.setOnItemSelectedListener(filterListener);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int n, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int n) { filter(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        title = text(execute, "API 선택", 21, INK);
        signature = text(execute, "왼쪽 목록에서 테스트할 API를 선택하세요.", 12, MUTED);
        signature.setTextIsSelectable(true);
        permission = text(execute, "", 13, MINT);
        form = column(); execute.addView(form);
        run = button(execute, "실행", this::executeSelected);
        result = text(execute, "응답이 여기에 표시됩니다.", 13, INK);
        result.setTypeface(Typeface.MONOSPACE); result.setTextIsSelectable(true);
        LinearLayout subPanel = card(root, "03  등록된 구독");
        subscriptions = column(); subPanel.addView(subscriptions);
        button(subPanel, "모든 구독 해제", () -> {
            for (ApiEngine.Subscription subscription : new ArrayList<>(engine.subscriptions.values())) engine.unsubscribe(subscription);
            refresh();
        });
        LinearLayout logPanel = card(root, "04  호출 로그 및 결과");
        LinearLayout tools = new LinearLayout(this); logPanel.addView(tools);
        button(tools, "결과 JSON 복사", () -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Vehicle SDK results", engine.reportJson()));
            Toast.makeText(this, "전체 결과 JSON 복사 완료", Toast.LENGTH_SHORT).show();
        });
        button(tools, "JSON 파일 저장", this::exportReport);
        button(logPanel, "결과 및 로그 지우기", () -> { engine.logs.clear(); engine.results.clear(); engine.droppedResults = 0; refresh(); });
        text(logPanel, "최근 로그 200개 · 결과 5,000개 보관. JSON에 누락된 이전 결과 개수를 표시합니다.", 12, MUTED);
        log = text(logPanel, "", 12, MUTED);
        log.setTypeface(Typeface.MONOSPACE); log.setTextIsSelectable(true);
        text(root, "SDK의 모든 API를 노출합니다. 차량·에뮬레이터별 기능 지원과 권한은 응답으로 확인하세요.\n앱이 백그라운드로 가면 일괄 검사를 중지하고 구독과 Vehicle 리소스를 해제합니다.", 13, MUTED);
        filter();
        select(catalog.all.get(0));
        refresh();
    }

    private void filter() {
        if (apiAdapter == null) return;
        String query = search.getText().toString().toLowerCase(Locale.ROOT).trim();
        String domain = String.valueOf(domainFilter.getSelectedItem());
        int kind = kindFilter.getSelectedItemPosition();
        visible.clear();
        for (ApiCatalog.Api api : catalog.all) {
            if (domainFilter.getSelectedItemPosition() > 0 && !domain.equals(api.domain)) continue;
            if (kind > 0 && api.kind.ordinal() != kind - 1) continue;
            if (!(api.title() + " " + api.permission).toLowerCase(Locale.ROOT).contains(query)) continue;
            visible.add(api);
        }
        count.setText(visible.size() + " / " + catalog.all.size() + "개 API");
        apiAdapter.notifyDataSetChanged();
    }

    void select(ApiCatalog.Api api) {
        selected = api;
        title.setText(api.title());
        signature.setText(api.signature() + (api.deprecated ? "\n이전 버전 API · 호환성 테스트용" : ""));
        form.removeAllViews(); editors.clear(); unregisterPicker = null;
        if (api.kind == ApiCatalog.Kind.UNREGISTER) {
            text(form, "동일 API로 등록한 구독을 선택합니다. 원래 영역과 리스너를 그대로 해제합니다.", 13, MUTED);
            unregisterPicker = spinner(form, Arrays.asList("등록된 구독 없음"));
            updateUnregisterChoices();
        } else {
            ApiCatalog.Api related = relatedCapability(api);
            if (related != null) button(form, "관련 지원 기능 조회 · " + related.method.getName(), () -> {
                engine.invoke(related, new Object[related.method.getParameterCount()], null);
            });
            Type[] generics = api.method.getGenericParameterTypes();
            for (int i = 0; i < generics.length; i++) {
                Class<?> type = api.method.getParameterTypes()[i];
                if (ApiCatalog.isFunction(type) || ApiCatalog.isListener(type)) continue;
                text(form, api.name(i) + " · " + ApiCatalog.shortType(generics[i]), 14, MUTED);
                try { editors.put(i, createEditor(form, type, generics[i])); }
                catch (Exception error) {
                    text(form, ApiEngine.describe(error), 13, Color.rgb(255, 180, 160));
                    editors.put(i, () -> { throw error; });
                }
            }
            if (editors.isEmpty()) text(form, "입력 인자 없음 · 콜백은 앱이 처리합니다.", 13, MUTED);
            if (api.kind == ApiCatalog.Kind.CONTROL) text(form, "입력한 값으로 차량 설정을 변경합니다. 해당 기능의 Capability 응답에서 범위와 접근 수준을 먼저 확인하세요.", 13, MINT);
        }
        run.setText(api.kind == ApiCatalog.Kind.REGISTER ? "구독 등록" : api.kind == ApiCatalog.Kind.UNREGISTER ? "선택한 구독 해제"
                : api.kind == ApiCatalog.Kind.CONTROL ? "제어 호출" : "API 실행");
        apiAdapter.notifyDataSetChanged();
        refresh();
    }

    private ValueReader createEditor(LinearLayout parent, Class<?> type, Type generic) throws Exception {
        if (!ApiCatalog.supports(generic)) throw new IllegalArgumentException("지원하지 않는 인자: " + generic);
        if (type == boolean.class) {
            Spinner choice = spinner(parent, Arrays.asList("false", "true"));
            return () -> choice.getSelectedItemPosition() == 1;
        }
        if (type.isEnum()) {
            Object[] values = type.getEnumConstants();
            Spinner choice = spinner(parent, enumNames(values));
            for (int i = 0; i < values.length; i++) if (((Enum<?>) values[i]).name().equals("STOP")) choice.setSelection(i);
            return () -> values[choice.getSelectedItemPosition()];
        }
        if (ApiCatalog.isArea(type)) {
            Constructor<?> constructor = type.getConstructors()[0];
            Class<?> element = constructor.getParameterTypes()[0].getComponentType();
            Object[] constants = element.getEnumConstants();
            boolean[] selectedValues = new boolean[constants.length];
            selectedValues[0] = true;
            Button choose = button(parent, "영역: " + constants[0], () -> {
                boolean[] draft = selectedValues.clone();
                new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(type.getSimpleName())
                        .setMultiChoiceItems(enumNames(constants).toArray(new String[0]), draft,
                                (dialog, which, checked) -> draft[which] = checked)
                        .setNegativeButton("취소", null)
                        .setPositiveButton("적용", (dialog, which) -> {
                            if (!any(draft)) { Toast.makeText(this, "영역을 하나 이상 선택하세요", Toast.LENGTH_SHORT).show(); return; }
                            System.arraycopy(draft, 0, selectedValues, 0, draft.length);
                            List<String> names = new ArrayList<>();
                            for (int i = 0; i < constants.length; i++) if (draft[i]) names.add(constants[i].toString());
                            // The editor's button is the most recently created area button in this parent.
                            for (int i = parent.getChildCount() - 1; i >= 0; i--) {
                                View child = parent.getChildAt(i);
                                if (child.getTag() == selectedValues) { ((Button) child).setText("영역: " + String.join(", ", names)); break; }
                            }
                        }).show();
            });
            choose.setTag(selectedValues);
            button(parent, "Capability 응답의 지원 영역에서 선택", () -> {
                ApiCatalog.Api related = relatedCapability(selected);
                Object[] payload = related == null ? null : engine.capabilities.get(related.domain + "." + related.method.getName());
                List<Object> supported = new ArrayList<>();
                if (payload != null) for (Object value : payload) {
                    Collection<?> candidates = value instanceof Map ? ((Map<?, ?>) value).keySet()
                            : value instanceof Collection ? (Collection<?>) value : Collections.emptyList();
                    for (Object item : candidates) if (type.isInstance(item)) supported.add(item);
                }
                if (supported.isEmpty()) {
                    Toast.makeText(this, "관련 지원 기능을 먼저 조회하세요. 지원 영역이 없으면 영역 상수를 직접 선택할 수 있습니다.", Toast.LENGTH_LONG).show();
                    return;
                }
                String[] names = new String[supported.size()];
                for (int i = 0; i < names.length; i++) names[i] = ApiEngine.format(supported.get(i));
                new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("SDK가 반환한 지원 영역")
                        .setItems(names, (dialog, index) -> {
                            try {
                                Object values = type.getMethod("getValues").invoke(supported.get(index));
                                Arrays.fill(selectedValues, false);
                                for (int i = 0; i < Array.getLength(values); i++)
                                    for (int j = 0; j < constants.length; j++) if (constants[j].equals(Array.get(values, i))) selectedValues[j] = true;
                                choose.setText("영역: " + names[index]);
                            } catch (Exception error) { engine.event(ApiEngine.describe(error)); }
                        }).show();
            });
            return () -> {
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < constants.length; i++) if (selectedValues[i]) values.add(constants[i]);
                Object array = Array.newInstance(element, values.size());
                for (int i = 0; i < values.size(); i++) Array.set(array, i, values.get(i));
                return constructor.newInstance(array);
            };
        }
        EditText input = new EditText(this);
        input.setTextColor(INK); input.setHintTextColor(MUTED); input.setSingleLine(true);
        parent.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
        if (type == List.class) {
            Class<?> element = ApiCatalog.listElement(generic);
            text(parent, "허용 값: " + String.join(", ", enumNames(element.getEnumConstants())) + " · 쉼표로 구분, 영역의 zone 순서대로 입력", 12, MUTED);
            input.setHint("예: ON, OFF, SKIP");
            return () -> {
                if (input.getText().toString().trim().isEmpty()) throw new IllegalArgumentException("목록 값을 입력하세요.");
                List<Object> values = new ArrayList<>();
                for (String item : input.getText().toString().split(",", -1)) {
                    Object match = null;
                    for (Object constant : element.getEnumConstants()) if (((Enum<?>) constant).name().equals(item.trim().toUpperCase(Locale.ROOT))) match = constant;
                    if (match == null) throw new IllegalArgumentException("허용되지 않은 값: " + item);
                    values.add(match);
                }
                return values;
            };
        }
        input.setHint(type == int.class ? "정수 입력" : "실수 입력");
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                | (type == float.class ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        return () -> {
            String raw = input.getText().toString().trim();
            if (type == int.class) return Integer.parseInt(raw);
            float value = Float.parseFloat(raw);
            if (Float.isInfinite(value) || Float.isNaN(value)) throw new IllegalArgumentException("유한한 숫자를 입력하세요.");
            return value;
        };
    }

    private void executeSelected() {
        if (selected == null || !engine.initialized()) return;
        try {
            if (selected.kind == ApiCatalog.Kind.UNREGISTER) {
                int index = unregisterPicker.getSelectedItemPosition();
                if (index < 0 || index >= unregisterChoices.size()) throw new IllegalArgumentException("해제할 구독이 없습니다.");
                engine.unsubscribe(unregisterChoices.get(index)); refresh(); return;
            }
            Object[] args = new Object[selected.method.getParameterCount()];
            for (Map.Entry<Integer, ValueReader> editor : editors.entrySet()) args[editor.getKey()] = editor.getValue().read();
            engine.invoke(selected, args, null);
        } catch (Exception error) { engine.event("입력 오류: " + ApiEngine.describe(error)); result.setText(ApiEngine.describe(error)); }
    }

    private void startBatch() {
        if (!engine.initialized() || batchRunning) return;
        batch.clear();
        for (ApiCatalog.Api api : catalog.all) if (api.kind == ApiCatalog.Kind.CAPABILITY) batch.add(api);
        batchTotal = batch.size(); batchPassed = batchFailed = 0;
        batchRunning = true; ++batchGeneration;
        nextBatch(batchGeneration);
    }
    private void nextBatch(int token) {
        if (!batchRunning || token != batchGeneration) return;
        if (batch.isEmpty()) { batchRunning = false; refresh(); return; }
        ApiCatalog.Api api = batch.removeFirst();
        engine.invoke(api, new Object[api.method.getParameterCount()], success -> {
            if (!batchRunning || token != batchGeneration) return;
            if (success) ++batchPassed; else ++batchFailed;
            refresh();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> nextBatch(token));
        });
        refresh();
    }
    private void stopBatch() {
        ++batchGeneration; batchRunning = false; batch.clear(); refresh();
    }
    private void refresh() {
        if (status == null || run == null || log == null) return;
        status.setText(engine.connection);
        connect.setEnabled(!engine.initialized()); disconnect.setEnabled(engine.initialized());
        run.setEnabled(engine.initialized() && selected != null);
        batchStart.setEnabled(engine.initialized() && !batchRunning);
        batchStop.setEnabled(batchRunning);
        if (batchTotal > 0) batchStatus.setText((batchRunning ? "검사 중" : "검사 종료/중지") + " · " + (batchPassed + batchFailed) + "/" + batchTotal
                + " · 응답 성공 " + batchPassed + " · 오류/시간 초과 " + batchFailed + " (성공 응답의 NONE은 미지원)");
        result.setText(engine.lastResult);
        // Keep the visible log short; JSON retains up to 5,000 result records.
        List<String> preview = new ArrayList<>();
        int length = 0;
        for (String item : engine.logs) { if (length > 18000) break; preview.add(item); length += item.length(); }
        log.setText(String.join("\n\n", preview));
        if (selected != null) {
            boolean granted = selected.permission.isEmpty() || androidx.core.content.ContextCompat.checkSelfPermission(this, selected.permission)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            permission.setText(selected.permission + "\n권한: " + (granted ? "허용됨" : "미허용 (호출 시 SDK 오류 확인)"));
        }
        subscriptions.removeAllViews();
        if (engine.subscriptions.isEmpty()) text(subscriptions, "등록된 구독 없음", 13, MUTED);
        for (ApiEngine.Subscription subscription : new ArrayList<>(engine.subscriptions.values()))
            button(subscriptions, subscription.toString() + " · 해제", () -> { engine.unsubscribe(subscription); refresh(); });
        updateUnregisterChoices();
    }
    private void updateUnregisterChoices() {
        if (unregisterPicker == null || selected == null) return;
        long previous = unregisterChoices.isEmpty() || unregisterPicker.getSelectedItemPosition() < 0 ? -1
                : unregisterChoices.get(Math.min(unregisterPicker.getSelectedItemPosition(), unregisterChoices.size() - 1)).id;
        unregisterChoices.clear();
        List<String> labels = new ArrayList<>();
        for (ApiEngine.Subscription subscription : engine.subscriptions.values()) {
            if (subscription.api.domain.equals(selected.domain) && ("un" + subscription.api.method.getName()).equals(selected.method.getName())) {
                unregisterChoices.add(subscription); labels.add(subscription.toString());
            }
        }
        if (labels.isEmpty()) labels.add("등록된 구독 없음");
        setSpinner(unregisterPicker, labels);
        for (int i = 0; i < unregisterChoices.size(); i++) if (unregisterChoices.get(i).id == previous) unregisterPicker.setSelection(i);
    }
    private void exportReport() {
        exportSnapshot = engine.reportJson();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/json"); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "vehicle-sdk-results.json");
        try { startActivityForResult(intent, EXPORT); }
        catch (android.content.ActivityNotFoundException error) { engine.event("파일 선택기가 없습니다. JSON 복사를 사용하세요."); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT && resultCode == RESULT_OK && data != null && data.getData() != null && exportSnapshot != null) {
            try (java.io.OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                if (output == null) throw new java.io.IOException("파일을 열 수 없습니다");
                output.write(exportSnapshot.getBytes(StandardCharsets.UTF_8));
                engine.event("JSON 결과 저장 완료");
            } catch (Exception error) { engine.event("저장 실패: " + ApiEngine.describe(error)); }
        }
    }
    @Override protected void onStop() {
        if (engine != null) { stopBatch(); engine.release(); refresh(); }
        uiHandler.removeCallbacksAndMessages(null);
        refreshScheduled = false;
        super.onStop();
    }
    private String argumentSummary(ApiCatalog.Api api) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < api.method.getParameterCount(); i++)
            names.add(api.name(i) + ": " + api.method.getParameterTypes()[i].getSimpleName());
        return "(" + String.join(", ", names) + ")";
    }
    private ApiCatalog.Api relatedCapability(ApiCatalog.Api api) {
        if (api == null || api.kind == ApiCatalog.Kind.CAPABILITY) return null;
        String property = property(api.method.getName());
        ApiCatalog.Api match = null;
        for (ApiCatalog.Api candidate : catalog.all) {
            if (candidate.domain.equals(api.domain) && candidate.kind == ApiCatalog.Kind.CAPABILITY
                    && property(candidate.method.getName()).equals(property)
                    && (match == null || (match.deprecated && !candidate.deprecated))) match = candidate;
        }
        return match;
    }
    private String property(String method) {
        return method.replaceFirst("^(unregister|register|getCurrent|check|get|set|move|select|has|is)", "")
                .replace("Capabilities", "").replace("State", "").replace("Position", "").replace("Been", "");
    }
    private static boolean any(boolean[] values) { for (boolean v : values) if (v) return true; return false; }
    private List<String> enumNames(Object[] values) {
        List<String> names = new ArrayList<>();
        for (Object value : values) names.add(((Enum<?>) value).name());
        return names;
    }
    private List<String> prepend(String first, List<String> rest) { List<String> values = new ArrayList<>(); values.add(first); values.addAll(rest); return values; }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout view = column(); view.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable background = new GradientDrawable(); background.setColor(PANEL); background.setCornerRadius(dp(16)); view.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(16); parent.addView(view, params);
        text(view, heading, 14, MINT).setTypeface(null, Typeface.BOLD); return view;
    }
    private TextView text(LinearLayout parent, String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setPadding(0, 0, 0, dp(10));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private Button button(LinearLayout parent, String value, Runnable action) {
        Button view = new com.google.android.material.button.MaterialButton(this); view.setText(value); view.setAllCaps(false); view.setMinHeight(dp(48));
        int[][] states = {new int[]{-android.R.attr.state_enabled}, new int[]{}};
        view.setTextColor(new ColorStateList(states, new int[]{MUTED, BG}));
        ViewCompat.setBackgroundTintList(view, new ColorStateList(states, new int[]{Color.rgb(42, 58, 71), MINT}));
        view.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = parent.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) params.setMarginEnd(dp(8));
        parent.addView(view, params); return view;
    }
    private Spinner spinner(LinearLayout parent, List<String> labels) {
        Spinner view = new Spinner(this); view.setBackgroundColor(Color.rgb(224, 236, 241));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48)); params.bottomMargin = dp(8);
        parent.addView(view, params); setSpinner(view, labels); return view;
    }
    private void setSpinner(Spinner spinner, List<String> labels) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
