package com.dream.driftpool;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.allOf;
import java.util.List;

public class UiAndSubscriptionsTest {
    private static final String ONE = "vless://12345678-1234-4234-8234-123456789abc@one.example:443?security=reality&pbk=test-key&type=tcp#Germany%20%231";
    private static final String TWO = ONE.replace("one.example", "two.example").replace("Germany%20%231", "US%20%232");

    private String savedLanguage;
    private boolean hadGuide, savedGuide;
    @org.junit.Before public void prepareAppearance() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        savedLanguage = context.getSharedPreferences("appearance", 0).getString("language", null);
        android.content.SharedPreferences guide = context.getSharedPreferences("MainActivity", 0);
        hadGuide = guide.contains("tutorial_seen_v1"); savedGuide = guide.getBoolean("tutorial_seen_v1", false);
        assertTrue(AppLanguage.save(context, "en")); guide.edit().putBoolean("tutorial_seen_v1", true).commit();
    }
    @org.junit.After public void restoreAppearance() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences.Editor language = context.getSharedPreferences("appearance", 0).edit();
        if (savedLanguage == null) language.remove("language"); else language.putString("language", savedLanguage);
        language.commit();
        android.content.SharedPreferences.Editor guide = context.getSharedPreferences("MainActivity", 0).edit();
        if (hadGuide) guide.putBoolean("tutorial_seen_v1", savedGuide); else guide.remove("tutorial_seen_v1");
        guide.commit();
    }

    @Test public void firstLanguageFollowsDeviceAndExplicitChoiceWins() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("appearance", 0).edit().remove("language").commit();
        android.content.res.Configuration configuration = new android.content.res.Configuration(context.getResources().getConfiguration());
        configuration.setLocales(new android.os.LocaleList(java.util.Locale.forLanguageTag("ru")));
        Context russian = context.createConfigurationContext(configuration);
        assertEquals("ru", AppLanguage.selected(russian));
        assertEquals("Настройки", AppLanguage.wrap(russian).getString(R.string.settings));
        configuration.setLocales(new android.os.LocaleList(java.util.Locale.forLanguageTag("de")));
        Context other = context.createConfigurationContext(configuration);
        assertEquals("en", AppLanguage.selected(other));
        assertTrue(AppLanguage.save(context, "en"));
        assertEquals("en", AppLanguage.selected(russian));
        assertEquals("Settings", AppLanguage.wrap(russian).getString(R.string.settings));
    }

    @Test public void tutorialAppearsOnceAndCanBeReplayed() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("MainActivity", 0).edit().remove("tutorial_seen_v1").commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.tutorial_title)).check(matches(withText(R.string.guide_add_title)));
            screenshot(context, "rr-vpn-guide.png");
            onView(withId(android.R.id.button1)).perform(click());
            onView(withId(R.id.tutorial_title)).check(matches(withText(R.string.guide_connect_title)));
            onView(withId(android.R.id.button3)).perform(click());
            onView(withId(R.id.tutorial_title)).check(matches(withText(R.string.guide_add_title)));
            onView(withId(android.R.id.button1)).perform(click());
            onView(withId(android.R.id.button1)).perform(click());
            onView(withId(R.id.tutorial_title)).check(matches(withText(R.string.guide_pool_title)));
            onView(withId(android.R.id.button1)).perform(click());
            assertTrue(context.getSharedPreferences("MainActivity", 0).getBoolean("tutorial_seen_v1", false));
            scenario.recreate();
            onView(withId(R.id.tutorial_title)).check(doesNotExist());
            onView(withId(R.id.settings_button)).perform(click());
            onView(withId(R.id.tutorial_button)).perform(scrollTo(), click());
            onView(withId(R.id.tutorial_title)).check(matches(withText(R.string.guide_add_title)));
            onView(withId(android.R.id.button2)).perform(click());
            onView(withId(R.id.tutorial_title)).check(doesNotExist());
        }
    }

    @Test public void languagePersistsAndRoutingInfoExplainsPriority() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SecretStore store = new SecretStore(context); SubscriptionBook preceding = store.loadBook();
        SubscriptionBook fixture = new SubscriptionBook(List.of()).upsert("https://first.example/a", ONE + "\n" + TWO, 1);
        store.saveBook(fixture);
        long generation = PoolVpnService.routingGeneration;
        try {
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                awaitReady(scenario);
                onView(withId(R.id.settings_button)).perform(click());
                onView(withId(R.id.language_button)).perform(scrollTo(), click());
                onView(withText("Русский")).perform(click());
                awaitReady(scenario);
                onView(withId(R.id.language_button)).check(matches(withText("Язык  ·  Русский  ›")));
                assertEquals("ru", AppLanguage.selected(context));
                onView(withId(R.id.smart_routing_info)).perform(scrollTo(), click());
                onView(withText(AppLanguage.wrap(context).getString(R.string.smart_description) + "\n\n"
                        + AppLanguage.wrap(context).getString(R.string.smart_refresh_description))).check(matches(isDisplayed()));
                screenshot(context, "rr-vpn-routing-ru.png");
                onView(withId(android.R.id.button1)).perform(click());
                screenshot(context, "rr-vpn-settings-ru.png");
                onView(withId(R.id.close_settings)).perform(click());
                onView(withText("Не подключено")).check(matches(isDisplayed()));
                screenshot(context, "rr-vpn-home-ru.png");
                scenario.recreate();
                onView(withText("Не подключено")).check(matches(isDisplayed()));
            }
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                awaitReady(scenario);
                onView(withId(R.id.settings_button)).perform(click());
                onView(withId(R.id.language_button)).perform(scrollTo(), click());
                onView(withText("English")).perform(click());
                awaitReady(scenario);
                onView(withId(R.id.language_button)).check(matches(withText("Language  ·  English  ›")));
                onView(withId(R.id.close_settings)).perform(click());
                onView(withText(R.string.status_idle)).check(matches(isDisplayed()));
                onView(withText("Germany #1")).check(matches(isDisplayed()));
            }
            assertEquals(fixture.toJson().toString(), store.loadBook().toJson().toString());
            assertEquals(generation, PoolVpnService.routingGeneration);
        } finally { store.saveBook(preceding); }
    }

    @Test public void smartRoutingDefaultsOnAndRespectsSavedOff() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences preferences = context.getSharedPreferences("routing", Context.MODE_PRIVATE);
        boolean had = preferences.contains("enabled"), preceding = preferences.getBoolean("enabled", true);
        java.lang.reflect.Field cache = RoutingLists.class.getDeclaredField("enabled"); cache.setAccessible(true);
        try {
            assertTrue(preferences.edit().remove("enabled").commit()); cache.set(null, null);
            assertTrue(RoutingLists.enabled(context));
            RoutingLists.setEnabled(context, false); cache.set(null, null);
            assertFalse(RoutingLists.enabled(context));
        } finally {
            if (had) RoutingLists.setEnabled(context, preceding);
            else { preferences.edit().remove("enabled").commit(); cache.set(null, null); }
        }
    }

    @Test public void migratedSubscriptionsAppearInNewUiAndCanBeRemoved() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SecretStore store = new SecretStore(context);
        org.json.JSONObject preceding = store.load();
        boolean precedingRouting = RoutingLists.enabled(context);
        RoutingLists.setEnabled(context, true);
        store.save("https://first.example/a", ONE); // The actual preceding version's encrypted format.
        SubscriptionBook migrated = store.loadBook();
        assertEquals(1, migrated.sources().size());
        store.saveBook(migrated.upsert("https://second.example/b", TWO, 1));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitSources(2);
            Thread.sleep(600);
            assertEquals("RR VPN", context.getString(R.string.app_name));
            onView(withText("Germany #1")).check(matches(isDisplayed()));
            onView(withText("US #2")).check(matches(isDisplayed()));
            onView(withText(R.string.download_label)).check(matches(isDisplayed()));
            onView(withText(R.string.upload_label)).check(matches(isDisplayed()));
            screenshot(context, "rr-vpn-home.png");
            onView(withId(R.id.settings_button)).perform(click());
            onView(withText("first.example · 1")).check(matches(isDisplayed()));
            onView(withText("second.example · 1")).check(matches(isDisplayed()));
            onView(withId(R.id.add_sources_button)).check(matches(withText(R.string.add)));
            onView(withText(R.string.refresh_label)).check(matches(isDisplayed()));
            screenshot(context, "rr-vpn-settings.png");
            onView(withId(R.id.add_sources_button)).perform(scrollTo(), click());
            onView(withId(R.id.subscriptions_input)).perform(replaceText("http://invalid.example/a\ninvalid"), closeSoftKeyboard());
            onView(withId(android.R.id.button1)).perform(click());
            Thread.sleep(500);
            assertEquals(2, store.loadBook().sources().size());
            onView(allOf(withContentDescription(R.string.remove), hasSibling(withText("first.example · 1")))).perform(scrollTo(), click());
            onView(withId(android.R.id.button1)).perform(click());
            awaitSources(1);
            Thread.sleep(300);
            onView(withText("first.example · 1")).check(doesNotExist());
            onView(withId(R.id.close_settings)).perform(click());
            onView(withText("US #2")).check(matches(isDisplayed()));
            onView(withText("Germany #1")).check(doesNotExist());
        } finally { store.saveBook(SubscriptionBook.fromJson(preceding)); RoutingLists.setEnabled(context, precedingRouting); }
    }

    @Test public void languageSwitchKeepsVpnRunningAndTranslatesNotification() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SecretStore store = new SecretStore(context); SubscriptionBook preceding = store.loadBook();
        boolean routing = RoutingLists.enabled(context);
        String local = ONE.replace("security=reality&pbk=test-key", "security=tls&sni=one.example");
        store.saveBook(new SubscriptionBook(List.of()).upsert("https://first.example/a", local, 1));
        RoutingLists.setEnabled(context, false);
        context.startForegroundService(new Intent(context, PoolVpnService.class));
        try {
            waitPhase(2); long generation = PoolVpnService.routingGeneration;
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                awaitReady(scenario);
                onView(withId(R.id.settings_button)).perform(click());
                onView(withId(R.id.language_button)).perform(scrollTo(), click());
                onView(withText("Русский")).perform(click());
                awaitReady(scenario);
                onView(withId(R.id.close_settings)).perform(click());
                onView(withText("Подключено")).check(matches(isDisplayed()));
                long deadline = System.currentTimeMillis() + 7000;
                boolean translated = false;
                while (System.currentTimeMillis() < deadline && !translated) {
                    for (android.service.notification.StatusBarNotification entry : context
                            .getSystemService(android.app.NotificationManager.class).getActiveNotifications()) {
                        android.app.Notification notification = entry.getNotification();
                        if (entry.getId() == 1 && notification.actions != null && notification.actions.length > 0)
                            translated = "Отключить".contentEquals(notification.actions[0].title);
                    }
                    if (!translated) Thread.sleep(100);
                }
                assertTrue("Running notification did not translate", translated);
                assertEquals(2, PoolVpnService.snapshot.phase());
                assertEquals("Locale change must not reload the VPN", generation, PoolVpnService.routingGeneration);
            }
        } finally {
            context.startService(new Intent(context, PoolVpnService.class).setAction(PoolVpnService.STOP));
            waitPhase(0); store.saveBook(preceding); RoutingLists.setEnabled(context, routing);
        }
    }

    @Test public void multipleRealSubscriptionsStartOneMergedVpnPool() throws Exception {
        String url = InstrumentationRegistry.getArguments().getString("subscription");
        org.junit.Assume.assumeNotNull(url);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SubscriptionBook existing = new SecretStore(context).loadBook();
        String body = null;
        for (SubscriptionBook.Source source : existing.sources()) if (source.url().equals(url)) body = source.body();
        if (body == null) body = SubscriptionFetcher.fetch(url);
        body = body.trim();
        if (!body.startsWith("vless://")) body = new String(java.util.Base64.getDecoder().decode(body.replaceAll("\\s", "")), java.nio.charset.StandardCharsets.UTF_8);
        List<String> lines = new java.util.ArrayList<>();
        for (String line : body.split("\\R")) if (!line.isBlank()) lines.add(line.trim());
        int middle = lines.size() / 2;
        String first = String.join("\n", lines.subList(0, middle));
        String second = String.join("\n", lines.subList(middle, lines.size())) + "\n" + lines.get(0);
        SubscriptionBook book = new SubscriptionBook(List.of()).upsert(url, first, 1)
                .upsert("https://second.example/integration", second, 1);
        new SecretStore(context).saveBook(book);
        assertEquals(2, new SecretStore(context).loadBook().sources().size());
        assertEquals(Subscription.parse(body).size(), book.nodes().size());
        context.startForegroundService(new Intent(context, PoolVpnService.class));
        try {
            waitPhase(2);
            assertEquals(book.nodes().size(), PoolVpnService.snapshot.nodes().size());
        } finally {
            context.startService(new Intent(context, PoolVpnService.class).setAction(PoolVpnService.STOP));
            waitPhase(0);
        }
    }

    private void awaitReady(ActivityScenario<MainActivity> scenario) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity(activity -> ready.set(activity.findViewById(R.id.connect_button).isEnabled()));
            if (ready.get()) return;
            Thread.sleep(50);
        }
        fail("Activity did not finish loading");
    }

    private void awaitSources(int expected) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            if (new SecretStore(context).loadBook().sources().size() == expected) return;
            Thread.sleep(50);
        }
        fail("Subscription count did not update");
    }
    private void waitPhase(int expected) throws Exception {
        long end = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < end) {
            if (PoolVpnService.snapshot.phase() == expected) return;
            if (PoolVpnService.snapshot.phase() == 4) fail("Native VPN failed");
            Thread.sleep(100);
        }
        fail("VPN lifecycle timed out");
    }
    private void screenshot(Context context, String name) throws Exception {
        android.graphics.Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (java.io.FileOutputStream out = context.openFileOutput(name, Context.MODE_PRIVATE)) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
        } finally { image.recycle(); }
    }
}
