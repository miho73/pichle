package ooo.ionya.driveaway;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Menu data and the structured order contract shared with the dill HTTP service.
 */
public interface PichleApi {
    /** Embedded catalog; live nearby search uses NearbyFinder without sending exact GPS. */
    List<StoreSummary> findNearbyStores(String coarseAreaId) throws Exception;

    /** Embedded menu lookup. Brand menus are obtained separately by BrandMenuFetcher. */
    List<MenuItem> getMenu(String storeId) throws Exception;

    /** POST /v1/orders; explicit user confirmation required. */
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
        public final String clientRequestId, storeId, storeName;
        public final List<OrderLine> items;
        public final List<String> menuItemIds;
        public final String customerNote;

        public OrderRequest(String clientRequestId, String storeId, String storeName,
                            List<OrderLine> items, String customerNote) {
            this.clientRequestId = clientRequestId;
            this.storeId = storeId;
            this.storeName = storeName;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            ArrayList<String> ids = new ArrayList<>();
            for (OrderLine item : items) ids.add(item.menuItemId);
            this.menuItemIds = Collections.unmodifiableList(ids);
            this.customerNote = customerNote == null ? "" : customerNote;
        }

        @Deprecated
        public OrderRequest(String storeId, List<String> menuItemIds, String customerNote) {
            this(java.util.UUID.randomUUID().toString(), storeId, storeId,
                    legacyItems(menuItemIds), customerNote);
        }

        private static List<OrderLine> legacyItems(List<String> ids) {
            ArrayList<OrderLine> items = new ArrayList<>();
            for (String id : ids) items.add(new OrderLine(id, id, 1, null, ""));
            return items;
        }
    }

    final class OrderLine {
        public final String menuItemId, name, options;
        public final int quantity;
        public final Integer unitPriceKrw;

        public OrderLine(String menuItemId, String name, int quantity, Integer unitPriceKrw, String options) {
            this.menuItemId = menuItemId; this.name = name; this.quantity = quantity;
            this.unitPriceKrw = unitPriceKrw; this.options = options == null ? "" : options;
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
