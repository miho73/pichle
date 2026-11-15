package ooo.ionya.driveaway;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.model.area.WheelArea;
import ai.pleos.playground.vehicle.constant.area.WheelValue;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** User-triggered, read-only Vehicle SDK diagnostics. Construction does not access the car. */
public final class VehicleStateProbe implements AutoCloseable {
    public interface Listener { void onResult(String id, String status, String result); }
    private interface Result { void accept(Object value); }
    private interface Failure { void accept(Exception error); }
    private interface ReadCall { void run(Vehicle vehicle, Result result, Failure failure); }

    public static final class Query {
        public final String id, title, domain, sdkMethod, permission, unit;
        private final ReadCall call;
        private Query(String id, String title, String domain, String sdkMethod,
                      String permission, String unit, ReadCall call) {
            this.id = id; this.title = title; this.domain = domain; this.sdkMethod = sdkMethod;
            this.permission = permission; this.unit = unit; this.call = call;
        }
    }

    private static final String DRIVE = "pleos.car.permission.CAR_DRIVE";
    private static final String ENERGY = "pleos.car.permission.CAR_ENERGY";
    private static final String SAFETY = "pleos.car.permission.CAR_SAFETY";
    private static final String LIGHTS = "pleos.car.permission.CAR_LIGHTS";
    private static final long TIMEOUT_MS = 8_000;
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Query> queries = new ArrayList<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final ArrayDeque<Query> batch = new ArrayDeque<>();
    private Vehicle vehicle;
    private boolean closed, batchRunning;
    private String batchCurrent;

    public VehicleStateProbe(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        addQueries();
    }

    public List<Query> getQueries() { return Collections.unmodifiableList(queries); }

    public void read(String id) {
        onMain(() -> {
            if (closed) return;
            Query query = find(id);
            if (query == null) { emit(id, "ERROR", "등록되지 않은 조회 항목입니다."); return; }
            start(query);
        });
    }

    /** Reads sequentially. No setters or automatic listener subscriptions are used. */
    public void readAll() {
        onMain(() -> {
            if (closed || batchRunning) return;
            batchRunning = true; batch.clear(); batch.addAll(queries);
            next();
        });
    }

    public void stopAll() {
        onMain(() -> cancel(false));
    }

    private void next() {
        if (closed || !batchRunning) return;
        if (batch.isEmpty()) { batchRunning = false; batchCurrent = null; return; }
        Query query = batch.removeFirst();
        batchCurrent = query.id;
        Pending existing = pending.get(query.id);
        if (existing == null) start(query);
        // An individual read already in flight becomes the batch's current read.
    }

    private void start(Query query) {
        if (closed || pending.containsKey(query.id)) return;
        Pending request = new Pending(query);
        pending.put(query.id, request);
        emit(query.id, "RUNNING", query.domain + "." + query.sdkMethod + "\n응답 대기 중");
        if (context.checkSelfPermission(query.permission) != PackageManager.PERMISSION_GRANTED) {
            finish(request, "PERMISSION_DENIED", query.permission + " 권한이 허용되지 않았습니다.");
            return;
        }
        request.timeout = () -> finish(request, "TIMEOUT", "8초 동안 SDK 응답이 도착하지 않았습니다.");
        main.postDelayed(request.timeout, TIMEOUT_MS);
        try {
            if (vehicle == null) {
                Vehicle candidate = new Vehicle(context);
                try { candidate.initialize(); vehicle = candidate; }
                catch (RuntimeException error) { try { candidate.release(); } catch (RuntimeException ignored) { } throw error; }
            }
            query.call.run(vehicle,
                    value -> main.post(() -> finish(request, "OK", formatResult(value))),
                    error -> main.post(() -> finish(request, errorStatus(error), describe(error))));
        } catch (RuntimeException | LinkageError error) {
            finish(request, error instanceof SecurityException ? "PERMISSION_DENIED" : "ERROR", describe(error));
        }
    }

