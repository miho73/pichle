package ooo.ionya.driveaway;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class NaverPlaceCrawlerTest {
    private String capturedPage() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/naver-search.html")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private JSONObject place(String id) {
        try { return new JSONObject().put("id", id).put("name", "테스트 가게").put("category", "카페")
                .put("latitude", 37.3845481).put("longitude", 126.6411987).put("address", "지번 주소");
        } catch (org.json.JSONException error) { throw new AssertionError(error); }
    }

    private String page(JSONArray items) {
        try { JSONObject data = new JSONObject().put("totalCount", items.length()).put("items", items);
        JSONObject entry = new JSONObject().put("queryKey", new JSONArray().put("v1").put("allSearchSuspense"))
                .put("state", new JSONObject().put("status", "success").put("data", data));
        return "<script>window.__RQ_STREAMING_STATE__.push("
                + new JSONObject().put("queries", new JSONArray().put(entry)) + ");</script>";
        } catch (org.json.JSONException error) { throw new AssertionError(error); }
    }

    @Test public void readsCapturedStreamingStateAndSkipsLocationState() throws Exception {
        NaverPlaceCrawler.Result result = NaverPlaceCrawler.parse("송도 스타벅스", capturedPage());
        assertEquals(28, result.totalCount);
        assertEquals(2, result.places.size());
        NaverPlace first = result.places.get(0);
        assertEquals("1617698245", first.id);
        assertEquals("스타벅스 송도캐슬파크점", first.name);
        assertEquals("인천광역시 연수구 인천타워대로132번길 9 (송도동)", first.address);
        assertEquals("1522-3232", first.telephone);
        assertEquals(37.3845481, first.latitude, 0.00000001);
        assertEquals(126.6411987, first.longitude, 0.00000001);
        assertTrue(first.mapUrl().endsWith("/1617698245"));
        assertTrue(first.matchesCategory("커피"));
        assertFalse(first.matchesCategory("버거"));
    }

    @Test public void emptyResultsAreDifferentFromBlockedOrChangedPages() throws Exception {
        assertTrue(NaverPlaceCrawler.parse("없는가게", page(new JSONArray())).places.isEmpty());
        assertThrows(IOException.class, () -> NaverPlaceCrawler.parse("가게", "<html>captcha</html>"));
        assertThrows(IOException.class, () -> NaverPlaceCrawler.parse("가게", "<script>window.__RQ_STREAMING_STATE__.push({broken);</script>"));
        assertThrows(IOException.class, () -> NaverPlaceCrawler.parse("가게", "<script>window.__RQ_STREAMING_STATE__.push({\"queries\":[]});</script>"));
    }

    @Test public void deduplicatesAndRejectsUnusableCoordinatesAndIds() throws Exception {
        JSONObject missingLongitude = place("3"); missingLongitude.remove("longitude");
        JSONArray items = new JSONArray().put(place("1")).put(place("1"))
                .put(place("2").put("latitude", 91)).put(missingLongitude)
                .put(place("../bad")).put(place("4").put("name", ""))
                .put(place("5").put("latitude", 0).put("longitude", 0)).put(JSONObject.NULL);
        NaverPlaceCrawler.Result result = NaverPlaceCrawler.parse("가게", page(items));
        assertEquals(1, result.places.size());
        assertEquals("지번 주소", result.places.get(0).address);
        assertEquals("", result.places.get(0).telephone);
    }

    @Test public void whollyInvalidResultsReportAnError() {
        assertThrows(IOException.class, () -> NaverPlaceCrawler.parse("가게", page(new JSONArray().put(place("bad")))));
    }

    @Test public void nullOptionalFieldsAreNotDisplayedAsLiteralNull() throws Exception {
        JSONObject item = place("1").put("roadAddress", JSONObject.NULL).put("tel", JSONObject.NULL)
                .put("category", JSONObject.NULL);
        NaverPlace result = NaverPlaceCrawler.parse("가게", page(new JSONArray().put(item))).places.get(0);
        assertEquals("지번 주소", result.address);
        assertEquals("", result.telephone);
        assertEquals("", result.category);
        assertThrows(IOException.class, () -> NaverPlaceCrawler.parse("가게", page(new JSONArray()
                .put(place("1").put("name", JSONObject.NULL)))));
    }

    @Test public void limitsFirstPageTo75Places() throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 1; i <= 100; i++) items.put(place(String.valueOf(i)));
        assertEquals(75, NaverPlaceCrawler.parse("가게", page(items)).places.size());
    }

    @Test public void normalizesQueriesAndCachesSuccessfulResults() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NaverPlaceCrawler crawler = new NaverPlaceCrawler(query -> {
            calls.incrementAndGet(); assertEquals("송도 카페", query); return page(new JSONArray().put(place("1")));
        });
        assertSame(crawler.search("  송도   카페  "), crawler.search("송도 카페"));
        assertEquals(1, calls.get());
        assertThrows(UnsupportedOperationException.class, () -> crawler.search("송도 카페").places.clear());
        crawler.close();
        assertThrows(IOException.class, () -> crawler.search("송도 카페"));
    }

    @Test public void invalidQueriesDoNotReachTheNetwork() {
        NaverPlaceCrawler crawler = new NaverPlaceCrawler(query -> { fail("Unexpected request"); return ""; });
        assertThrows(IOException.class, () -> crawler.search("   "));
        assertThrows(IOException.class, () -> crawler.search(null));
        assertThrows(IOException.class, () -> crawler.search("a".repeat(101)));
    }

    @Test public void failedRequestsAreRetriedAndCacheIsBounded() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NaverPlaceCrawler crawler = new NaverPlaceCrawler(query -> {
            if (calls.incrementAndGet() == 1) throw new IOException("접속 제한");
            return page(new JSONArray());
        });
        assertThrows(IOException.class, () -> crawler.search("첫 검색"));
        crawler.search("첫 검색");
        for (int i = 0; i < 8; i++) crawler.search("가게 " + i);
        crawler.search("첫 검색");
        assertEquals(11, calls.get());
    }
}
