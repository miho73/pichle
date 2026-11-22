package ooo.ionya.driveaway;

/**
 * Offline fallback for turning a coordinate into a search-friendly area name when the device has no
 * reverse geocoder. Naver search ignores coordinates, so queries need a place name; resolving it
 * locally keeps the exact position on the device. Entries are approximate district centers.
 */
final class KoreaAreas {
    private KoreaAreas() {}

    private static final Object[][] AREAS = {
        // 서울
        {"강남", 37.4979, 127.0276}, {"종로", 37.5704, 126.9830}, {"홍대", 37.5563, 126.9236},
        {"잠실", 37.5133, 127.1001}, {"영등포", 37.5264, 126.8962}, {"마곡", 37.5600, 126.8250},
        {"노원", 37.6554, 127.0614}, {"가산", 37.4812, 126.8827}, {"성수", 37.5446, 127.0557},
        {"신림", 37.4846, 126.9296},
        // 인천
        {"송도동", 37.3887, 126.6433}, {"부평", 37.5070, 126.7218}, {"구월동", 37.4486, 126.7053},
        {"계양", 37.5372, 126.7377}, {"청라", 37.5340, 126.6420}, {"영종", 37.4930, 126.5370},
        // 경기
        {"수원", 37.2636, 127.0286}, {"분당", 37.3827, 127.1189}, {"수지", 37.3220, 127.0970},
        {"안양", 37.3943, 126.9568}, {"부천", 37.5034, 126.7660}, {"일산", 37.6584, 126.7700},
        {"안산", 37.3219, 126.8309}, {"동탄", 37.2000, 127.0950}, {"평택", 36.9921, 127.1129},
        {"의정부", 37.7381, 127.0337}, {"김포", 37.6153, 126.7156}, {"시흥", 37.3800, 126.8030},
        {"운정", 37.7160, 126.7430}, {"하남", 37.5393, 127.2148}, {"광명", 37.4786, 126.8644},
        {"다산", 37.6360, 127.2165},
        // 광역시·지방
        {"해운대", 35.1631, 129.1635}, {"서면", 35.1579, 129.0600}, {"동성로", 35.8690, 128.5950},
        {"둔산", 36.3510, 127.3850}, {"상무지구", 35.1529, 126.8514}, {"삼산동", 35.5384, 129.3390},
        {"세종", 36.4800, 127.2890}, {"제주시", 33.4996, 126.5312}, {"서귀포", 33.2540, 126.5600},
        {"청주", 36.6424, 127.4890}, {"천안", 36.8151, 127.1139}, {"전주", 35.8242, 127.1480},
        {"창원", 35.2280, 128.6811}, {"포항", 36.0190, 129.3435}, {"춘천", 37.8813, 127.7300},
        {"강릉", 37.7519, 128.8761},
    };

    private static final double MAX_METERS = 25_000;

    /** Nearest known area within 25 km, or null (e.g. the device is outside Korea). */
    static String nearest(double lat, double lon) {
        String best = null; double bestMeters = MAX_METERS;
        for (Object[] area : AREAS) {
            double d = NearbyFinder.distance(lat, lon, (Double) area[1], (Double) area[2]);
            if (d < bestMeters) { bestMeters = d; best = (String) area[0]; }
        }
        return best;
    }
}
