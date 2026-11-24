package ooo.ionya.driveaway;

import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Tracks the car's UX (distraction optimization) restrictions.
 * Devices without the car library are treated as unrestricted; on a car the UI stays
 * restricted until the restriction service is connected.
 */
public final class CarUxGate implements AutoCloseable {
    public interface Listener { void onChanged(State state); }

    public static final class State {
        public final boolean carDevice, connected, requiresDo;
        public final int activeRestrictions, maxDepth, maxItems, maxStringLength;

        private State(boolean carDevice, boolean connected, boolean requiresDo,
                      int activeRestrictions, int maxDepth, int maxItems, int maxStringLength) {
            this.carDevice = carDevice; this.connected = connected; this.requiresDo = requiresDo;
            this.activeRestrictions = activeRestrictions; this.maxDepth = maxDepth;
            this.maxItems = maxItems; this.maxStringLength = maxStringLength;
        }

        static State unrestricted() { return new State(false, true, false, 0, -1, -1, -1); }
        static State pending() { return new State(true, false, true, 0, 0, 0, 0); }

        static State of(CarUxRestrictions r) {
            return new State(true, true, r.isRequiresDistractionOptimization(),
                    r.getActiveRestrictions(), r.getMaxContentDepth(),
                    r.getMaxCumulativeContentItems(), r.getMaxRestrictedStringLength());
        }

        /**
         * Whether the full UI needs no adjustments. A false result does not prohibit
         * menus or navigation: apply the individual flags and limits to each feature.
         * The baseline requiresDo state alone does not remove any app screens.
         */
        public boolean canUseComplexUi() {
            return !carDevice || (connected && (!requiresDo || activeRestrictions == 0));
        }

        public boolean keyboardAllowed() {
            return allows(CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD);
        }

        public boolean filteringAllowed() {
            return allows(CarUxRestrictions.UX_RESTRICTIONS_NO_FILTERING);
        }

        public boolean setupAllowed() {
            return allows(CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP);
        }

        public boolean voiceTranscriptionAllowed() {
            return allows(CarUxRestrictions.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION);
        }

        public boolean textMessagesAllowed() {
            return allows(CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE);
        }

        private boolean allows(int restriction) {
            return !carDevice || (connected
                    && (!requiresDo || (activeRestrictions & restriction) == 0));
        }

        public boolean contentAllowed() {
            return (!carDevice || connected)
                    && (!limitsContent() || (maxItems > 0 && maxDepth > 0));
        }

        public boolean limitsContent() {
            return carDevice && (!connected || (requiresDo
                    && (activeRestrictions & CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT) != 0));
        }

        /** The item count is cumulative along one task path, not a per-page allowance. */
        public boolean allowsContent(int cumulativeItems, int depth) {
            return cumulativeItems >= 0 && depth >= 0 && contentAllowed()
                    && (!limitsContent() || (cumulativeItems <= maxItems && depth <= maxDepth));
        }

        public boolean limitsStrings() {
            return carDevice && (!connected || (requiresDo
                    && (activeRestrictions & CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH) != 0));
        }

        public String limitText(String value) {
            if (value == null) return null;
            if (!limitsStrings() || value.length() <= maxStringLength) return value;
            if (maxStringLength <= 0) return "";
            int end = maxStringLength - 1;
            if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            return value.substring(0, end) + "…";
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean carDevice;
    private volatile State state;
    private Car car;
    private CarUxRestrictionsManager manager;
    private boolean started, closed;
    private int session;

    public CarUxGate(Context context, Listener listener) {
        this.context = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        this.listener = listener;
        this.carDevice = detectCar(context);
        this.state = carDevice ? State.pending() : State.unrestricted();
    }

    private static boolean detectCar(Context context) {
        try {
            if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                return true;
            }
            Class.forName("android.car.Car");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public State getState() { return state; }

    public void start() {
        if (!carDevice || closed || started) return;
        started = true;
        final int activeSession = ++session;
        try {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                    (connectedCar, ready) -> {
                        // May run before createCar() returns, so `car` can still be null here.
                        if (closed || !started || activeSession != session) return;
                        if (ready) attach(connectedCar, activeSession); else detach(true);
                    });
        } catch (Throwable error) {
            car = null;
            publish(State.pending());
        }
    }

    private void attach(Car connectedCar, int activeSession) {
        try {
            manager = (CarUxRestrictionsManager) connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
            if (manager == null) { publish(State.pending()); return; }
            manager.registerListener(restrictions -> {
                if (!closed && started && activeSession == session && restrictions != null) publish(State.of(restrictions));
            });
            CarUxRestrictions current = manager.getCurrentCarUxRestrictions();
            publish(current == null ? State.pending() : State.of(current));
        } catch (Throwable error) {
            manager = null;
            publish(State.pending());
        }
    }

    private void detach(boolean notify) {
        try { if (manager != null) manager.unregisterListener(); } catch (Throwable ignored) { }
        manager = null;
        if (notify) publish(State.pending());
    }

    public void stop() {
        if (!started) return;
        started = false;
        ++session;
        detach(false);
        try { if (car != null) car.disconnect(); } catch (Throwable ignored) { }
        car = null;
        if (carDevice) state = State.pending();
    }

    @Override public void close() {
        if (closed) return;
        stop();
        closed = true;
    }

    private void publish(State next) {
        state = next;
        if (listener != null) listener.onChanged(next);
    }
}
