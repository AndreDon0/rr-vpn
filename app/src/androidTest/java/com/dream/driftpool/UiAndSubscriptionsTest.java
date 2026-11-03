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
    private android.os.LocaleList savedNativeLanguages;
    private boolean hadMigration, savedMigration;
    private boolean hadGuide, savedGuide;
    @org.junit.Before public void prepareAppearance() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        savedLanguage = context.getSharedPreferences("appearance", 0).getString("language", null);
        hadMigration = context.getSharedPreferences("appearance",0).contains("native_language_migrated");
        savedMigration = context.getSharedPreferences("appearance",0).getBoolean("native_language_migrated",false);
        if (android.os.Build.VERSION.SDK_INT>=33) savedNativeLanguages = context.getSystemService(android.app.LocaleManager.class).getApplicationLocales();
        android.content.SharedPreferences guide = context.getSharedPreferences("MainActivity", 0);
        hadGuide = guide.contains("tutorial_seen_v1"); savedGuide = guide.getBoolean("tutorial_seen_v1", false);
        assertTrue(AppLanguage.save(context, "en")); guide.edit().putBoolean("tutorial_seen_v1", true).commit();
    }
    @org.junit.After public void restoreAppearance() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences.Editor language = context.getSharedPreferences("appearance", 0).edit();
        if (savedLanguage == null) language.remove("language"); else language.putString("language", savedLanguage);
        if (hadMigration) language.putBoolean("native_language_migrated",savedMigration); else language.remove("native_language_migrated");
        language.commit();
        if (android.os.Build.VERSION.SDK_INT>=33) context.getSystemService(android.app.LocaleManager.class).setApplicationLocales(savedNativeLanguages);
        android.content.SharedPreferences.Editor guide = context.getSharedPreferences("MainActivity", 0).edit();
        if (hadGuide) guide.putBoolean("tutorial_seen_v1", savedGuide); else guide.remove("tutorial_seen_v1");
        guide.commit();
    }

    @Test public void firstLanguageFollowsDeviceAndExplicitChoiceWins() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("ru", AppLanguage.preferred(android.os.LocaleList.forLanguageTags("zh-CN,ru-RU,en-US")));
        assertEquals("en", AppLanguage.preferred(android.os.LocaleList.forLanguageTags("zh-CN,en-US,ru-RU")));
        assertEquals("en", AppLanguage.preferred(android.os.LocaleList.forLanguageTags("zh-CN,de-DE")));
        assertEquals("ru", AppLanguage.preferred(android.os.LocaleList.forLanguageTags("ru-RU")));
        assertTrue(AppLanguage.save(context,"system")); assertEquals("system",AppLanguage.choice(context));
        assertTrue(AppLanguage.save(context,"ru")); assertEquals("ru",AppLanguage.selected(context));
        assertEquals("Настройки",AppLanguage.wrap(context).getString(R.string.settings));
        assertTrue(AppLanguage.save(context,"en")); assertEquals("en",AppLanguage.selected(context));
    }

    @Test public void legacyLanguageMigratesAndAndroidSettingsCanResetIt() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=33);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.app.LocaleManager manager = context.getSystemService(android.app.LocaleManager.class);
        manager.setApplicationLocales(android.os.LocaleList.getEmptyLocaleList());
        context.getSharedPreferences("appearance",0).edit().putString("language","ru").remove("native_language_migrated").commit();
        AppLanguage.migrate(context);
        assertEquals("ru",manager.getApplicationLocales().toLanguageTags()); assertEquals("ru",AppLanguage.choice(context));
        manager.setApplicationLocales(android.os.LocaleList.getEmptyLocaleList());
        assertEquals("system",AppLanguage.choice(context)); // Android's Follow system must beat the old saved override.
        assertTrue(AppLanguage.save(context,"en")); assertEquals("en",manager.getApplicationLocales().toLanguageTags());
    }

    @Test public void tutorialAppearsOnceAndCanBeReplayed() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("MainActivity", 0).edit().remove("tutorial_seen_v1").commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.tutorial_title)).check(matches(withText(R.string.guide_add_title)));
            assertTutorialProgress(scenario,1); screenshot(context, "rr-vpn-guide.png");
            onView(withId(android.R.id.button1)).perform(click());
            onView(withId(R.id.tutorial_title)).check(matches(withText(R.string.guide_connect_title)));
            assertTutorialProgress(scenario,2);
            onView(withId(R.id.tutorial_icon)).check(matches(withTagValue(org.hamcrest.Matchers.is(R.drawable.ic_power))));
            screenshot(context,"rr-vpn-guide-page2.png");
            onView(withId(android.R.id.button3)).perform(click());
            onView(withId(R.id.tutorial_title)).check(matches(withText(R.string.guide_add_title)));
            onView(withId(android.R.id.button1)).perform(click());
            onView(withId(android.R.id.button1)).perform(click());
            onView(withId(R.id.tutorial_title)).check(matches(withText(R.string.guide_pool_title)));
            assertTutorialProgress(scenario,3); screenshot(context,"rr-vpn-guide-page3.png");
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
                awaitLanguage(scenario,"ru");
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
                awaitLanguage(scenario,"en");
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
        store.saveBook(new SubscriptionBook(List.of()).upsert("https://first.example/a", local, 1)); RoutingLists.setEnabled(context,false);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitReady(scenario); int off=powerColor(scenario); screenshot(context,"rr-vpn-disconnected.png");
            context.startForegroundService(new Intent(context,PoolVpnService.class)); waitPhase(2); Thread.sleep(1700);
            assertNotEquals(off,powerColor(scenario)); screenshot(context,"rr-vpn-connected.png");
            long generation=PoolVpnService.routingGeneration;
            onView(withId(R.id.settings_button)).perform(click());
            onView(withId(R.id.language_button)).perform(scrollTo(),click()); onView(withText("Русский")).perform(click());
            awaitLanguage(scenario,"ru"); onView(withId(R.id.close_settings)).perform(click());
            onView(withText("Подключено")).check(matches(isDisplayed()));
            long deadline=System.currentTimeMillis()+7000; boolean translated=false;
            while (System.currentTimeMillis()<deadline && !translated) {
                for (android.service.notification.StatusBarNotification entry:context.getSystemService(android.app.NotificationManager.class).getActiveNotifications()) {
                    android.app.Notification notification=entry.getNotification();
                    if (entry.getId()==1 && notification.actions!=null && notification.actions.length>0)
                        translated="Отключить".contentEquals(notification.actions[0].title);
                }
                if (!translated)Thread.sleep(100);
            }
            assertTrue(translated); assertEquals(generation,PoolVpnService.routingGeneration);
            context.startService(new Intent(context,PoolVpnService.class).setAction(PoolVpnService.STOP)); waitPhase(0); Thread.sleep(1700);
            assertEquals(off,powerColor(scenario));
        } finally {
            if (PoolVpnService.snapshot.busy()) {context.startService(new Intent(context,PoolVpnService.class).setAction(PoolVpnService.STOP));waitPhase(0);}
            store.saveBook(preceding);RoutingLists.setEnabled(context,routing);
        }
    }
    private int powerColor(ActivityScenario<MainActivity> scenario) {
        java.util.concurrent.atomic.AtomicInteger color=new java.util.concurrent.atomic.AtomicInteger();
        scenario.onActivity(activity -> {
            try {java.lang.reflect.Field field=MainActivity.class.getDeclaredField("powerDisk");field.setAccessible(true);
                color.set(((android.graphics.drawable.GradientDrawable)field.get(activity)).getColor().getDefaultColor());
            } catch(Exception e){throw new AssertionError(e);}
        });return color.get();
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

    private void assertTutorialProgress(ActivityScenario<MainActivity> scenario,int expected) {
        scenario.onActivity(activity -> {
            try {
                java.lang.reflect.Field field = MainActivity.class.getDeclaredField("tutorial"); field.setAccessible(true);
                android.app.AlertDialog guide = (android.app.AlertDialog)field.get(activity);
                android.widget.ProgressBar progress = guide.findViewById(R.id.tutorial_progress);
                assertEquals(3,progress.getMax()); assertEquals(expected,progress.getProgress());
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }
    private void awaitLanguage(ActivityScenario<MainActivity> scenario,String expected) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline=System.currentTimeMillis()+10000;
        while (System.currentTimeMillis()<deadline) {
            scenario.onActivity(activity -> ready.set(expected.equals(activity.getResources().getConfiguration().getLocales().get(0).getLanguage())
                    && activity.findViewById(R.id.connect_button).isEnabled()));
            if (ready.get()) return; Thread.sleep(50);
        }
        fail("Language did not update: "+expected);
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
