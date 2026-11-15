package ooo.ionya.driveaway;

import java.util.Arrays;
import java.util.List;

/** Local-only stand-in for the Pichle backend, so the end-to-end demo works offline. */
public final class LocalPichleApi implements PichleApi {
    private int nextOrder = 104;

    @Override public List<StoreSummary> findNearbyStores(String coarseAreaId) {
        return Arrays.asList(
                new StoreSummary("morningbrew-songdo", "모닝브루 송도점", "커피 · DT", 1200, 4, 5, true,
                        37.3887, 126.6433, "인천광역시 연수구 송도동"),
                new StoreSummary("burgerstation-central", "버거스테이션 센트럴점", "버거 · DT", 2400, 7, 9, true,
                        37.3867, 126.6508, "인천광역시 연수구 송도동"),
                new StoreSummary("dailycup-incheon", "데일리컵 인천대점", "커피 · DT", 3100, 3, 2, false,
                        37.3790, 126.6352, "인천광역시 연수구 송도동"));
    }

    @Override public List<MenuItem> getMenu(String storeId) {
        if ("burgerstation-central".equals(storeId)) return Arrays.asList(
                new MenuItem("cheese-set", "치즈버거 세트", 8900, "감자 변경, 음료 선택"),
                new MenuItem("chicken-set", "치킨버거 세트", 9200, "소스 제외, 음료 선택"),
                new MenuItem("bulgogi-burger", "불고기버거", 6100, "양파 제외, 피클 제외"));
        if ("dailycup-incheon".equals(storeId)) return Arrays.asList(
                new MenuItem("iced-americano", "아이스 아메리카노", 3900, "샷 추가, 시럽"),
                new MenuItem("cold-brew", "콜드브루", 4800, "연하게, 얼음 적게"),
                new MenuItem("lemon-tea", "레몬티", 4500, "따뜻하게, 시럽 적게"));
        return Arrays.asList(
                new MenuItem("americano", "아메리카노", 4300, "샷 추가, 디카페인"),
                new MenuItem("cafe-latte", "카페라떼", 5000, "우유 변경, 샷 추가"),
                new MenuItem("vanilla-latte", "바닐라 라떼", 5500, "시럽 적게, 디카페인"));
    }

    @Override public OrderReceipt submitOrder(OrderRequest request) {
        return new OrderReceipt("PICHLE-" + nextOrder, "RECEIVED", "P-" + nextOrder++);
    }

    @Override public void submitVisitSignal(VisitSignal signal) {
        // Intentionally kept in memory only. No location or vehicle data leaves this device.
    }
}
