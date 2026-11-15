package ooo.ionya.driveaway;

import android.app.Activity;
import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Supplies the same concrete bitmap to system task surfaces and the app launcher. */
final class AppTaskIcon {
    private static Bitmap icon;

    @SuppressWarnings("deprecation")
    static void apply(Activity activity) {
        if (icon == null) {
            icon = BitmapFactory.decodeResource(activity.getResources(), R.drawable.pichle_app_icon);
        }
        activity.setTaskDescription(new ActivityManager.TaskDescription(
                activity.getString(R.string.app_name), icon,
                activity.getColor(R.color.pichle_background)));
    }

    private AppTaskIcon() { }
}
