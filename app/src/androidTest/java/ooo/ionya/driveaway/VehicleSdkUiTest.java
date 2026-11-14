package ooo.ionya.driveaway;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VehicleSdkUiTest {
    @Test public void all517ApisRenderTheirExecutableForms() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ApiCatalog catalog = new ApiCatalog(instrumentation.getTargetContext());
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(intent);
        try {
            for (ApiCatalog.Api api : catalog.all) {
                instrumentation.runOnMainSync(() -> {
                    activity.select(api);
                    String text = text(activity.getWindow().getDecorView());
                    assertTrue(api.key, text.contains(api.title()));
                    assertFalse(api.key + "\n" + text, text.contains("지원하지 않는 인자:"));
                    assertFalse(api.key + "\n" + text, text.contains("java.lang.IllegalArgumentException:"));
                    assertFalse(api.key + "\n" + text, text.contains("java.lang.NullPointerException:"));
                });
            }
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
    private String text(View view) {
        StringBuilder result = new StringBuilder();
        if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            result.append(text(((ViewGroup) view).getChildAt(i)));
        return result.toString();
    }
}
