package ooo.ionya.driveaway;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import ai.pleos.playground.location.fused.FusedLocation;
import ai.pleos.playground.location.fused.listener.FusedLocationListener;
import ai.pleos.playground.location.fused.model.LocationData;

import org.json.JSONException;
import org.json.JSONObject;

/** Explicit, local-only location diagnostics for the hidden developer screen. */
public final class FusedLocationProbe implements AutoCloseable {
    public interface Listener { void onResult(String title, String detail); }
    public static final String PERMISSION = "pleos.car.permission.FUSED_LOCATION";
    private static final long REQUEST_TIMEOUT_MS = 20_000;
    private static final long UPDATE_SILENCE_MS = 15_000;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FusedLocation sdk;
    private FusedLocationListener updates;
    private boolean registered, continuous, currentPending, lastKnownPending, closed;
    private int session, currentRequest, lastKnownRequest;
    private long currentStarted, lastUpdate;
    private Runnable currentTimeout, lastKnownTimeout, updateWatchdog;

    public FusedLocationProbe(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public static String[] requiredPermissions() { return new String[]{PERMISSION}; }

    /** Waits for the next non-null SDK update; the SDK's own timestamp remains visible. */
    public void getCurrentLocation() {
        onMain(() -> {
            if (!ready()) return;
            if (currentPending) {
                emit("현재 위치 수신 대기 중", "registerFusedLocationCallback의 다음 위치 응답을 기다리고 있습니다.");
                return;
            }
            currentPending = true;
            currentStarted = SystemClock.elapsedRealtime();
            final int request = ++currentRequest;
            currentTimeout = () -> {
                if (closed || !currentPending || request != currentRequest) return;
                currentPending = false;
                emit("현재 위치 시간 초과", "20초 동안 유효한 위치 업데이트를 받지 못했습니다.\n"
                        + "API: registerFusedLocationCallback\n"
                        + "차량 위치 서비스, 위치 공유 설정, FUSED_LOCATION 권한을 확인해주세요.");
                stopUnusedSubscription();
            };
            main.postDelayed(currentTimeout, REQUEST_TIMEOUT_MS);
            emit("현재 위치 수신 요청", "API: registerFusedLocationCallback\n다음 non-null 위치 응답을 1회 수신합니다. "
                    + "새 측위를 강제하는 API는 아니며 timestampMillis로 생성 시점을 확인할 수 있습니다.");
            register();
        });
    }

    /** The cached result is deliberately separate from the current-update operation. */
    public void getLastKnownLocation() {
        onMain(() -> {
            if (!ready()) return;
            if (lastKnownPending) {
                emit("마지막 위치 조회 대기 중", "getLastKnownLocation 응답을 기다리고 있습니다.");
                return;
            }
            lastKnownPending = true;
            final int request = ++lastKnownRequest;
            final int requestSession = session;
            final long started = SystemClock.elapsedRealtime();
            lastKnownTimeout = () -> {
                if (closed || !lastKnownPending || request != lastKnownRequest) return;
                lastKnownPending = false;
                emit("마지막 위치 조회 시간 초과", "API: getLastKnownLocation\n20초 동안 응답이 없습니다.");
                releaseIfIdle();
            };
            main.postDelayed(lastKnownTimeout, REQUEST_TIMEOUT_MS);
            emit("마지막 위치 조회 요청", "API: getLastKnownLocation\n이미 계산된 마지막 위치를 조회합니다. 새 측위를 요청하지 않습니다.");
            try {
                sdk.getLastKnownLocation(new FusedLocationListener() {
                    @Override public void onChanged(LocationData location) {
                        main.post(() -> {
                            if (!lastKnownActive(requestSession, request)) return;
                            lastKnownPending = false;
                            main.removeCallbacks(lastKnownTimeout);
                            emit(location == null ? "마지막 위치 없음" : "마지막 위치 수신",
                                    describe("getLastKnownLocation", location, SystemClock.elapsedRealtime() - started));
                            releaseIfIdle();
                        });
                    }
                    @Override public void onError(Exception error) {
                        main.post(() -> {
                            if (!lastKnownActive(requestSession, request)) return;
                            lastKnownPending = false;
                            main.removeCallbacks(lastKnownTimeout);
                            emit("마지막 위치 조회 오류", describeError("getLastKnownLocation", error));
                            releaseIfIdle();
                        });
                    }
                });
            } catch (RuntimeException | LinkageError error) {
                lastKnownPending = false;
                main.removeCallbacks(lastKnownTimeout);
                emit("마지막 위치 조회 오류", describeError("getLastKnownLocation", error));
                releaseIfIdle();
            }
        });
    }

    public void startUpdates() {
        onMain(() -> {
            if (!ready()) return;
            if (continuous) {
                emit("위치 업데이트 수신 중", "같은 리스너로 이미 구독하고 있습니다.");
                return;
            }
            continuous = true;
            lastUpdate = SystemClock.elapsedRealtime();
            emit("위치 업데이트 시작", "API: registerFusedLocationCallback\n위치 변경과 오류를 이 화면에서 계속 표시합니다.");
            register();
            if (continuous) startWatchdog();
        });
    }

    public void stopUpdates() {
        onMain(() -> {
            if (closed) return;
            boolean hadRequest = continuous || currentPending || registered || lastKnownPending;
            continuous = false;
            currentPending = false;
            lastKnownPending = false;
            ++currentRequest;
            ++lastKnownRequest;
            if (currentTimeout != null) main.removeCallbacks(currentTimeout);
            if (lastKnownTimeout != null) main.removeCallbacks(lastKnownTimeout);
            if (updateWatchdog != null) main.removeCallbacks(updateWatchdog);
            unregister();
            if (hadRequest) emit("위치 업데이트 중지", "API: unregisterFusedLocationCallback\n등록한 동일 리스너의 구독을 해제했습니다.");
            releaseIfIdle();
        });
    }

    private boolean ready() {
        if (closed) return false;
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            emit("Fused Location 권한 필요", "권한이 허용되지 않았습니다.\n" + PERMISSION);
            return false;
        }
        if (sdk != null) return true;
        try {
            FusedLocation candidate = new FusedLocation(context);
            if (!candidate.isSupported()) {
                emit("Fused Location 미지원", "isSupported() = false\n차량 서비스 패키지가 없거나 조회할 수 없습니다.\n"
                        + "package: ai.pleos.playground.service.location.fused");
                return false;
            }
            sdk = candidate;
            ++session;
            // The SDK queues calls until its asynchronous service binding completes.
            sdk.initialize();
            return true;
        } catch (RuntimeException | LinkageError error) {
            emit("Fused Location 초기화 오류", describeError("initialize", error));
            resetSession();
            return false;
        }
    }

