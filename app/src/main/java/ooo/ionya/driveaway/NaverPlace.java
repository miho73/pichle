package ooo.ionya.driveaway;

/** A search result, without invented menu, drive-through, ordering or wait-time data. */
public final class NaverPlace {
    public final String id, name, category, address, telephone;
    public final double latitude, longitude;

    public NaverPlace(String id, String name, String category, String address, String telephone,
                      double latitude, double longitude) {
        this.id = id; this.name = name; this.category = category; this.address = address;
        this.telephone = telephone; this.latitude = latitude; this.longitude = longitude;
    }

    public String mapUrl() { return "https://map.naver.com/p/entry/place/" + id; }

    public boolean matchesCategory(String filter) {
        if ("커피".equals(filter)) return category.contains("카페") || category.contains("커피");
        if ("버거".equals(filter)) return category.contains("버거") || category.contains("햄버거");
        return category.contains(filter);
    }
}
