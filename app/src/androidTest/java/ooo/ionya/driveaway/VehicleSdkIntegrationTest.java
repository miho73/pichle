package ooo.ionya.driveaway;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.rules.TestName;
import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Opt-in integration tests: execute only on an emulator, never on a connected vehicle. */
@RunWith(AndroidJUnit4.class)
public class VehicleSdkIntegrationTest {
    @Rule public TestName testName = new TestName();
    private Instrumentation instrumentation;
    private Context context;
    private ApiCatalog catalog;
    private ApiEngine engine;
    @Before public void connect() throws Exception {
        Assume.assumeTrue("Pass -e vehicleIntegration true", "true".equals(
                InstrumentationRegistry.getArguments().getString("vehicleIntegration")));
        Assume.assumeTrue("Emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        catalog = new ApiCatalog(context);
        CountDownLatch ready = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            engine = new ApiEngine(context, message -> {
                if (engine != null && engine.connection.equals("차량 서비스 응답 확인됨")) ready.countDown();
            });
            engine.connect(catalog);
        });
        assertTrue("Vehicle service did not answer", ready.await(15, TimeUnit.SECONDS));
    }
    @After public void release() throws Exception {
        if (engine == null) return;
        instrumentation.runOnMainSync(() -> {
            try {
                Files.write(new File(context.getFilesDir(), "vehicle-integration-" + testName.getMethodName() + ".json").toPath(), engine.reportJson().getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) { throw new AssertionError(error); }
            engine.release();
        });
    }
    @Test public void everyCapabilityAndGetterReachesTheRealService() throws Exception {
        int capabilities = 0, getters = 0;
        for (ApiCatalog.Api api : catalog.all) {
            if (api.kind != ApiCatalog.Kind.CAPABILITY && api.kind != ApiCatalog.Kind.GET) continue;
            AtomicBoolean answered = new AtomicBoolean();
            CountDownLatch done = new CountDownLatch(1);
            Object[] args = inputs(api);
            instrumentation.runOnMainSync(() -> engine.invoke(api, args, success -> { answered.set(true); done.countDown(); }));
            assertTrue(api.title() + " callback/timeout not handled", done.await(12, TimeUnit.SECONDS));
            assertTrue(answered.get());
            // Unsupported hardware/permission errors are valid results; reflection/type errors are not.
            assertFalse(api.title() + engine.lastResult, engine.lastResult.contains("IllegalArgumentException: argument type mismatch"));
            assertFalse(api.title() + engine.lastResult, engine.lastResult.contains("IllegalAccessException"));
            if (api.kind == ApiCatalog.Kind.CAPABILITY) ++capabilities; else ++getters;
        }
        assertEquals(168, capabilities); assertEquals(109, getters);
    }
    @Test public void controlAndListenerUseTypedArgumentsAndExactUnregisterIdentity() throws Exception {
        ApiCatalog.Api get = api("Door", "getDoorLockState");
        invokeAndAwait(api("Door", "checkDoorLockCapabilities"), new Object[2]);
        assertTrue(engine.lastResult, engine.lastResult.contains("SUCCESS"));
        List<?> supported = (List<?>) engine.lastValues[1];
        assertFalse(supported.isEmpty());
        Object[] getArgs = inputs(get);
        getArgs[0] = supported.get(supported.size() - 1);
        invokeAndAwait(get, getArgs);
        String initial = engine.lastResult;
        assertTrue(initial, initial.contains("SUCCESS"));
        boolean locked = initial.contains("true");
        ApiCatalog.Api set = api("Door", "setDoorLock");
        Object[] setArgs = inputs(set); setArgs[0] = getArgs[0]; setArgs[1] = locked;
        invokeAndAwait(set, setArgs); // Write the existing value; leave emulator state unchanged.
        assertTrue(engine.lastResult, engine.lastResult.contains("SUCCESS"));
        ApiCatalog.Api register = api("Door", "registerDoorLockState");
        CountDownLatch event = new CountDownLatch(1);
        Object[] registerArgs = inputs(register); registerArgs[0] = getArgs[0];
        instrumentation.runOnMainSync(() -> engine.invoke(register, registerArgs, null));
        // Poll on the main thread to establish visibility of asynchronously delivered events.
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean received = new AtomicBoolean();
            instrumentation.runOnMainSync(() -> received.set(engine.results.stream().anyMatch(row -> row.optString("state").equals("EVENT"))));
            if (received.get()) { event.countDown(); break; }
            Thread.sleep(50);
        }
        assertEquals("No initial subscription event", 0, event.getCount());
        instrumentation.runOnMainSync(() -> {
            assertEquals(1, engine.subscriptions.size());
            assertTrue(engine.unsubscribe(engine.subscriptions.values().iterator().next()));
            assertTrue(engine.subscriptions.isEmpty());
        });
    }
    private void invokeAndAwait(ApiCatalog.Api api, Object[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> engine.invoke(api, args, success -> done.countDown()));
        assertTrue(api.title(), done.await(12, TimeUnit.SECONDS));
    }
    private ApiCatalog.Api api(String domain, String name) {
        for (ApiCatalog.Api api : catalog.all) if (api.domain.equals(domain) && api.method.getName().equals(name) && !api.deprecated) return api;
        throw new AssertionError(name);
    }
    private Object[] inputs(ApiCatalog.Api api) {
        try {
            Object[] args = new Object[api.method.getParameterCount()];
            for (int i = 0; i < args.length; i++) {
                Class<?> type = api.method.getParameterTypes()[i];
                if (ApiCatalog.isFunction(type) || ApiCatalog.isListener(type)) continue;
                if (ApiCatalog.isArea(type)) {
                    Constructor<?> ctor = type.getConstructors()[0];
                    Class<?> element = ctor.getParameterTypes()[0].getComponentType();
                    Object array = Array.newInstance(element, 1);
                    Array.set(array, 0, element.getEnumConstants()[0]);
                    args[i] = ctor.newInstance(array);
                    String property = api.method.getName().replaceFirst("^(getCurrent|get|has|is)", "")
                            .replace("State", "").replace("Position", "").replace("Been", "");
                    for (Map.Entry<String, Object[]> capability : engine.capabilities.entrySet()) {
                        String name = capability.getKey();
                        if (!name.startsWith(api.domain + ".")) continue;
                        String candidate = name.substring(name.indexOf('.') + 1).replaceFirst("^check", "")
                                .replace("Capabilities", "").replace("State", "").replace("Position", "");
                        if (!candidate.equals(property)) continue;
                        for (Object payload : capability.getValue()) {
                            Collection<?> areas = payload instanceof Map ? ((Map<?, ?>) payload).keySet()
                                    : payload instanceof Collection ? (Collection<?>) payload : Collections.emptyList();
                            for (Object area : areas) if (type.isInstance(area)) { args[i] = area; break; }
                        }
                    }
                } else if (type.isEnum()) args[i] = type.getEnumConstants()[0];
                else if (type == boolean.class) args[i] = false;
                else if (type == int.class) args[i] = 0;
                else if (type == float.class) args[i] = 0f;
                else throw new IllegalArgumentException(type.getName());
            }
            return args;
        } catch (Exception error) { throw new AssertionError(api.key, error); }
    }
}
