package ooo.ionya.driveaway;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.constants.RouteOption;
import ai.pleos.playground.navi.data.DestinationArrivedInfo;
import ai.pleos.playground.navi.data.NavigationScreenRequest;
import ai.pleos.playground.navi.data.RouteInfo;
import ai.pleos.playground.navi.data.RouteStartInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import kotlin.Unit;

/** Connects a coordinate destination to the built-in Pleos navigation application. */
public final class PleosNavigation implements AutoCloseable {
    public interface Listener { void onStatus(String message); }

    private static final String ROUTE_PERMISSION = "pleos.car.permission.NAVI_ROUTE_SEARCH";
    private static final String SCREEN_PERMISSION = "pleos.car.permission.NAVI_CUSTOM_MAP";
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NaviHelper helper;
    private Destination pending;
    private Destination active;
    private boolean connected, connecting, awaitingRoute, closed;

    private final Runnable connectionTimeout = () -> {
        if (!connecting || closed) return;
        pending = null;
        disconnect();
        status("차량 내비게이션에 연결하지 못했어요. 연결 상태를 확인하고 다시 눌러 주세요.");
    };

    private final Runnable routeTimeout = () -> {
        if (!awaitingRoute || closed) return;
        awaitingRoute = false;
        status("경로 탐색 결과가 아직 도착하지 않았어요. 내비게이션 화면을 확인해 주세요.");
    };

    private final NaviHelperEventListener events = new NaviHelperEventListener() {
        @Override public void onRouteStarted(RouteStartInfo info) {
            main.post(() -> {
                if (!awaitingRoute || active == null || closed) return;
                // Pleos Connect 2.1 may emit only this event once guidance is ready.
                // A matching destination and resolved route metrics distinguish it
                // from a search-start notification or another app's route event.
                if (info != null && matchesActiveName(info.getName())
                        && info.getDistance() > 0 && info.getDuration() > 0) {
                    awaitingRoute = false;
                    main.removeCallbacks(routeTimeout);
                    status(active.name + "까지 경로 안내를 시작했어요.");
                    showNavigationScreen();
                } else {
                    status(active.name + "까지 경로를 탐색하고 있어요.");
                }
            });
        }

        @Override public void onRoutingCompleted() {
            main.post(() -> {
                if (!awaitingRoute || active == null || closed) return;
                awaitingRoute = false;
                main.removeCallbacks(routeTimeout);
                status(active.name + "까지 경로 탐색이 완료됐어요. 차량 내비게이션의 안내를 확인해 주세요.");
                showNavigationScreen();
            });
        }

        @Override public void onError(NaviErrorCode error) {
            main.post(() -> {
                if (active == null || closed) return;
                awaitingRoute = false;
                main.removeCallbacks(routeTimeout);
                status(routeError(error));
            });
        }

        @Override public void onRouteCancelled() {
            main.post(() -> {
                if (active == null || closed) return;
                // Replacing an existing route cancels that previous route first.
                // The callback carries no destination/request ID, so it must not
                // discard the new request while its route result is pending.
                if (awaitingRoute) return;
                awaitingRoute = false;
                active = null;
                main.removeCallbacks(routeTimeout);
                status("차량 내비게이션에서 경로 안내를 취소했어요.");
            });
        }

        @Override public void onDestinationArrived(DestinationArrivedInfo info) {
            main.post(() -> {
                if (active == null || closed || info == null || !matchesActiveName(info.getName())) return;
                awaitingRoute = false;
                main.removeCallbacks(routeTimeout);
                status(active.name + "에 도착했어요. 안전하게 정차한 뒤 주문을 수령해 주세요.");
            });
        }
    };

