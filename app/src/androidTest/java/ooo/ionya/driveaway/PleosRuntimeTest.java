package ooo.ionya.driveaway;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.model.area.WindowArea;
import ai.pleos.playground.vehicle.constant.area.WindowValue;
import kotlin.Unit;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Real service checks. The window test is guarded against execution on a physical vehicle. */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class PleosRuntimeTest {
    private static final String TAG = "PichleRuntimeTest";
    private Instrumentation instrumentation;
    private MainActivity activity;
    private OrderSpeechController speech;
    private PleosWindowController windows;
    private PleosNavigation navigation;
    private Vehicle readbackVehicle;

    @Before public void openForegroundActivityOnEmulator() throws Exception {
        Assume.assumeTrue("This test controls an emulator window and must never run on a real vehicle.",
                isEmulator());
        instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = (MainActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        // Keep the activity foreground for audio focus, but isolate each service client under test.
        instrumentation.runOnMainSync(() -> {
            for (Field field : MainActivity.class.getDeclaredFields()) {
                if (field.getType() != OrderSpeechController.class) continue;
                try {
                    field.setAccessible(true);
                    OrderSpeechController existing = (OrderSpeechController) field.get(activity);
                    if (existing != null) existing.close();
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError("Could not isolate the activity speech client", error);
                }
            }
        });
        Log.i(TAG, "Running service checks on emulator: " + Build.HARDWARE + " / " + Build.MODEL);
    }

    @After public void closeClients() {
        if (instrumentation == null) return;
        instrumentation.runOnMainSync(() -> {
            if (speech != null) speech.close();
            if (windows != null) windows.close();
            if (navigation != null) navigation.close();
            if (readbackVehicle != null) readbackVehicle.release();
            if (activity != null) activity.finish();
        });
        instrumentation.waitForIdleSync();
    }

    @Test(timeout = 40000) public void a_orderSpeechStartsAndCompletesPlayback() throws Exception {
        List<String> statuses = new CopyOnWriteArrayList<>();
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> completedEngine = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            speech = new OrderSpeechController(activity, message -> {
                record(statuses, "speech", message);
                if ("주문을 읽고 있습니다.".equals(message)) started.set(true);
                if ("주문 읽기를 마쳤습니다.".equals(message)) {
                    completedEngine.set(speechEngineName(speech));
                    done.set(true);
                    terminal.countDown();
                } else if (isFailure(message)) {
                    failure.compareAndSet(null, message);
                    terminal.countDown();
                }
            });
            // Calling before initialization completes also exercises request queuing.
            speech.speak("안녕하세요. 아이스 아메리카노 한 잔 주세요.");
        });
        assertTrue("No speech completion/error callback: " + statuses,
                terminal.await(35, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
        assertNull("Speech failed: " + statuses, failure.get());
        assertTrue("Ready alone is insufficient; playback never started: " + statuses, started.get());
        assertTrue("Playback did not complete: " + statuses, done.get());
        assertTrue("Speech completed through " + completedEngine.get()
                + "; this integration test requires the actual Pleos Speech Service, not fallback: " + statuses,
                "PLEOS".equals(completedEngine.get()));
    }

    @Test(timeout = 25000) public void b_driverWindowReportsActualVehicleFeedback() throws Exception {
        // Repeat the guard directly at the only point capable of initiating vehicle control.
        Assume.assumeTrue("Refusing window actuation outside emulator", isEmulator());
        List<String> statuses = new CopyOnWriteArrayList<>();
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean();
        AtomicBoolean delivered = new AtomicBoolean();
        AtomicReference<String> terminalMessage = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            windows = new PleosWindowController(activity, message -> {
                record(statuses, "window", message);
                if (message.contains("창문 내림 요청을 전달했어요.")) delivered.set(true);
            });
            windows.lowerDriverWindow((accepted, message) -> {
                record(statuses, "window", message);
                success.set(accepted);
                terminalMessage.set(message);
                terminal.countDown();
            });
        });
        assertTrue("No Vehicle SDK window callback: " + statuses,
                terminal.await(16, TimeUnit.SECONDS));
        assertTrue("Vehicle SDK never accepted the window command: " + statuses, delivered.get());
        WindowArea driver = new WindowArea(WindowValue.ROW_1_LEFT);
        AtomicReference<Integer> fullyOpen = new AtomicReference<>();
        AtomicReference<Integer> position = new AtomicReference<>();
        CountDownLatch boundsReady = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            readbackVehicle = new Vehicle(activity);
            readbackVehicle.initialize();
            readbackVehicle.getWindow().checkWindowPositionCapabilities((capability, supported) -> {
                if (supported.get(driver) != null) fullyOpen.set(supported.get(driver).getMaxValue());
                boundsReady.countDown(); return Unit.INSTANCE;
            }, error -> { boundsReady.countDown(); return Unit.INSTANCE; });
        });
        assertTrue("No capability response for position verification", boundsReady.await(4, TimeUnit.SECONDS));
        assertTrue("No fully-open range for driver window", fullyOpen.get() != null);
        for (int attempt = 0; attempt < 6; attempt++) {
            CountDownLatch read = new CountDownLatch(1);
            instrumentation.runOnMainSync(() -> readbackVehicle.getWindow().getCurrentWindowPosition(driver,
                    value -> { position.set(value); read.countDown(); return Unit.INSTANCE; },
                    error -> { read.countDown(); return Unit.INSTANCE; }));
            assertTrue("Window readback did not respond", read.await(1, TimeUnit.SECONDS));
            if (fullyOpen.get().equals(position.get())) break;
            Thread.sleep(250);
        }
        assertTrue("No current window position returned", position.get() != null);
        if (success.get()) {
            assertTrue("Controller claimed opened but readback differs: current=" + position.get() + ", expected=" + fullyOpen.get(),
                    fullyOpen.get().equals(position.get()));
        } else {
            // Some Pleos emulator images acknowledge WINDOW_POS writes but keep returning 0.
            // Verify that this is reported honestly, rather than treating command acceptance as motion.
            assertTrue("Unexpected window failure: " + terminalMessage.get(),
                    terminalMessage.get().contains("창문 위치가 아직 변경되지 않았어요"));
            assertTrue("Controller reported unchanged despite fully-open readback", !fullyOpen.get().equals(position.get()));
        }
        Log.i(TAG, "window readback: current=" + position.get() + ", fullyOpen=" + fullyOpen.get()
                + ", movementConfirmed=" + success.get());
    }

    @Test(timeout = 40000) public void c_navigationFindsSelectedStoreCoordinateRoute() throws Exception {
        List<String> statuses = new CopyOnWriteArrayList<>();
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicBoolean routed = new AtomicBoolean();
        AtomicReference<String> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            navigation = new PleosNavigation(activity, message -> {
                record(statuses, "navigation", message);
                if (message.contains("경로 탐색이 완료됐어요.") || message.contains("경로 안내를 시작했어요.")) {
                    routed.set(true);
                    terminal.countDown();
                } else if (isFailure(message)) {
                    failure.compareAndSet(null, message);
                    terminal.countDown();
                }
            });
            PichleApi.StoreSummary store = new LocalPichleApi().findNearbyStores("songdo").get(0);
            navigation.navigate(store.latitude, store.longitude, store.name, store.address);
        });
        assertTrue("No route result (a sent request is not route success): " + statuses,
                terminal.await(36, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
        assertNull("Navigation failed: " + statuses, failure.get());
        assertTrue("NaviHelper never reported completed route search or a valid started route: " + statuses, routed.get());
    }

    private static boolean isEmulator() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        return hardware.contains("ranchu") || hardware.contains("goldfish");
    }

    private static String speechEngineName(OrderSpeechController controller) {
        try {
            Field field = OrderSpeechController.class.getDeclaredField("engine");
            field.setAccessible(true);
            return String.valueOf(field.get(controller));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not identify the engine that completed playback", error);
        }
    }

    private static void record(List<String> statuses, String domain, String message) {
        statuses.add(message);
        Log.i(TAG, domain + ": " + message);
    }

    private static boolean isFailure(String message) {
        return message.contains("못했") || message.contains("못해") || message.contains("못했습니다")
                || message.contains("없어요") || message.contains("없습니다")
                || message.contains("허용되지") || message.contains("지원하지")
                || message.contains("결과가 아직") || message.contains("권한을 확인")
                || message.contains("다시 눌러") || message.contains("전달하지")
                || message.contains("확인할 수") || message.contains("설치해")
                || message.contains("응답하지") || message.contains("응답이 끊겼");
    }
}