    private void register() {
        if (registered || sdk == null || closed) return;
        final int activeSession = session;
        updates = new FusedLocationListener() {
            @Override public void onChanged(LocationData location) {
                main.post(() -> {
                    if (closed || activeSession != session || !registered) return;
                    lastUpdate = SystemClock.elapsedRealtime();
                    boolean single = currentPending && location != null;
                    if (continuous || currentPending) {
                        emit(location == null ? "위치 업데이트 없음" : single ? "현재 위치 수신" : "위치 업데이트 수신",
                                describe("registerFusedLocationCallback", location,
                                        currentPending ? lastUpdate - currentStarted : -1));
                    }
                    if (single) {
                        currentPending = false;
                        if (currentTimeout != null) main.removeCallbacks(currentTimeout);
                        stopUnusedSubscription();
                    }
                });
            }
            @Override public void onError(Exception error) {
                main.post(() -> {
                    if (closed || activeSession != session || !registered) return;
                    emit("위치 업데이트 오류", describeError("registerFusedLocationCallback", error));
                    continuous = false;
                    currentPending = false;
                    if (currentTimeout != null) main.removeCallbacks(currentTimeout);
                    if (updateWatchdog != null) main.removeCallbacks(updateWatchdog);
                    stopUnusedSubscription();
                });
            }
        };
        try {
            registered = true;
            sdk.registerFusedLocationCallback(updates);
        } catch (RuntimeException | LinkageError error) {
            registered = false;
            continuous = false;
            currentPending = false;
            if (currentTimeout != null) main.removeCallbacks(currentTimeout);
            emit("위치 업데이트 등록 오류", describeError("registerFusedLocationCallback", error));
            releaseIfIdle();
        }
    }

