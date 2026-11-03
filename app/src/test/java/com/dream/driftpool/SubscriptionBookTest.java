package com.dream.driftpool;

import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SubscriptionBookTest {
    private static final String ONE = "vless://12345678-1234-4234-8234-123456789abc@one.example:443?security=reality&pbk=test-key&type=tcp#One";
    private static final String TWO = ONE.replace("one.example", "two.example").replace("#One", "#Two");
    private static final String THREE = ONE.replace("one.example", "three.example");

    @Test public void readsPreviousSingleSubscriptionAndRoundTripsNewFormat() throws Exception {
        JSONObject old = new JSONObject().put("url", "https://a.example/private").put("body", ONE);
        SubscriptionBook book = SubscriptionBook.fromJson(old);
        assertEquals(1, book.sources().size()); assertEquals(1, book.nodes().size());
        SubscriptionBook next = book.upsert("https://b.example/private", TWO, 123);
        SubscriptionBook restored = SubscriptionBook.fromJson(next.toJson());
        assertEquals(2, restored.sources().size()); assertEquals(2, restored.nodes().size());
        assertEquals("https://a.example/private", restored.sources().get(0).url());
        assertEquals(ONE, restored.sources().get(0).body());
    }

    @Test public void combinesServersAndDeduplicatesAcrossLabelsAndQueryOrder() throws Exception {
        String reordered = ONE.replace("security=reality&pbk=test-key&type=tcp#One", "type=tcp&pbk=test-key&security=reality#Another");
        SubscriptionBook book = new SubscriptionBook(List.of()).upsert("https://a.example/s", ONE + "\n" + TWO, 1)
                .upsert("https://b.example/s", reordered + "\n" + THREE, 1);
        assertEquals(2, book.sources().size()); assertEquals(3, book.nodes().size());
    }

    @Test public void updatingExistingUrlKeepsIdAndDoesNotAddAnotherSource() throws Exception {
        SubscriptionBook book = new SubscriptionBook(List.of()).upsert("https://a.example/s", ONE, 1);
        String id = book.sources().get(0).id();
        SubscriptionBook next = book.upsert("https://a.example/s", TWO, 2);
        assertEquals(1, next.sources().size()); assertEquals(id, next.sources().get(0).id());
        assertEquals("Two", next.nodes().get(0).name());
    }

    @Test public void failedRefreshRetainsOldSourceAndImportsOtherSuccessfulFeeds() throws Exception {
        SubscriptionBook book = new SubscriptionBook(List.of()).upsert("https://a.example/s", ONE, 1);
        SubscriptionRefresh.Result result = SubscriptionRefresh.run(book,
                List.of("https://a.example/s", "https://b.example/s", "https://bad.example/s"),
                url -> { if (url.contains("a.example")) throw new java.io.IOException(); return url.contains("bad") ? "<html>error</html>" : TWO; });
        assertEquals(1, result.succeeded()); assertEquals(2, result.failed());
        assertEquals(2, result.book().sources().size()); assertEquals(2, result.book().nodes().size());
        assertEquals(ONE, result.book().sources().get(0).body());
    }

    @Test public void removalRetainsSharedServersInOtherSourcesAndSupportsEmptyPool() throws Exception {
        SubscriptionBook book = new SubscriptionBook(List.of()).upsert("https://a.example/s", ONE, 1)
                .upsert("https://b.example/s", ONE + "\n" + TWO, 1);
        book = book.remove(book.sources().get(0).id()); assertEquals(2, book.nodes().size());
        book = book.remove(book.sources().get(0).id());
        assertTrue(SubscriptionBook.fromJson(book.toJson()).nodes().isEmpty());
    }

    @Test public void rejectsInvalidOrExcessSourcesWithoutChangingOriginal() throws Exception {
        SubscriptionBook book = new SubscriptionBook(List.of());
        for (int i = 0; i < SubscriptionBook.MAX_SOURCES; i++) book = book.upsert("https://a.example/" + i, ONE, 1);
        SubscriptionBook full = book;
        assertThrows(Exception.class, () -> full.upsert("https://a.example/extra", ONE, 1));
        assertThrows(Exception.class, () -> full.upsert("http://a.example/s", ONE, 1));
        assertThrows(Exception.class, () -> full.upsert("https://a.example/0", "invalid", 1));
        assertEquals(SubscriptionBook.MAX_SOURCES, full.sources().size());
    }
}
