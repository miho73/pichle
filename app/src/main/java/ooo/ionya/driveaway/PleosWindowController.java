package ooo.ionya.driveaway;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.model.area.WindowArea;
import ai.pleos.playground.vehicle.api.model.capability.AccessType;
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds;
import ai.pleos.playground.vehicle.constant.area.WindowValue;
import java.util.Map;
import kotlin.Unit;

/** Button-triggered front-left window control through Pleos Vehicle 2.3.2. */
public final class PleosWindowController {
    public interface Listener {
        void onStatus(String message);
    }

    public interface Callback {
        void onResult(boolean success, String message);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Vehicle vehicle;
    private int generation;
    private boolean busy;
    private Runnable timeout;
    private Runnable positionPoll;

    public PleosWindowController(Context context) {
        this(context, message -> { });
    }

    public PleosWindowController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void lowerDriverWindow() {
        listener.onStatus("운전석 창문을 확인하고 있어요.");
        lowerDriverWindow((success, message) -> listener.onStatus(message));
    }

    /** No window movement happens during construction or service initialization. */
    public void lowerDriverWindow(Callback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> lowerDriverWindow(callback));
            return;
        }
        if (busy) {
            callback.onResult(false, "창문 제어 응답을 기다리고 있어요.");
            return;
        }
        busy = true;
        int request = ++generation;
        timeout = () -> finish(request, callback, false,
                "차량 서비스 응답이 없어요. Pleos 연결과 창문 권한을 확인해 주세요.");
        main.postDelayed(timeout, 12000);
        try {
            if (vehicle == null) {
                Vehicle connection = new Vehicle(context);
                try {
                    connection.initialize();
                    vehicle = connection;
                } catch (Exception error) {
                    try { connection.release(); } catch (Exception ignored) { }
                    throw error;
                }
            }
            // Vehicle initializes asynchronously; this SDK request is queued until it is connected.
            vehicle.getWindow().checkWindowPositionCapabilities((capability, supported) -> {
                main.post(() -> {
                    if (!active(request)) return;
                    AccessType access = capability.getAccessType();
                    if (access != AccessType.WRITE && access != AccessType.READ_WRITE) {
                        finish(request, callback, false, "이 차량에서는 앱의 창문 제어를 지원하지 않아요.");
                        return;
                    }
                    lowerSupportedDriverWindow(request, supported, callback);
                });
                return Unit.INSTANCE;
            }, error -> {
                main.post(() -> finish(request, callback, false, describe(error)));
                return Unit.INSTANCE;
            });
        } catch (Exception error) {
            finish(request, callback, false, describe(error));
        }
    }

    private void lowerSupportedDriverWindow(int request,
            Map<WindowArea, AreaBounds<Integer>> supported, Callback callback) {
        // A grouped area must not accidentally lower additional passenger windows.
        WindowArea driver = new WindowArea(WindowValue.ROW_1_LEFT);
        AreaBounds<Integer> bounds = supported.get(driver);
        if (bounds == null || bounds.getMaxValue() == null || bounds.getMaxValue() <= 0) {
            finish(request, callback, false, "운전석 창문의 지원 범위를 확인할 수 없어요.");
            return;
        }
        // WINDOW_POS follows Automotive semantics: maximum is fully open, zero is closed.
        int fullyOpen = bounds.getMaxValue();
        try {
            vehicle.getWindow().setWindowPosition(driver, fullyOpen, (area, value) -> {
                main.post(() -> verifyWindowPosition(request, driver, fullyOpen, callback));
                return Unit.INSTANCE;
            }, error -> {
                main.post(() -> finish(request, callback, false, describe(error)));
                return Unit.INSTANCE;
            });
        } catch (Exception error) {
            finish(request, callback, false, describe(error));
        }
    }

    private void verifyWindowPosition(int request, WindowArea driver, int fullyOpen, Callback callback) {
        if (!active(request)) return;
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = () -> finish(request, callback, false,
                "내림 요청은 전달했지만 창문 위치가 아직 변경되지 않았어요. 차량 상태를 확인해 주세요.");
        main.postDelayed(timeout, 8000);
        listener.onStatus("창문 내림 요청을 전달했어요. 창문 위치를 확인하고 있어요.");
        pollWindowPosition(request, driver, fullyOpen, callback);
    }

    private void pollWindowPosition(int request, WindowArea driver, int fullyOpen, Callback callback) {
        if (!active(request)) return;
        try {
            vehicle.getWindow().getCurrentWindowPosition(driver, position -> {
                main.post(() -> {
                    if (!active(request)) return;
                    if (position != null && position == fullyOpen) {
                        finish(request, callback, true, "운전석 창문이 내려갔어요.");
                    } else {
                        positionPoll = () -> pollWindowPosition(request, driver, fullyOpen, callback);
                        main.postDelayed(positionPoll, 500);
                    }
                });
                return Unit.INSTANCE;
            }, error -> {
                main.post(() -> finish(request, callback, false,
                        "내림 요청은 전달했지만 창문 위치를 확인하지 못했어요. 차량 상태를 확인해 주세요."));
                return Unit.INSTANCE;
            });
        } catch (Exception error) {
            finish(request, callback, false,
                    "내림 요청은 전달했지만 창문 위치를 확인하지 못했어요. 차량 상태를 확인해 주세요.");
        }
    }

    private boolean active(int request) {
        return busy && request == generation;
    }

    private void finish(int request, Callback callback, boolean success, String message) {
        if (!active(request)) return;
        busy = false;
        if (timeout != null) main.removeCallbacks(timeout);
        if (positionPoll != null) main.removeCallbacks(positionPoll);
        timeout = null;
        positionPoll = null;
        callback.onResult(success, message);
    }

    /** Invalidate pending callbacks before releasing the vehicle service. */
    public void release() {
        ++generation;
        busy = false;
        if (timeout != null) main.removeCallbacks(timeout);
        if (positionPoll != null) main.removeCallbacks(positionPoll);
        timeout = null;
        positionPoll = null;
        if (vehicle != null) {
            try { vehicle.release(); } catch (Exception ignored) { }
            vehicle = null;
        }
    }

    public void close() {
        release();
    }

    private String describe(Exception error) {
        if (error instanceof SecurityException) return "창문 제어 권한이 허용되지 않았어요.";
        String detail = error.getLocalizedMessage();
        return "창문을 내리지 못했어요." + (detail == null || detail.isEmpty() ? "" : " " + detail);
    }
}
