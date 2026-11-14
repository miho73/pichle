package ooo.ionya.driveaway;

import android.content.Context;
import android.content.pm.PackageManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VehicleSdkContractTest {
    @Test public void everyPublishedApiHasMetadataAndAnInputAdapter() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ApiCatalog catalog = new ApiCatalog(context);
        assertEquals(20, catalog.domains.size());
        assertEquals(517, catalog.all.size());
        Set<String> keys = new HashSet<>();
        Set<String> declared = new HashSet<>(Arrays.asList(context.getPackageManager()
                .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions));
        Map<ApiCatalog.Kind, Integer> counts = new EnumMap<>(ApiCatalog.Kind.class);
        for (ApiCatalog.Api api : catalog.all) {
            assertTrue(api.key, keys.add(api.key));
            assertTrue(api.key + " permission", declared.contains(api.permission));
            counts.put(api.kind, counts.getOrDefault(api.kind, 0) + 1);
            Type[] parameters = api.method.getGenericParameterTypes();
            for (int i = 0; i < parameters.length; i++) {
                assertTrue(api.key + " parameter " + parameters[i], ApiCatalog.supports(parameters[i]));
                assertFalse(api.key + " missing parameter name", api.name(i).startsWith("arg"));
                if (ApiCatalog.isFunction(api.method.getParameterTypes()[i])) {
                    assertTrue(api.key, api.name(i).equals("onComplete") || api.name(i).equals("onFailed"));
                    assertEquals(api.key, api.name(i).equals("onFailed"), api.isFailure(i));
                }
            }
            if (api.kind == ApiCatalog.Kind.REGISTER) {
                Method unregister = api.getter.getReturnType().getMethod("un" + api.method.getName(), api.method.getParameterTypes());
                assertEquals(void.class, unregister.getReturnType());
            }
        }
        assertEquals(Integer.valueOf(168), counts.get(ApiCatalog.Kind.CAPABILITY));
        assertEquals(Integer.valueOf(109), counts.get(ApiCatalog.Kind.GET));
        assertEquals(Integer.valueOf(56), counts.get(ApiCatalog.Kind.CONTROL));
        assertEquals(Integer.valueOf(92), counts.get(ApiCatalog.Kind.REGISTER));
        assertEquals(Integer.valueOf(92), counts.get(ApiCatalog.Kind.UNREGISTER));
    }
}
