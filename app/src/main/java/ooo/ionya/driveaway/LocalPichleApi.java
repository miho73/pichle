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
                        37.3790, 126.6352, "인천광역시 연수구 송도동"),
                new StoreSummary("greenbean-canal", "그린빈 커넬워크점", "커피 · DT", 1800, 5, 3, true,
                        37.3984, 126.6385, "인천광역시 연수구 송도동"),
                new StoreSummary("pickburger-campus", "픽버거 캠퍼스타운점", "버거 · DT", 3600, 8, 4, false,
                        37.3882, 126.6629, "인천광역시 연수구 송도동"),
                new StoreSummary("sandwichpark-central", "샌드위치파크 센트럴점", "샌드위치 · DT", 2700, 6, 5, true,
                        37.3924, 126.6363, "인천광역시 연수구 송도동"));
    }

    @Override public List<MenuItem> getMenu(String storeId) {
        if ("greenbean-canal".equals(storeId)) return Arrays.asList(
                new MenuItem("greenbean-flatwhite", "플랫화이트", 5100, "디카페인, 우유 변경"),
                new MenuItem("greenbean-matcha", "말차 라떼", 5600, "시럽 적게, 우유 변경"),
                new MenuItem("greenbean-cookie", "초콜릿 쿠키", 3200, "따뜻하게, 개별 포장"));
        if ("pickburger-campus".equals(storeId)) return Arrays.asList(
                new MenuItem("pickburger-double", "더블치즈버거", 7900, "피클 제외, 양파 제외"),
                new MenuItem("pickburger-chicken", "크리스피 치킨버거", 7200, "소스 제외, 치즈 추가"),
                new MenuItem("pickburger-fries", "감자튀김", 3100, "소금 적게, 케첩 추가"));
        if ("sandwichpark-central".equals(storeId)) return Arrays.asList(
                new MenuItem("sandwichpark-ham", "햄치즈 샌드위치", 6500, "토마토 제외, 소스 적게"),
                new MenuItem("sandwichpark-egg", "에그 샌드위치", 6100, "피클 제외, 치즈 추가"),
                new MenuItem("sandwichpark-chicken", "치킨 샐러드", 8200, "드레싱 별도, 토마토 제외"));
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
