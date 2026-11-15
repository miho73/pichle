package ooo.ionya.driveaway;

import java.util.List;

/**
 * Mobile-to-server contract for the MVP. The demo screen currently uses local sample data;
 * replace this interface with the app's HTTP client when the Pichle service is available.
 */
public interface PichleApi {
    /** GET /v1/stores?areaId={coarseAreaId}; never send exact GPS coordinates. */
    List<StoreSummary> findNearbyStores(String coarseAreaId) throws Exception;

    /** GET /v1/stores/{storeId}/menu */
    List<MenuItem> getMenu(String storeId) throws Exception;

    /** POST /v1/orders (partner stores only; explicit user confirmation required) */
    OrderReceipt submitOrder(OrderRequest request) throws Exception;

    /** POST /v1/visit-signals; accepts only coarse, store-scoped, non-identifying signals. */
    void submitVisitSignal(VisitSignal signal) throws Exception;

    final class StoreSummary {
        public final String id, name, category, address;
        public final double latitude, longitude;
        public final int distanceMeters, driveMinutes, waitMinutes;
        public final boolean electronicOrdering;
        public StoreSummary(String id, String name, String category, int distanceMeters,
                            int driveMinutes, int waitMinutes, boolean electronicOrdering,
                            double latitude, double longitude, String address) {
            this.id=id; this.name=name; this.category=category; this.distanceMeters=distanceMeters;
            this.driveMinutes=driveMinutes; this.waitMinutes=waitMinutes;
            this.electronicOrdering=electronicOrdering;
            this.latitude=latitude; this.longitude=longitude; this.address=address;
        }
    }

    final class MenuItem {
        public final String id, name, optionSchema;
        public final int priceKrw;
        public MenuItem(String id, String name, int priceKrw, String optionSchema) {
            this.id=id; this.name=name; this.priceKrw=priceKrw; this.optionSchema=optionSchema;
        }
    }

    final class OrderRequest {
        public final String storeId;
        public final List<String> menuItemIds;
        public final String customerNote;
        public OrderRequest(String storeId, List<String> menuItemIds, String customerNote) {
            this.storeId=storeId; this.menuItemIds=menuItemIds; this.customerNote=customerNote;
        }
    }

    final class OrderReceipt {
        public final String orderId, status, pickupCode;
        public OrderReceipt(String orderId, String status, String pickupCode) {
            this.orderId=orderId; this.status=status; this.pickupCode=pickupCode;
        }
    }

    /** Do not put a user ID, exact GPS coordinate, route, VIN, or account identifier here. */
    final class VisitSignal {
        public final String storeId;
        public final int dwellSeconds, stoppedSeconds, distanceMeters, tripSeconds;
        public VisitSignal(String storeId, int dwellSeconds, int stoppedSeconds,
                           int distanceMeters, int tripSeconds) {
            this.storeId=storeId; this.dwellSeconds=dwellSeconds; this.stoppedSeconds=stoppedSeconds;
            this.distanceMeters=distanceMeters; this.tripSeconds=tripSeconds;
        }
    }
}
