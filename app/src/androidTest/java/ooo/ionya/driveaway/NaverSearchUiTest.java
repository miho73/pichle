package ooo.ionya.driveaway;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import static org.junit.Assert.*;

/** Opt-in real-network check; does not request navigation or operate vehicle controls. */
@RunWith(AndroidJUnit4.class)
public final class NaverSearchUiTest {
    @Test public void liveSearchDetailsAndRecreation() throws Exception {
        Assume.assumeTrue("Enable with -Pandroid.testInstrumentationRunnerArguments.naverLiveSearch=true",
                "true".equals(InstrumentationRegistry.getArguments().getString("naverLiveSearch")));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                EditText query = find(root(activity), EditText.class, view -> true);
                assertNotNull(query); query.setText("송도 스타벅스");
                label(activity, "검색").performClick();
            });
            AtomicBoolean loaded = new AtomicBoolean();
            long deadline = SystemClock.elapsedRealtime() + 35_000;
            while (!loaded.get() && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(activity -> loaded.set(find(root(activity), TextView.class,
                        view -> "매장 상세".contentEquals(view.getText())) != null));
                if (!loaded.get()) SystemClock.sleep(100);
            }
            screenshot("naver-search.png");
            assertTrue("Live search did not produce a store card; check network or Naver restrictions.", loaded.get());
            scenario.onActivity(activity -> label(activity, "매장 상세").performClick());
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertNotNull(label(activity, "이 매장으로 길안내"));
                assertNotNull(label(activity, "네이버 지도에서 보기"));
                assertNotNull(find(root(activity), TextView.class, view -> view.getText().toString().contains("메뉴·주문·대기시간 정보는 아직 연결되지 않았습니다.")));
            });
            screenshot("naver-place.png");
            scenario.onActivity(activity -> label(activity, "‹ 뒤로").performClick());
            scenario.onActivity(activity -> {
                assertNotNull(label(activity, "매장 상세"));
                label(activity, "시연 매장").performClick();
            });
            scenario.onActivity(activity -> assertNotNull(label(activity, "메뉴 보기")));
        }
    }

    private static View root(MainActivity activity) { return activity.getWindow().getDecorView(); }
    private static TextView label(MainActivity activity, String value) {
        TextView view = find(root(activity), TextView.class, text -> value.contentEquals(text.getText()));
        assertNotNull("Missing label: " + value, view); return view;
    }
    private static <T extends View> T find(View root, Class<T> type, Predicate<T> predicate) {
        if (type.isInstance(root) && predicate.test(type.cast(root))) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type, predicate); if (found != null) return found;
            }
        }
        return null;
    }
    private static void screenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
    }
}