    private void finish(Pending request, String status, String result) {
        if (closed || pending.get(request.query.id) != request) return;
        pending.remove(request.query.id);
        if (request.timeout != null) main.removeCallbacks(request.timeout);
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.KOREA).format(new Date());
        String output = time + " · " + (SystemClock.elapsedRealtime() - request.started) + "ms\n"
                + request.query.domain + "." + request.query.sdkMethod + "\n" + result;
        emit(request.query.id, status, output);
        if (request.query.id.equals(batchCurrent)) { batchCurrent = null; main.post(this::next); }
    }

    private Query find(String id) { for (Query query : queries) if (query.id.equals(id)) return query; return null; }
    private void emit(String id, String status, String detail) { if (!closed) listener.onResult(id, status, detail); }
    private void onMain(Runnable action) { if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action); }

    private void cancel(boolean closing) {
        batchRunning = false; batchCurrent = null; batch.clear();
        List<Pending> requests = new ArrayList<>(pending.values()); pending.clear();
        main.removeCallbacksAndMessages(null);
        for (Pending request : requests) if (!closing) emit(request.query.id, "CANCELLED", "조회가 중지됐습니다.");
        if (vehicle != null) { try { vehicle.release(); } catch (RuntimeException ignored) { } vehicle = null; }
    }

    @Override public void close() {
        onMain(() -> { if (closed) return; closed = true; cancel(true); });
    }

    private void add(String id, String title, String domain, String method, String permission, String unit, ReadCall call) {
        queries.add(new Query(id, title, domain, method, permission, unit, call));
    }

    private static <T> Function1<T, Unit> success(Result result) {
        return value -> { result.accept(value); return Unit.INSTANCE; };
    }
    private static Function1<Exception, Unit> failure(Failure result) {
        return error -> { result.accept(error); return Unit.INSTANCE; };
    }

    private void addQueries() {
        add("brake", "브레이크 페달 입력", "Brake", "getCurrentBrakePedalStatus", DRIVE, "Boolean · true=페달 입력",
                (v, ok, no) -> v.getBrake().getCurrentBrakePedalStatus(success(ok), failure(no)));
        add("gear", "현재 기어", "Gear", "getCurrentGearSelectionState", DRIVE, "VehicleGear",
                (v, ok, no) -> v.getGear().getCurrentGearSelectionState(success(ok), failure(no)));
        add("odometer", "차량 누적 주행거리", "Odometer", "getCurrentDistanceDriven", DRIVE, "km",
                (v, ok, no) -> v.getOdometer().getCurrentDistanceDriven(success(ok), failure(no)));
        add("trip_time", "현재 주행 시간", "Odometer", "getTripCurrentDrivingTime", DRIVE, "분",
                (v, ok, no) -> v.getOdometer().getTripCurrentDrivingTime(success(ok), failure(no)));
        add("trip_time_total", "누적 주행 시간", "Odometer", "getTripCumulativeDrivingTime", DRIVE, "분",
                (v, ok, no) -> v.getOdometer().getTripCumulativeDrivingTime(success(ok), failure(no)));
        add("trip_time_refill", "주유 이후 주행 시간", "Odometer", "getTripSinceRefillDrivingTime", DRIVE, "분",
                (v, ok, no) -> v.getOdometer().getTripSinceRefillDrivingTime(success(ok), failure(no)));
        add("trip_distance", "현재 주행 거리", "Odometer", "getTripCurrentDrivingDistance", DRIVE, "m",
                (v, ok, no) -> v.getOdometer().getTripCurrentDrivingDistance(success(ok), failure(no)));
        add("trip_distance_total", "누적 트립 거리", "Odometer", "getTripCumulativeDrivingDistance", DRIVE, "m",
                (v, ok, no) -> v.getOdometer().getTripCumulativeDrivingDistance(success(ok), failure(no)));
        add("trip_distance_refill", "주유 이후 주행 거리", "Odometer", "getTripSinceRefillDrivingDistance", DRIVE, "m",
                (v, ok, no) -> v.getOdometer().getTripSinceRefillDrivingDistance(success(ok), failure(no)));
        add("range", "주행 가능 거리", "Odometer", "getRangeRemainingDistance", DRIVE, "m",
                (v, ok, no) -> v.getOdometer().getRangeRemainingDistance(success(ok), failure(no)));
        add("cruise_speed", "크루즈 설정 속도", "DrivingMode", "getCruiseControlTargetSpeed", DRIVE, "m/s · 실제 현재 속도 아님",
                (v, ok, no) -> v.getDrivingMode().getCruiseControlTargetSpeed(success(ok), failure(no)));
        add("cruise_state", "크루즈 제어 상태", "DrivingMode", "getCruiseControlState", DRIVE, "CruiseControl",
                (v, ok, no) -> v.getDrivingMode().getCruiseControlState(success(ok), failure(no)));
        add("driving_mode", "주행 모드", "DrivingMode", "getCurrentDrivingModeState", DRIVE, "DrivingModeType",
                (v, ok, no) -> v.getDrivingMode().getCurrentDrivingModeState(success(ok), failure(no)));
        add("steering_angle", "앞바퀴 조향각", "SteeringWheel", "getCurrentSteeringWheelAngle", DRIVE, "도 · 좌측 음수, 핸들 자체 회전각 아님",
                (v, ok, no) -> v.getSteeringWheel().getCurrentSteeringWheelAngle(success(ok), failure(no)));
        add("turn_signal", "방향 지시등", "TurnSignal", "getCurrentTurnSignalState", LIGHTS, "TurnSignalValue",
                (v, ok, no) -> v.getTurnSignal().getCurrentTurnSignalState(success(ok), failure(no)));
        add("ev_soc", "배터리 충전량", "EvBattery", "getEvBatteryStateOfCharge", ENERGY, "%",
                (v, ok, no) -> v.getEvBattery().getEvBatteryStateOfCharge(success(ok), failure(no)));
        add("ev_level", "배터리 잔여 에너지 (구 API)", "EvBattery", "getEvBatteryLevel", ENERGY, "Wh · deprecated",
                (v, ok, no) -> v.getEvBattery().getEvBatteryLevel(success(ok), failure(no)));
        add("ev_instant", "순간 전비", "EvBattery", "getInstantaneousEvEfficiency", ENERGY, "km/kWh",
                (v, ok, no) -> v.getEvBattery().getInstantaneousEvEfficiency(success(ok), failure(no)));
        add("ev_current", "현재 전비", "EvBattery", "getCurrentEvEfficiency", ENERGY, "km/kWh",
                (v, ok, no) -> v.getEvBattery().getCurrentEvEfficiency(success(ok), failure(no)));
        add("ev_charge_efficiency", "충전 이후 전비", "EvBattery", "getSinceChargeEvEfficiency", ENERGY, "km/kWh",
                (v, ok, no) -> v.getEvBattery().getSinceChargeEvEfficiency(success(ok), failure(no)));
        add("ev_cumulative", "누적 전비", "EvBattery", "getCumulativeEvEfficiency", ENERGY, "km/kWh",
                (v, ok, no) -> v.getEvBattery().getCumulativeEvEfficiency(success(ok), failure(no)));
        add("ev_draw_limit", "충전 전류 한도", "EvBattery", "getEvChargeCurrentDrawLimit", ENERGY, "A",
                (v, ok, no) -> v.getEvBattery().getEvChargeCurrentDrawLimit(success(ok), failure(no)));
        add("ev_port", "충전구 열림", "EvBattery", "isEvChargePortOpen", ENERGY, "Boolean",
                (v, ok, no) -> v.getEvBattery().isEvChargePortOpen(success(ok), failure(no)));
        add("ev_charging", "충전 스위치 상태", "EvBattery", "getEvChargeSwitchState", ENERGY, "Boolean",
                (v, ok, no) -> v.getEvBattery().getEvChargeSwitchState(success(ok), failure(no)));
        add("fuel_level", "연료 잔량", "Fuel", "getFuelPercent", ENERGY, "%",
                (v, ok, no) -> v.getFuel().getFuelPercent(success(ok), failure(no)));
        add("fuel_instant", "순간 연료 소비", "Fuel", "getInstantaneousFuelEconomy", ENERGY, "L/100km",
                (v, ok, no) -> v.getFuel().getInstantaneousFuelEconomy(success(ok), failure(no)));
        add("fuel_current", "현재 연료 소비", "Fuel", "getCurrentFuelEconomy", ENERGY, "L/100km",
                (v, ok, no) -> v.getFuel().getCurrentFuelEconomy(success(ok), failure(no)));
        add("fuel_refill", "주유 이후 연료 소비", "Fuel", "getSinceChargeFuelEconomy", ENERGY, "L/100km",
                (v, ok, no) -> v.getFuel().getSinceChargeFuelEconomy(success(ok), failure(no)));
        add("fuel_total", "누적 연료 소비", "Fuel", "getCumulativeFuelEconomy", ENERGY, "L/100km",
                (v, ok, no) -> v.getFuel().getCumulativeFuelEconomy(success(ok), failure(no)));
        add("engine_oil", "엔진오일 상태", "Safety", "getEngineOilLevelState", SAFETY, "OilLevelState",
                (v, ok, no) -> v.getSafety().getEngineOilLevelState(success(ok), failure(no)));
        add("brake_fluid", "브레이크액 상태", "Safety", "getBrakeFluidLevelState", SAFETY, "OilLevelState",
                (v, ok, no) -> v.getSafety().getBrakeFluidLevelState(success(ok), failure(no)));
        add("transmission_oil", "변속기 오일 상태", "Safety", "getTransmissionOilLevelState", SAFETY, "OilLevelState",
                (v, ok, no) -> v.getSafety().getTransmissionOilLevelState(success(ok), failure(no)));
        add("washer_warning", "워셔액 부족 경고", "Safety", "hasLowWasherFluidWarning", SAFETY, "Boolean",
                (v, ok, no) -> v.getSafety().hasLowWasherFluidWarning(success(ok), failure(no)));
        add("power_warning", "출력 저하 경고", "Safety", "hasPowerDownWarningIndicator", SAFETY, "Boolean",
                (v, ok, no) -> v.getSafety().hasPowerDownWarningIndicator(success(ok), failure(no)));
        add("telltales", "차량 경고등 정보", "Safety", "getTelltalesInfo", SAFETY, "Telltales",
                (v, ok, no) -> v.getSafety().getTelltalesInfo(success(ok), failure(no)));
        addTire("tire_left_front", "앞 왼쪽 타이어 저압 임계값", WheelValue.LEFT_FRONT);
        addTire("tire_right_front", "앞 오른쪽 타이어 저압 임계값", WheelValue.RIGHT_FRONT);
        addTire("tire_left_rear", "뒤 왼쪽 타이어 저압 임계값", WheelValue.LEFT_REAR);
        addTire("tire_right_rear", "뒤 오른쪽 타이어 저압 임계값", WheelValue.RIGHT_REAR);
    }

    private void addTire(String id, String title, WheelValue wheel) {
        add(id, title, "Tire", "getLowTirePressure(" + wheel.name() + ")", SAFETY, "kPa · 현재 압력 아닌 저압 임계값",
                (v, ok, no) -> v.getTire().getLowTirePressure(new WheelArea(wheel), success(ok), failure(no)));
    }

    private static String errorStatus(Exception error) {
        if (error instanceof SecurityException) return "PERMISSION_DENIED";
        if (error instanceof UnsupportedOperationException) return "UNSUPPORTED";
        return "ERROR";
    }
    private static String describe(Throwable error) {
        if (error == null) return "SDK 오류 원인이 제공되지 않았습니다.";
        StringBuilder out = new StringBuilder();
        for (int depth = 0; error != null && depth < 3; depth++, error = error.getCause()) {
            if (depth > 0) out.append("\n원인: ");
            out.append(error.getClass().getName()).append(": ").append(String.valueOf(error.getMessage()));
        }
        return out.toString();
    }
    private static String formatResult(Object value) {
        if (value == null) return "type=null\nvalue=null\nSDK가 성공 콜백에서 null을 반환했습니다. 0이나 false로 대체하지 않습니다.";
        String result = "type=" + value.getClass().getName() + "\nvalue=" + format(value, 0, new IdentityHashMap<>());
        return result.length() > 6000 ? result.substring(0, 6000) + "\n… 결과 표시 한도 초과" : result;
    }
    private static String format(Object value, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean || value instanceof CharSequence || value instanceof Enum<?>) return String.valueOf(value);
        if (depth >= 3) return String.valueOf(value);
        if (seen.put(value, true) != null) return "<cycle>";
        if (value.getClass().isArray()) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < Math.min(Array.getLength(value), 32); i++) parts.add(format(Array.get(value, i), depth + 1, seen));
            return parts.toString();
        }
        if (value instanceof Iterable<?>) {
            List<String> parts = new ArrayList<>();
            for (Object item : (Iterable<?>) value) { if (parts.size() == 32) break; parts.add(format(item, depth + 1, seen)); }
            return parts.toString();
        }
        // Only introspect the SDK's returned data models; this never discovers/invokes car APIs.
        if (value.getClass().getName().startsWith("ai.pleos.")) {
            List<String> parts = new ArrayList<>();
            Method[] methods = value.getClass().getMethods();
            Arrays.sort(methods, (a, b) -> a.getName().compareTo(b.getName()));
            for (Method method : methods) {
                if (parts.size() == 32) break;
                String name = method.getName();
                if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 0 || name.equals("getClass")
                        || (!name.startsWith("get") && !name.startsWith("is"))) continue;
                try { parts.add(name + "=" + format(method.invoke(value), depth + 1, seen)); }
                catch (ReflectiveOperationException | RuntimeException error) { parts.add(name + "=<" + error.getClass().getSimpleName() + ">"); }
            }
            if (!parts.isEmpty()) return value.getClass().getSimpleName() + "{\n  " + String.join(",\n  ", parts) + "\n}";
        }
        return String.valueOf(value);
    }

    private static final class Pending {
        final Query query; final long started = SystemClock.elapsedRealtime(); Runnable timeout;
        Pending(Query query) { this.query = query; }
    }
}
