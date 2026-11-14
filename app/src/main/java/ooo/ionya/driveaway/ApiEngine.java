package ooo.ionya.driveaway;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import ai.pleos.playground.vehicle.Vehicle;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import kotlin.Unit;

/** Executes all SDK interfaces and keeps exact listener identities for unregister. */
public final class ApiEngine {
    public interface Observer { void changed(String message); }
    public interface Completion { void complete(boolean success); }
    public static final class Subscription {
        public final long id;
        public final ApiCatalog.Api api;
        final Object domain;
        final Object[] arguments;
        final Method unregister;
        public String state = "등록 요청됨";
        Subscription(long id, ApiCatalog.Api api, Object domain, Object[] args, Method unregister) {
            this.id = id; this.api = api; this.domain = domain; this.arguments = args; this.unregister = unregister;
        }
        @Override public String toString() { return "#" + id + " " + api.title() + " · " + format(arguments) + " · " + state; }
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Observer observer;
    private final Map<Long, Completion> pending = new LinkedHashMap<>();
    private final Map<Long, String> requestInputs = new LinkedHashMap<>();
    public final Map<Long, Subscription> subscriptions = new LinkedHashMap<>();
    public final Deque<JSONObject> results = new ArrayDeque<>();
    public long droppedResults;
    public final Map<String, Object[]> capabilities = new LinkedHashMap<>();
    public Object[] lastValues = new Object[0];
    public final Deque<String> logs = new ArrayDeque<>();
    private Vehicle vehicle;
    private int generation;
    private long nextId;
    public String connection = "연결 대기";
    public String lastResult = "API를 선택하고 실행하세요.";
    public ApiEngine(Context context, Observer observer) { this.context = context; this.observer = observer; }
    public boolean initialized() { return vehicle != null; }
    public void connect(ApiCatalog catalog) {
        release();
        try {
            vehicle = new Vehicle(context);
            vehicle.initialize();
            connection = "초기화 요청됨 · 서비스 응답 대기";
            event("Vehicle.initialize()");
            for (ApiCatalog.Api api : catalog.all) {
                if (api.domain.equals("Display") && api.method.getName().equals("checkTemperatureDisplayUnitCapabilities")) {
                    invoke(api, new Object[api.method.getParameterCount()], null);
                    break;
                }
            }
        } catch (Exception error) { vehicle = null; connection = "초기화 실패"; event(describe(error)); }
    }
    public void invoke(ApiCatalog.Api api, Object[] arguments, Completion completion) {
        if (vehicle == null) { event("먼저 차량 서비스를 연결하세요."); if (completion != null) completion.complete(false); return; }
        long id = ++nextId;
        int token = generation;
        Object[] args = arguments.clone();
        requestInputs.put(id, formatInput(api, args));
        Class<?>[] types = api.method.getParameterTypes();
        try {
            Object domain = api.getter.invoke(vehicle);
            if (api.kind == ApiCatalog.Kind.UNREGISTER) throw new IllegalArgumentException("등록된 구독을 선택해 해제하세요.");
            Method unregister = api.kind == ApiCatalog.Kind.REGISTER
                    ? api.getter.getReturnType().getMethod("un" + api.method.getName(), types) : null;
            for (int i = 0; i < types.length; i++) {
                final int index = i;
                if (ApiCatalog.isFunction(types[i]) || ApiCatalog.isListener(types[i])) {
                    args[i] = Proxy.newProxyInstance(types[i].getClassLoader(), new Class<?>[]{types[i]}, (proxy, method, values) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            switch (method.getName()) {
                                case "equals": return proxy == values[0];
                                case "hashCode": return System.identityHashCode(proxy);
                                default: return "SDK callback #" + id;
                            }
                        }
                        Object[] payload = values == null ? new Object[0] : values.clone();
                        boolean failure = api.isFailure(index) || method.getName().equals("onFailed");
                        main.post(() -> {
                            if (token != generation) return;
                            if (api.kind == ApiCatalog.Kind.REGISTER) {
                                Subscription subscription = subscriptions.get(id);
                                if (subscription == null) return;
                                subscription.state = failure ? "수신 오류" : "수신 중";
                                report(id, api, failure ? "LISTENER_ERROR" : "EVENT", payload);
                            } else {
                                if (!pending.containsKey(id)) return; // Late/duplicate callback after timeout.
                                Completion done = pending.remove(id);
                                report(id, api, failure ? "ERROR" : "SUCCESS", payload);
                                requestInputs.remove(id);
                                if (done != null) done.complete(!failure);
                            }
                        });
                        return method.getReturnType() == void.class ? null : Unit.INSTANCE;
                    });
                }
            }
            if (api.kind == ApiCatalog.Kind.REGISTER) {
                subscriptions.put(id, new Subscription(id, api, domain, args, unregister));
            } else pending.put(id, completion);
            event("#" + id + " CALL " + api.title() + " " + formatInput(api, args));
            api.method.invoke(domain, args);
            if (api.kind == ApiCatalog.Kind.REGISTER) {
                lastResult = "#" + id + " 구독 등록 요청됨. 첫 이벤트를 기다립니다.";
                observer.changed("");
            } else {
                main.postDelayed(() -> {
                    if (token != generation || !pending.containsKey(id)) return;
                    Completion done = pending.remove(id);
                    boolean noSuccess = !api.hasSuccessCallback();
                    report(id, api, noSuccess ? "SENT_NO_SUCCESS_CALLBACK" : "TIMEOUT", new Object[]{
                            noSuccess ? "실패 콜백 없이 10초 경과. SDK에 성공 콜백이 없어 상태 조회로 결과를 확인하세요."
                                      : "10초 동안 응답 없음. 이후 콜백은 무시합니다."});
                    requestInputs.remove(id);
                    if (done != null) done.complete(false);
                }, 10000);
            }
        } catch (Exception error) {
            pending.remove(id);
            subscriptions.remove(id);
            report(id, api, "ERROR", new Object[]{unwrap(error)});
            requestInputs.remove(id);
            if (completion != null) completion.complete(false);
        }
    }
    public boolean unsubscribe(Subscription subscription) {
        try {
            subscription.unregister.invoke(subscription.domain, subscription.arguments);
            subscriptions.remove(subscription.id);
            report(subscription.id, subscription.api, "UNREGISTER_REQUESTED", new Object[]{"동일 영역 및 리스너로 해제 요청 전달"});
            requestInputs.remove(subscription.id);
            return true;
        } catch (Exception error) {
            event("구독 해제 실패 #" + subscription.id + " " + describe(error));
            return false;
        }
    }
    public void release() {
        ++generation;
        main.removeCallbacksAndMessages(null);
        pending.clear();
        for (Subscription s : new ArrayList<>(subscriptions.values())) unsubscribe(s);
        subscriptions.clear();
        requestInputs.clear();
        capabilities.clear();
        if (vehicle != null) {
            try { vehicle.release(); event("Vehicle.release()"); }
            catch (Exception error) { event("release: " + describe(error)); }
        }
        vehicle = null;
        connection = "연결 대기";
        observer.changed("");
    }
    private void report(long id, ApiCatalog.Api api, String state, Object[] values) {
        lastValues = values.clone();
        if (api.kind == ApiCatalog.Kind.CAPABILITY && state.equals("SUCCESS"))
            capabilities.put(api.domain + "." + api.method.getName(), values.clone());
        if (state.equals("SUCCESS") || state.equals("EVENT")) connection = "차량 서비스 응답 확인됨";
        else if ((state.equals("ERROR") || state.equals("TIMEOUT")) && !connection.equals("차량 서비스 응답 확인됨"))
            connection = "서비스 연결 확인 실패 · API 오류 확인";
        String value = format(values);
        lastResult = "#" + id + " " + api.title() + "\n" + state + "\n\n" + value;
        try {
            JSONObject row = new JSONObject();
            row.put("id", id); row.put("time", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()));
            row.put("api", api.key); row.put("permission", api.permission);
            row.put("inputs", requestInputs.get(id));
            row.put("state", state); row.put("result", value);
            results.add(row);
            while (results.size() > 5000) { results.removeFirst(); ++droppedResults; }
        } catch (JSONException error) { throw new IllegalStateException(error); }
        event("#" + id + " " + state + " " + api.title() + "\n" + value);
    }
    public void event(String message) {
        if (!message.isEmpty()) {
            logs.addFirst(new java.text.SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date()) + " " + message);
            while (logs.size() > 200) logs.removeLast();
        }
        observer.changed(message);
    }
    public String reportJson() {
        try {
            JSONObject report = new JSONObject();
            report.put("sdk", "ai.pleos.playground:Vehicle:2.3.2");
            report.put("connection", connection);
            report.put("results", new JSONArray(results));
            report.put("retainedResultLimit", 5000);
            report.put("droppedResults", droppedResults);
            report.put("log", new JSONArray(logs));
            return report.toString(2);
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }
    private static Throwable unwrap(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error;
    }
    public static String describe(Throwable error) {
        Throwable cause = unwrap(error);
        return cause.getClass().getName() + ": " + cause.getMessage();
    }
    public static String formatInput(ApiCatalog.Api api, Object[] args) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            Class<?> type = api.method.getParameterTypes()[i];
            if (!ApiCatalog.isFunction(type) && !ApiCatalog.isListener(type)) values.add(api.name(i) + "=" + format(args[i]));
        }
        return String.join(", ", values);
    }
    public static String format(Object value) { return format(value, 0); }
    private static String format(Object value, int depth) {
        if (value == null) return "null";
        if (depth > 6) return String.valueOf(value);
        if (value instanceof Throwable) return describe((Throwable) value);
        if (value.getClass().isArray()) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) items.add(format(Array.get(value, i), depth + 1));
            return "[" + String.join(", ", items) + "]";
        }
        if (value instanceof Map) {
            List<String> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
                entries.add(format(entry.getKey(), depth + 1) + " = " + format(entry.getValue(), depth + 1));
            return "{\n" + String.join("\n", entries) + "\n}";
        }
        if (value instanceof Iterable) {
            List<String> entries = new ArrayList<>();
            for (Object item : (Iterable<?>) value) entries.add(format(item, depth + 1));
            return "[" + String.join(", ", entries) + "]";
        }
        if (ApiCatalog.isArea(value.getClass())) {
            try { return value.getClass().getSimpleName() + format(value.getClass().getMethod("getValues").invoke(value), depth + 1); }
            catch (ReflectiveOperationException ignored) { return value.getClass().getSimpleName(); }
        }
        return String.valueOf(value);
    }
}