    public PleosNavigation(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void navigate(double latitude, double longitude, String name, String address) {
        main.post(() -> {
            if (closed) return;
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180
                    || name == null || name.trim().isEmpty()) {
                status("매장의 목적지 좌표를 확인할 수 없어요.");
                return;
            }
            if (context.checkSelfPermission(ROUTE_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                status("길안내에 필요한 차량 내비게이션 권한이 허용되지 않았어요.");
                return;
            }
            if (awaitingRoute || pending != null) {
                status("경로를 준비하고 있어요. 잠시 기다려 주세요.");
                return;
            }
            pending = new Destination(latitude, longitude, name, address == null ? "" : address);
            if (connected) sendRoute();
            else connect();
        });
    }

    private void connect() {
        if (closed || connecting) return;
        connecting = true;
        status("차량 내비게이션에 연결하고 있어요.");
        try {
            helper = new NaviHelper(context);
            NaviHelper connectingHelper = helper;
            main.postDelayed(connectionTimeout, 15_000);
            helper.initialize(() -> {
                main.post(() -> {
                    if (closed || helper != connectingHelper || !connecting) return;
                    main.removeCallbacks(connectionTimeout);
                    connecting = false;
                    connected = true;
                    try {
                        helper.addListener(events);
                        sendRoute();
                    } catch (RuntimeException error) {
                        fail("내비게이션 연결을 준비하지 못했어요. 다시 시도해 주세요.");
                    }
                });
                return Unit.INSTANCE;
            }, error -> {
                main.post(() -> {
                    if (closed || helper != connectingHelper) return;
                    fail("차량 내비게이션을 연결하지 못했어요. Pleos 내비게이션 서비스를 확인해 주세요.");
                });
                return Unit.INSTANCE;
            });
        } catch (RuntimeException error) {
            fail("차량 내비게이션을 시작하지 못했어요. 연결 상태와 권한을 확인해 주세요.");
        }
    }

    private void sendRoute() {
        if (closed || !connected || helper == null || pending == null) return;
        Destination destination = pending;
        pending = null;
        active = destination;
        awaitingRoute = true;
        status(destination.name + "까지 경로를 요청하고 있어요.");
        try {
            // Verified against navi-data 3.0.4: latitude comes BEFORE longitude.
            RouteInfo route = new RouteInfo(destination.latitude, destination.longitude,
                    null, null, destination.name, "", destination.address, "0",
                    RouteOption.RECOMMENDED, "", false);
            helper.requestRoute(route);
            main.postDelayed(routeTimeout, 30_000);
            showNavigationScreen();
        } catch (RuntimeException error) {
            fail("목적지를 전달하지 못했어요. 차량 내비게이션 연결과 권한을 확인해 주세요.");
        }
    }

    private void showNavigationScreen() {
        if (closed || !connected || helper == null) return;
        if (context.checkSelfPermission(SCREEN_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            status("길안내를 요청했어요. 내비게이션 화면 표시 권한을 확인해 주세요.");
            return;
        }
        try {
            helper.requestNavigationScreen(NavigationScreenRequest.Foreground.INSTANCE);
        } catch (RuntimeException error) {
            status("내비게이션 화면을 열지 못했어요. 차량의 내비게이션을 직접 확인해 주세요.");
        }
    }

    private String routeError(NaviErrorCode error) {
        if (error == null) return "경로를 찾지 못했어요. 목적지와 내비게이션 연결을 확인해 주세요.";
        switch (error) {
            case CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE:
                return "목적지가 너무 가까워 경로 안내를 시작할 수 없어요.";
            case CAUSE_SEARCH_ROUTE_OVER_DISTANCE:
                return "목적지가 경로 탐색 가능 거리를 벗어났어요.";
            case CAUSE_LOW_BATTERY:
                return "차량 배터리 상태로 경로 탐색을 진행하지 못했어요.";
            default:
                return "경로를 찾지 못했어요. 목적지와 내비게이션 연결을 확인해 주세요.";
        }
    }

    private boolean matchesActiveName(String name) {
        return active != null && name != null && active.name.trim().equalsIgnoreCase(name.trim());
    }

    private void fail(String message) {
        pending = null;
        active = null;
        awaitingRoute = false;
        main.removeCallbacks(routeTimeout);
        disconnect();
        status(message);
    }

    private void status(String message) {
        if (!closed && listener != null) listener.onStatus(message);
    }

    private void disconnect() {
        main.removeCallbacks(connectionTimeout);
        connected = false;
        connecting = false;
        NaviHelper old = helper;
        helper = null;
        if (old != null) {
            try { old.removeListener(events); } catch (RuntimeException ignored) { }
            try { old.release(); } catch (RuntimeException ignored) { }
        }
    }

    @Override public void close() {
        closed = true;
        pending = null;
        active = null;
        awaitingRoute = false;
        main.removeCallbacksAndMessages(null);
        disconnect();
    }

    private static final class Destination {
        final double latitude, longitude;
        final String name, address;
        Destination(double latitude, double longitude, String name, String address) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.name = name;
            this.address = address;
        }
    }
}