    private void startWatchdog() {
        if (updateWatchdog != null) main.removeCallbacks(updateWatchdog);
        updateWatchdog = new Runnable() {
            @Override public void run() {
                if (closed || !continuous) return;
                long silence = SystemClock.elapsedRealtime() - lastUpdate;
                if (silence >= UPDATE_SILENCE_MS) {
                    emit("위치 업데이트 대기", "최근 위치 응답 이후 " + silence + "ms가 지났습니다.\n"
                            + "구독은 유지 중입니다. 차량 위치 서비스와 위치 공유 설정을 확인해주세요.");
                }
                main.postDelayed(this, UPDATE_SILENCE_MS);
            }
        };
        main.postDelayed(updateWatchdog, UPDATE_SILENCE_MS);
    }

    private boolean lastKnownActive(int requestSession, int request) {
        return !closed && session == requestSession && lastKnownPending && lastKnownRequest == request;
    }

    private void stopUnusedSubscription() {
        if (continuous || currentPending) return;
        unregister();
        releaseIfIdle();
    }

    private void unregister() {
        if (sdk == null || !registered || updates == null) return;
        registered = false;
        try { sdk.unregisterFusedLocationCallback(updates); }
        catch (RuntimeException | LinkageError error) {
            emit("위치 구독 해제 오류", describeError("unregisterFusedLocationCallback", error));
        }
        updates = null;
    }

    private void releaseIfIdle() {
        if (!continuous && !currentPending && !lastKnownPending) resetSession();
    }

    private void resetSession() {
        ++session;
        unregister();
        FusedLocation old = sdk;
        sdk = null;
        updates = null;
        registered = false;
        if (old != null) {
            try { old.release(); }
            catch (RuntimeException | LinkageError error) { emit("위치 SDK 해제 오류", describeError("release", error)); }
        }
    }

    private String describe(String api, LocationData location, long elapsedMillis) {
        try {
            JSONObject data = new JSONObject();
            long receivedAt = System.currentTimeMillis();
            data.put("api", api);
            data.put("receivedAtEpochMillis", receivedAt);
            if (elapsedMillis >= 0) data.put("requestElapsedMillis", elapsedMillis);
            data.put("continuousUpdates", continuous);
            if (location == null) {
                data.put("location", JSONObject.NULL);
                data.put("note", "SDK가 null 위치를 반환했습니다. 좌표 0,0으로 대체하지 않습니다.");
                return data.toString(2);
            }
            JSONObject value = new JSONObject();
            value.put("latitude", finite(location.getLatitude()));
            value.put("longitude", finite(location.getLongitude()));
            value.put("altitude", finite(location.getAltitude()));
            value.put("heading", finite(location.getHeading()));
            value.put("confidence", finite(location.getConfidence()));
            value.put("timestampMillis", location.getTimestampMillis());
            data.put("location", value);
            long timestamp = location.getTimestampMillis();
            if (timestamp >= 946684800000L && timestamp <= receivedAt + 60_000L) {
                data.put("estimatedWallClockAgeMillis", Math.max(0L, receivedAt - timestamp));
                data.put("timestampInterpretation", "epoch milliseconds로 해석 가능한 값에 한해 생성 후 경과시간을 계산했습니다.");
            } else {
                data.put("estimatedWallClockAgeMillis", JSONObject.NULL);
                data.put("timestampInterpretation", "SDK timestamp의 시계 기준을 확정할 수 없어 경과시간을 계산하지 않았습니다.");
            }
            data.put("note", "altitude, heading, confidence는 SDK 원시값입니다. confidence를 미터 단위 정확도로 변환하지 않습니다.");
            data.put("sdkRaw", location.toString());
            return data.toString(2);
        } catch (JSONException error) {
            return "API: " + api + "\nSDK raw: " + String.valueOf(location) + "\n직렬화 오류: " + error.getMessage();
        }
    }

    private Object finite(double value) { return Double.isFinite(value) ? value : JSONObject.NULL; }
    private String describeError(String api, Throwable error) {
        return "API: " + api + "\n오류 타입: " + error.getClass().getName()
                + "\n오류 내용: " + String.valueOf(error.getMessage());
    }
    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
    }
    private void emit(String title, String detail) { if (!closed && listener != null) listener.onResult(title, detail); }

    @Override public void close() {
        onMain(() -> {
            if (closed) return;
            closed = true;
            continuous = false; currentPending = false; lastKnownPending = false;
            ++currentRequest; ++lastKnownRequest;
            main.removeCallbacksAndMessages(null);
            resetSession();
        });
    }
}
