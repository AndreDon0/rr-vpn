package com.dream.driftpool;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(245, 247, 251);
    private static final int SURFACE = Color.WHITE;
    private static final int INK = Color.rgb(23, 35, 56);
    private static final int MUTED = Color.rgb(100, 113, 135);
    private static final int ACCENT = Color.rgb(46, 95, 221);
    private static final int PALE = Color.rgb(232, 239, 255);
    private static final int LINE = Color.rgb(229, 234, 242);
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService updateIo = Executors.newSingleThreadExecutor();
    private final ExecutorService usageIo = Executors.newSingleThreadExecutor();
    static final long USAGE_INTERVAL = 15 * 60 * 1000L;
    private boolean usageRefreshing;
    private long nextUsageCheck, usageAttempt;
    private SubscriptionRefresh.UsageFetcher usageFetcher = SubscriptionFetcher::fetchUsage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SubscriptionBook book = new SubscriptionBook(List.of());
    private List<Subscription.Node> nodes = List.of();
    private boolean loading = true, importing, resumed, routingReady, routingBusy, reopenSettings;
    private FrameLayout power;
    private ImageView powerImage;
    private GradientDrawable powerDisk, powerHalo;
    private TextView connectionState, connectionAction, poolCount, totalDown, totalUp, importStatus;
    private LinearLayout rows, sourceRows;
    private LinearLayout usagePanel, usageRows;
    private String usageSignature = "";
    private View emptyPool;
    private Switch smartSwitch;
    private Button addSources, refreshSources, languageButton;
    private Dialog settings;
    private AlertDialog tutorial;
    private Dialog updateDialog;
    private Button installButton;
    private UpdateButton updateButton;
    private UpdateSession updates;
    private boolean showWhenChecked;
    private final UpdateSession.Observer updateObserver = this::updateChanged;
    private UpdateRelease updateRelease;
    private boolean updateReady, updateBusy, reopenUpdates;
    private int tutorialStep = -1;
    private String rowSignature = "", importMessage = "", settingsSourceKey = "";
    private java.util.Map<String, Integer> sourceCounts = java.util.Map.of();
    private int previousPhase;
    private long nextTrafficSample;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (PoolVpnService.snapshot.phase() == 2 && now >= nextTrafficSample) {
                nextTrafficSample = now + 3000;
                PoolVpnService.requestTrafficSample();
            }
            render(); refreshUsageIfNeeded(); main.postDelayed(this, 1500);
        }
    };

    @Override protected void attachBaseContext(Context base) { AppLanguage.migrate(base); super.attachBaseContext(AppLanguage.wrap(base)); }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        updates = UpdateSession.get(this);
        reopenUpdates = UpdateManager.OPEN.equals(getIntent().getAction());
        getIntent().setAction(Intent.ACTION_MAIN);
        reopenSettings = savedInstanceState != null && savedInstanceState.getBoolean("settings");
        int restoredStep = savedInstanceState == null ? -1 : savedInstanceState.getInt("tutorial", -1);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root = page();
        LinearLayout header = horizontal();
        TextView brand = text(getString(R.string.app_name), 24, INK); bold(brand);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(56), 1));
        updateButton = new UpdateButton(this); updateButton.setId(R.id.update_button);
        updateButton.setVisibility(View.GONE); updateButton.setOnClickListener(v -> openUpdates());
        LinearLayout.LayoutParams updatePosition = new LinearLayout.LayoutParams(dp(48), dp(48)); updatePosition.rightMargin = dp(8);
        header.addView(updateButton, updatePosition);
        FrameLayout gear = iconButton(R.drawable.ic_settings, R.string.settings); gear.setId(R.id.settings_button);
        gear.setOnClickListener(v -> openSettings()); header.addView(gear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);
        ScrollView homeScroll = new ScrollView(this); homeScroll.setFillViewport(true);
        LinearLayout content = column(); homeScroll.addView(content);
        root.addView(homeScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout hero = column(); hero.setGravity(Gravity.CENTER); hero.setPadding(0, dp(16), 0, dp(14));
        power = new FrameLayout(this); power.setId(R.id.connect_button); power.setClickable(true); power.setFocusable(true);
        powerHalo = round(0xffe8edf4, 100); powerDisk = round(0xff68768d, 100);
        power.setBackground(new RippleDrawable(ColorStateList.valueOf(0x203660DD), powerHalo, null));
        FrameLayout inner = new FrameLayout(this); inner.setBackground(powerDisk);
        power.addView(inner, centered(136, 136));
        powerImage = image(R.drawable.ic_power); powerImage.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        inner.addView(powerImage, centered(48, 48));
        power.setOnClickListener(v -> toggleVpn()); hero.addView(power, new LinearLayout.LayoutParams(dp(164), dp(164)));
        connectionState = text("", 22, INK); bold(connectionState); connectionState.setGravity(Gravity.CENTER);
        hero.addView(connectionState);
        connectionAction = text("", 13, MUTED); connectionAction.setGravity(Gravity.CENTER); hero.addView(connectionAction);
        content.addView(hero);

        LinearLayout totals = horizontal();
        LinearLayout downCard = trafficCard(R.string.download_label, "↓"); totalDown = (TextView) downCard.getChildAt(1);
        LinearLayout upCard = trafficCard(R.string.upload_label, "↑"); totalUp = (TextView) upCard.getChildAt(1);
        totals.addView(downCard, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams upParams = new LinearLayout.LayoutParams(0, -2, 1); upParams.leftMargin = dp(12);
        totals.addView(upCard, upParams); content.addView(totals); space(content, 18);

        usagePanel = card(16); usagePanel.setId(R.id.home_subscriptions);
        TextView usageHeading = text(getString(R.string.subscriptions), 15, INK); bold(usageHeading); usagePanel.addView(usageHeading);
        usageRows = column(); usagePanel.addView(usageRows);
        LinearLayout.LayoutParams usagePosition = new LinearLayout.LayoutParams(-1, -2); usagePosition.bottomMargin = dp(18);
        content.addView(usagePanel, usagePosition);

        LinearLayout panel = card(16);
        LinearLayout columns = horizontal();
        TextView servers = text(getString(R.string.pool_title), 15, INK); bold(servers);
        columns.addView(servers, new LinearLayout.LayoutParams(0, -2, 1));
        poolCount = text("", 12, MUTED); columns.addView(poolCount); panel.addView(columns);
        LinearLayout labels = horizontal(); labels.setPadding(0, dp(8), 0, dp(6));
        labels.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        labels.addView(number(getString(R.string.download_label), 10, MUTED), new LinearLayout.LayoutParams(dp(68), -2));
        labels.addView(number(getString(R.string.upload_label), 10, MUTED), new LinearLayout.LayoutParams(dp(68), -2));
        panel.addView(labels);
        rows = column(); rows.setId(R.id.server_rows); panel.addView(rows);
        LinearLayout empty = column(); empty.setGravity(Gravity.CENTER); empty.setPadding(0, dp(18), 0, dp(18));
        TextView noServers = text(getString(R.string.no_servers), 15, MUTED); empty.addView(noServers);
        Button add = button(R.string.add_subscription, true); add.setOnClickListener(v -> { openSettings(); showAddDialog(); });
        empty.addView(add); emptyPool = empty; panel.addView(empty);
        content.addView(panel, new LinearLayout.LayoutParams(-1, -2)); setContentView(root); render();
        if (restoredStep >= 0) showTutorial(restoredStep);
        else if (!reopenSettings && !getPreferences(MODE_PRIVATE).getBoolean("tutorial_seen_v1", false)) showTutorial(0);
        io.execute(() -> {
            try {
                SubscriptionBook loaded = new SecretStore(this).loadBook();
                List<Subscription.Node> pool = loaded.nodes(); java.util.Map<String, Integer> counts = sourceCounts(loaded);
                RoutingLists.load(this); RoutingLists.schedule(this);
                main.post(() -> {
                    if (isDestroyed()) return;
                    book = loaded; nodes = pool; sourceCounts = counts; loading = false; routingReady = true; render();
                    if (reopenSettings) { reopenSettings = false; openSettings(); }
                    if (reopenUpdates) { reopenUpdates = false; openUpdates(); }
                });
            } catch (Exception e) { main.post(() -> { loading = false; toast(R.string.import_failed); render(); }); }
        });

    }

    private void toggleVpn() {
        PoolVpnService.Snapshot state = PoolVpnService.snapshot;
        if (state.busy()) { startService(new Intent(this, PoolVpnService.class).setAction(PoolVpnService.STOP)); return; }
        if (nodes.isEmpty()) { openSettings(); return; }
        Intent permission = VpnService.prepare(this);
        if (permission == null) startVpn(); else startActivityForResult(permission, 50);
    }

    private void openSettings() {
        if (settings != null && settings.isShowing()) return;
        settingsSourceKey = "";
        Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE); settings = dialog;
        LinearLayout root = page();
        LinearLayout header = horizontal();
        Button back = smallButton("‹", R.string.close); back.setId(R.id.close_settings); back.setTextSize(30);
        back.setOnClickListener(v -> dialog.dismiss()); header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(52)));
        TextView title = text(getString(R.string.settings), 23, INK); bold(title); header.addView(title);
        root.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = column(); body.setPadding(0, dp(16), 0, dp(16)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        section(body, R.string.subscriptions);
        LinearLayout subscriptions = card(16); body.addView(subscriptions);
        sourceRows = column(); subscriptions.addView(sourceRows);
        LinearLayout actions = horizontal(); subscriptions.addView(actions);
        addSources = button(R.string.add, true); addSources.setId(R.id.add_sources_button);
        addSources.setContentDescription(getString(R.string.add_subscriptions));
        actions.addView(addSources, new LinearLayout.LayoutParams(0, dp(48), 1)); addSources.setOnClickListener(v -> showAddDialog());
        refreshSources = button(R.string.refresh_label, false); refreshSources.setId(R.id.refresh_sources_button);
        refreshSources.setContentDescription(getString(R.string.refresh_subscriptions));
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(0, dp(48), 1); refreshParams.leftMargin = dp(8);
        actions.addView(refreshSources, refreshParams);
        refreshSources.setOnClickListener(v -> {
            List<String> urls = new ArrayList<>(); for (SubscriptionBook.Source source : book.sources()) urls.add(source.url());
            updateSubscriptions(urls);
        });
        importStatus = text(importMessage, 12, MUTED); subscriptions.addView(importStatus);
        space(body, 24); section(body, R.string.connection_settings);
        LinearLayout routing = card(12); body.addView(routing);
        LinearLayout routingRow = horizontal(); routing.addView(routingRow);
        TextView routingTitle = text(getString(R.string.smart_title), 16, INK);
        routingTitle.setMinimumHeight(dp(52)); routingRow.addView(routingTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button info = smallButton("i", R.string.smart_info); info.setId(R.id.smart_routing_info);
        info.setTextSize(17); info.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        info.setBackground(new RippleDrawable(ColorStateList.valueOf(0x163660DD), outlined(SURFACE, LINE, 24), null));
        routingRow.addView(info, new LinearLayout.LayoutParams(dp(48), dp(48))); info.setOnClickListener(v -> showRoutingInfo());
        smartSwitch = new Switch(this); smartSwitch.setId(R.id.smart_routing_switch);
        smartSwitch.setContentDescription(getString(R.string.smart_title)); smartSwitch.setChecked(RoutingLists.enabled(this));
        smartSwitch.setButtonTintList(ColorStateList.valueOf(ACCENT)); smartSwitch.setPadding(dp(12), 0, 0, 0);
        routingRow.addView(smartSwitch, new LinearLayout.LayoutParams(-2, dp(52)));
        smartSwitch.setOnCheckedChangeListener((view, value) -> {
            if (!routingReady || routingBusy) return;
            routingBusy = true; smartSwitch.setEnabled(false);
            io.execute(() -> {
                boolean success = true;
                try { RoutingLists.setEnabled(this, value); } catch (Exception e) { success = false; }
                boolean saved = RoutingLists.enabled(this), complete = success;
                main.post(() -> {
                    routingBusy = false; if (isDestroyed()) return;
                    if (smartSwitch != null) { routingReady = false; smartSwitch.setChecked(saved); routingReady = true; }
                    if (!complete) toast(R.string.smart_failed); render();
                });
            });
        });
        space(body, 24); section(body, R.string.app_settings);
        LinearLayout appearance = card(12); body.addView(appearance);
        languageButton = navigationRow(appearance, R.string.language, AppLanguage.choice(this).equals("system") ? getString(R.string.follow_system) : AppLanguage.selected(this).equals("ru") ? "Русский" : "English");
        languageButton.setId(R.id.language_button); languageButton.setOnClickListener(v -> chooseLanguage());
        divider(appearance);
        Button guide = navigationRow(appearance, R.string.tutorial, ""); guide.setId(R.id.tutorial_button);
        guide.setOnClickListener(v -> { dialog.dismiss(); showTutorial(0); });
        divider(appearance);
        Button updates = navigationRow(appearance, R.string.updates, installedVersion()); updates.setId(R.id.updates_button);
        updates.setOnClickListener(v -> {
            if (this.updates.snapshot().visible()) openUpdates();
            else { showWhenChecked = true; this.updates.request(true); }
        }); divider(appearance);
        LinearLayout wifiRow = horizontal();
        TextView wifiLabel = text(getString(R.string.update_wifi_only), 15, INK); wifiLabel.setMinimumHeight(dp(64));
        wifiRow.addView(wifiLabel, new LinearLayout.LayoutParams(0, -2, 1));
        Switch wifi = new Switch(this); wifi.setId(R.id.update_wifi_only); wifi.setContentDescription(getString(R.string.update_wifi_only));
        wifi.setPadding(dp(12), 0, 0, 0); wifi.setChecked(UpdateManager.wifiOnly(this));
        wifiRow.addView(wifi, new LinearLayout.LayoutParams(-2, dp(56))); appearance.addView(wifiRow); divider(appearance);
        wifi.setOnCheckedChangeListener((view, enabled) -> {
            if (!UpdateManager.setWifiOnly(this, enabled)) toast(R.string.operation_failed);
            this.updates.request(false);
        });
        Button about = navigationRow(appearance, R.string.about, "");
        about.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(R.string.about).setMessage(R.string.about_body)
                .setPositiveButton(R.string.close, null).show());
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            settings = null; sourceRows = null; smartSwitch = null; languageButton = null;
            addSources = null; refreshSources = null; importStatus = null;
        });
        dialog.show();
        dialog.getWindow().setLayout(-1, -1); dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        dialog.getWindow().setStatusBarColor(BACKGROUND); dialog.getWindow().setNavigationBarColor(BACKGROUND);
        dialog.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | (Build.VERSION.SDK_INT >= 27 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        render();
    }

    private String installedVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    private void updateChanged(UpdateSession.Snapshot snapshot) {
        if (isDestroyed()) return;
        updateButton.show(snapshot);
        updateRelease = snapshot.release(); updateReady = snapshot.phase() == UpdateSession.Phase.READY;
        if (installButton != null) { installButton.setEnabled(updateReady && !updateBusy); installButton.setAlpha(updateReady && !updateBusy ? 1f : .4f); }
        if (showWhenChecked && snapshot.phase() != UpdateSession.Phase.CHECKING) {
            showWhenChecked = false;
            if (snapshot.visible()) openUpdates();
            else toast(snapshot.phase() == UpdateSession.Phase.ERROR ? R.string.update_check_failed : R.string.update_current);
        }
    }

    private void openUpdates() {
        if (updateDialog != null && updateDialog.isShowing()) return;
        UpdateSession.Snapshot state = updates.snapshot();
        if (!state.visible()) { showWhenChecked = true; updates.request(true); return; }
        updateRelease = state.release(); updateReady = state.phase() == UpdateSession.Phase.READY;
        Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE); updateDialog = dialog;
        LinearLayout body = card(24); body.setBackground(round(SURFACE, 28));
        TextView heading = text(getString(R.string.update_version_title, updateRelease.versionName()), 24, INK);
        bold(heading); body.addView(heading); space(body, 18);
        TextView subtitle = text(getString(R.string.whats_new), 13, MUTED); bold(subtitle); body.addView(subtitle);
        String notes = updateRelease.notes(AppLanguage.selected(this));
        if (notes.isBlank()) notes = getString(R.string.update_notes_missing);
        TextView changes = text(notes.replaceAll("(?m)^- ", "• "), 16, INK); changes.setLineSpacing(dp(5), 1);
        ScrollView scroll = new ScrollView(this); scroll.addView(changes);
        int limit = (int) (getResources().getDisplayMetrics().heightPixels * .4f);
        changes.measure(View.MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().widthPixels-dp(80), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        body.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(limit, changes.getMeasuredHeight())));
        space(body, 24);
        LinearLayout actions = horizontal();
        Button later = button(R.string.later, false); later.setOnClickListener(v -> dialog.dismiss());
        actions.addView(later, new LinearLayout.LayoutParams(0, dp(48), 1));
        installButton = button(R.string.update_action, true); installButton.setId(R.id.install_update_button);
        installButton.setEnabled(updateReady && !updateBusy); installButton.setAlpha(updateReady && !updateBusy ? 1f : .4f);
        installButton.setOnClickListener(v -> installDownloaded());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(48), 1); actionParams.leftMargin = dp(12);
        actions.addView(installButton, actionParams); body.addView(actions);
        dialog.setContentView(body); dialog.setOnDismissListener(d -> { updateDialog = null; installButton = null; });
        dialog.show();
        Window window = dialog.getWindow(); window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(getResources().getDisplayMetrics().widthPixels - dp(32), -2);
        window.setGravity(Gravity.BOTTOM); WindowManager.LayoutParams position = window.getAttributes(); position.y = dp(24); window.setAttributes(position);
        updates.request(false);
    }

    private void installDownloaded() {
        if (updateRelease == null || updateBusy) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            try { startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + getPackageName())), 61); }
            catch (android.content.ActivityNotFoundException e) { toast(R.string.update_install_failed); }
            return;
        }
        updateBusy = true;
        if (installButton != null) installButton.setEnabled(false);
        UpdateRelease release = updateRelease;
        updateIo.execute(() -> {
            boolean verified = UpdateManager.ready(this, release);
            main.post(() -> {
                if (isDestroyed()) return;
                updateBusy = false;
                if (!verified) { updateReady = false; toast(R.string.update_download_failed); updates.request(false); return; }
                try {
                    Intent installer = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(UpdateProvider.uri(this))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(installer);
                } catch (android.content.ActivityNotFoundException | SecurityException e) { toast(R.string.update_install_failed); }
                if (installButton != null) installButton.setEnabled(true);
            });
        });
    }

    private void showRoutingInfo() {
        new AlertDialog.Builder(this).setTitle(R.string.smart_title)
                .setMessage(getString(R.string.smart_description) + "\n\n" + getString(R.string.smart_refresh_description))
                .setPositiveButton(R.string.close, null).show();
    }

    private void chooseLanguage() {
        String current = AppLanguage.choice(this);
        new AlertDialog.Builder(this).setTitle(R.string.language)
                .setSingleChoiceItems(new String[]{getString(R.string.follow_system), "English", "Русский"},
                        current.equals("system") ? 0 : current.equals("en") ? 1 : 2, (dialog, which) -> {
                    String next = which == 0 ? "system" : which == 1 ? "en" : "ru"; dialog.dismiss();
                    if (next.equals(current)) return;
                    if (AppLanguage.save(this, next)) { if (Build.VERSION.SDK_INT < 33) recreate(); }
                    else toast(R.string.operation_failed);
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showTutorial(int step) {
        if (tutorial != null) tutorial.dismiss();
        tutorialStep = step;
        int[] titles = {R.string.guide_add_title, R.string.guide_connect_title, R.string.guide_pool_title};
        int[] descriptions = {R.string.guide_add_body, R.string.guide_connect_body, R.string.guide_pool_body};
        int[] icons = {R.drawable.ic_add, R.drawable.ic_power, R.drawable.ic_pool_arrows};
        LinearLayout body = column(); body.setPadding(dp(24), dp(24), dp(24), dp(16));
        android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setId(R.id.tutorial_progress); progress.setIndeterminate(false);
        android.graphics.drawable.ClipDrawable fill = new android.graphics.drawable.ClipDrawable(round(ACCENT, 3), Gravity.LEFT,
                android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable track = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{round(PALE, 3), fill});
        track.setId(0, android.R.id.background); track.setId(1, android.R.id.progress);
        progress.setProgressDrawable(track); progress.setMax(titles.length); progress.setProgress(step + 1);
        progress.setContentDescription(getString(R.string.guide_progress, Math.round(100f * (step + 1) / titles.length)));
        body.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));
        FrameLayout symbol = new FrameLayout(this); symbol.setBackground(round(PALE, 20));
        ImageView icon = image(icons[step]); icon.setId(R.id.tutorial_icon); icon.setTag(icons[step]);
        icon.setImageTintList(ColorStateList.valueOf(ACCENT)); symbol.addView(icon, centered(34, 34));
        LinearLayout.LayoutParams symbolParams = new LinearLayout.LayoutParams(dp(76), dp(76));
        symbolParams.topMargin = dp(24); symbolParams.bottomMargin = dp(16); body.addView(symbol, symbolParams);
        TextView heading = text(getString(titles[step]), 23, INK); heading.setId(R.id.tutorial_title); bold(heading); body.addView(heading);
        TextView description = text(getString(descriptions[step]), 16, MUTED); description.setLineSpacing(dp(4), 1); body.addView(description);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        tutorial = new AlertDialog.Builder(this).setView(scroll).setCancelable(false)
                .setNegativeButton(R.string.skip, (dialog, which) -> finishTutorial())
                .setNeutralButton(step == 0 ? R.string.close : R.string.back, null)
                .setPositiveButton(step == 2 ? R.string.got_it : R.string.next, null).create();
        tutorial.setOnShowListener(dialog -> {
            for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE}) {
                tutorial.getButton(which).setAllCaps(false); tutorial.getButton(which).setTextSize(13);
            }
            Button back = tutorial.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (step == 0) back.setVisibility(View.GONE);
            else back.setOnClickListener(v -> showTutorial(step - 1));
            tutorial.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (step < 2) showTutorial(step + 1); else finishTutorial();
            });
        });
        tutorial.show(); tutorial.getWindow().setBackgroundDrawable(round(SURFACE, 24));
    }

    private void finishTutorial() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("tutorial_seen_v1", true).apply();
        if (tutorial != null) tutorial.dismiss(); tutorial = null; tutorialStep = -1;
    }
    private void showAddDialog() {
        EditText input = new EditText(this); input.setId(R.id.subscriptions_input);
        input.setHint(R.string.add_hint); input.setTextSize(14); input.setMinLines(3); input.setMaxLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSaveEnabled(false); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        LinearLayout wrapper = column(); wrapper.setPadding(dp(20), dp(8), dp(20), dp(8)); wrapper.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(wrapper)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.add, null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            List<String> urls = new ArrayList<>();
            for (String line : input.getText().toString().split("\\R")) if (!line.isBlank()) urls.add(line.trim());
            if (urls.isEmpty()) return;
            input.setText(""); dialog.dismiss(); updateSubscriptions(urls);
        }));
        dialog.show();
    }

    private void updateSubscriptions(List<String> urls) {
        if (importing || loading || PoolVpnService.snapshot.busy()) return;
        importing = true; importMessage = "…"; render();
        io.execute(() -> {
            try {
                SecretStore store = new SecretStore(this);
                SubscriptionRefresh.Result result = SubscriptionRefresh.run(store.loadBook(), urls, SubscriptionFetcher::fetchWithInfo);
                if (Thread.currentThread().isInterrupted()) return;
                if (result.succeeded() > 0) store.saveBook(result.book());
                List<Subscription.Node> pool = result.book().nodes();
                java.util.Map<String, Integer> counts = sourceCounts(result.book());
                main.post(() -> {
                    if (isDestroyed()) return;
                    book = result.book(); nodes = pool; sourceCounts = counts; importing = false;
                    importMessage = result.failed() == 0 ? "" : getString(R.string.source_failures, result.failed());
                    render();
                });
            } catch (Exception e) {
                main.post(() -> { importing = false; importMessage = getString(R.string.operation_failed); render(); });
            }
        });
    }

    private void removeSource(String id) {
        if (importing || PoolVpnService.snapshot.busy()) return;
        new AlertDialog.Builder(this).setMessage(R.string.remove_subscription).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (dialog, which) -> {
                    importing = true; render();
                    io.execute(() -> {
                        try {
                            SecretStore store = new SecretStore(this); SubscriptionBook next = store.loadBook().remove(id);
                            store.saveBook(next); List<Subscription.Node> pool = next.nodes();
                            java.util.Map<String, Integer> counts = sourceCounts(next);
                            main.post(() -> { book = next; nodes = pool; sourceCounts = counts; importing = false; render(); });
                        } catch (Exception e) { main.post(() -> { importing = false; toast(R.string.import_failed); render(); }); }
                    });
                }).show();
    }

    private void render() {
        if (isDestroyed()) return;
        PoolVpnService.Snapshot state = PoolVpnService.snapshot; boolean busy = state.busy();
        if (state.phase() == 4 && previousPhase != 4) toast(R.string.connection_failed);
        previousPhase = state.phase();
        power.setEnabled(!loading && !importing && state.phase() != 3);
        power.setContentDescription(getString(busy ? R.string.disconnect : R.string.connect));
        powerDisk.setColor(state.phase() == 2 ? 0xff0e8b66 : state.phase() == 1 ? ACCENT : 0xff68768d);
        powerHalo.setColor(state.phase() == 2 ? 0xffdcf5eb : state.phase() == 1 ? PALE : 0xffe8edf4);
        powerImage.setAlpha(state.phase() == 2 ? 1f : .85f); power.setAlpha(power.isEnabled() ? 1f : .4f);
        int phaseLabel = state.message() == R.string.status_reloading ? R.string.status_reloading : switch (state.phase()) {
            case 1 -> R.string.connecting_short; case 2 -> R.string.connected_short;
            case 3 -> R.string.disconnecting_short; case 4 -> R.string.connection_failed;
            default -> R.string.status_idle;
        };
        connectionState.setText(phaseLabel);
        connectionAction.setText(loading ? R.string.loading_short : busy ? R.string.tap_disconnect : R.string.tap_connect);
        List<Subscription.Node> shown = busy && !state.nodes().isEmpty() ? state.nodes() : nodes;
        poolCount.setText(getResources().getQuantityString(R.plurals.server_count, shown.size(), shown.size()));
        long downTotal = 0, upTotal = 0;
        for (PoolVpnService.Traffic traffic : state.traffic().values()) { downTotal += traffic.down(); upTotal += traffic.up(); }
        totalDown.setText(bytes(downTotal)); totalUp.setText(bytes(upTotal));
        emptyPool.setVisibility(shown.isEmpty() && !loading ? View.VISIBLE : View.GONE);
        rows.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        StringBuilder nodeIds = new StringBuilder(); for (Subscription.Node node : shown) nodeIds.append(node.tag()).append(';');
        String signature = nodeIds + state.traffic().toString();
        if (!signature.equals(rowSignature)) {
            rowSignature = signature; rows.removeAllViews();
            for (int i = 0; i < shown.size(); i++) {
                Subscription.Node node = shown.get(i);
                if (i > 0) divider(rows);
                LinearLayout row = horizontal(); row.setPadding(0, dp(10), 0, dp(10)); row.setMinimumHeight(dp(52));
                TextView name = text(node.name(), 13, INK); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                PoolVpnService.Traffic traffic = state.traffic().get(node.tag());
                row.addView(number(bytes(traffic == null ? 0 : traffic.down()), 11, MUTED), new LinearLayout.LayoutParams(dp(68), -2));
                row.addView(number(bytes(traffic == null ? 0 : traffic.up()), 11, MUTED), new LinearLayout.LayoutParams(dp(68), -2));
                rows.addView(row);
            }
        }
        renderUsage();
        renderSettings(busy);
    }

    private void renderSettings(boolean busy) {
        if (settings == null || sourceRows == null) return;
        boolean editable = !busy && !loading && !importing;
        addSources.setEnabled(editable); refreshSources.setEnabled(editable && !book.sources().isEmpty());
        languageButton.setEnabled(!loading && !importing && !routingBusy);
        importStatus.setText(busy ? getString(R.string.subscription_busy_short) : importMessage);
        importStatus.setVisibility(importStatus.getText().length() == 0 ? View.GONE : View.VISIBLE);
        String sourceKey = book.sources().stream().map(SubscriptionBook.Source::id).collect(java.util.stream.Collectors.joining(";"))
                + sourceCounts + editable + usageKey();
        if (!sourceKey.equals(settingsSourceKey)) {
            settingsSourceKey = sourceKey; sourceRows.removeAllViews();
            if (book.sources().isEmpty()) {
                TextView empty = text(getString(R.string.no_subscriptions), 14, MUTED); empty.setPadding(0, 0, 0, dp(16)); sourceRows.addView(empty);
            }
            for (SubscriptionBook.Source source : book.sources()) {
                LinearLayout row = horizontal(); row.setMinimumHeight(dp(56));
                int size = sourceCounts.getOrDefault(source.id(), 0);
                TextView name = text(getString(R.string.source_summary, source.label(), size), 14, INK);
                name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                Button remove = smallButton("×", R.string.remove); remove.setEnabled(editable); remove.setTextSize(24);
                row.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(48)));
                remove.setOnClickListener(v -> removeSource(source.id())); sourceRows.addView(row);
                addUsage(sourceRows, source);
                space(sourceRows, 10);
            }
            if (!book.sources().isEmpty()) space(sourceRows, 12);
        }
        if (smartSwitch != null) smartSwitch.setEnabled(routingReady && !routingBusy);
    }

    private String usageKey() {
        StringBuilder key = new StringBuilder().append(System.currentTimeMillis() / 60000);
        for (SubscriptionBook.Source source : book.sources()) key.append(';').append(source.id()).append(source.usage()).append('@').append(source.usageCheckedAt());
        return key.toString();
    }
    private void renderUsage() {
        usagePanel.setVisibility(book.sources().isEmpty() ? View.GONE : View.VISIBLE);
        String signature = usageKey(); if (signature.equals(usageSignature)) return;
        usageSignature = signature; usageRows.removeAllViews();
        for (SubscriptionBook.Source source : book.sources()) {
            if (usageRows.getChildCount() > 0) { space(usageRows, 8); divider(usageRows); space(usageRows, 8); }
            TextView name = text(source.label(), 14, INK); bold(name); name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END); usageRows.addView(name);
            addUsage(usageRows, source);
        }
    }
    private void addUsage(LinearLayout parent, SubscriptionBook.Source source) {
        SubscriptionUsage usage = source.usage(); long used = usage.used();
        String summary;
        if (usage.total() == 0) summary = used >= 0 ? getString(R.string.quota_used_unlimited, bytes(used)) : getString(R.string.quota_unlimited);
        else if (usage.total() > 0) summary = used >= 0 ? getString(R.string.quota_used_limit, bytes(used), bytes(usage.total()))
                : getString(R.string.quota_limit_only, bytes(usage.total()));
        else summary = used >= 0 ? getString(R.string.quota_used_unknown_limit, bytes(used)) : getString(R.string.quota_unknown);
        TextView value = text(summary, 13, MUTED); value.setTag("quota-" + source.id()); parent.addView(value);
        if (usage.total() > 0 && used >= 0) {
            int color = used >= usage.total() ? 0xffbf3f45 : ACCENT;
            android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            android.graphics.drawable.LayerDrawable track = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                    round(PALE, 3), new android.graphics.drawable.ClipDrawable(round(color, 3), Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL)});
            track.setId(0, android.R.id.background); track.setId(1, android.R.id.progress);
            progress.setProgressDrawable(track); progress.setMax(1000); progress.setProgress(usage.progress());
            progress.setContentDescription(getString(R.string.quota_progress, bytes(used), bytes(usage.total())));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(4)); params.topMargin = dp(4); params.bottomMargin = dp(4);
            parent.addView(progress, params);
        }
        if (usage.expire() > 0) {
            long expiry = usage.expire() * 1000; boolean expired = expiry <= System.currentTimeMillis();
            java.text.DateFormat date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, getResources().getConfiguration().getLocales().get(0));
            parent.addView(text(getString(expired ? R.string.quota_expired : R.string.quota_expires, date.format(new java.util.Date(expiry))), 12, expired ? 0xffbf3f45 : MUTED));
        }
        long checked = source.usageCheckedAt();
        if (usage.known() && checked > 0 && System.currentTimeMillis() - checked >= USAGE_INTERVAL) {
            java.text.DateFormat date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
                    getResources().getConfiguration().getLocales().get(0));
            parent.addView(text(getString(R.string.quota_updated, date.format(new java.util.Date(checked))), 11, MUTED));
        }
    }

    private void refreshUsageIfNeeded() {
        if (loading || importing || usageRefreshing || book.sources().isEmpty()) return;
        long now = System.currentTimeMillis(); if (now < nextUsageCheck) return;
        nextUsageCheck = now + 60000;
        android.content.SharedPreferences preferences = getSharedPreferences("subscriptions", MODE_PRIVATE);
        long attempt = preferences.getLong("usage_last_attempt", 0);
        if (attempt > 0 && now >= attempt && now - attempt < USAGE_INTERVAL) return;
        List<SubscriptionBook.Source> due = new ArrayList<>();
        for (SubscriptionBook.Source source : book.sources())
            if (source.usageCheckedAt() <= 0 || now < source.usageCheckedAt() || now - source.usageCheckedAt() >= USAGE_INTERVAL) due.add(source);
        if (due.isEmpty()) return;
        preferences.edit().putLong("usage_last_attempt", now).apply(); usageAttempt = now; usageRefreshing = true;
        usageIo.execute(() -> {
            try {
                List<SubscriptionRefresh.UsageUpdate> received = SubscriptionRefresh.usage(due, usageFetcher);
                if (Thread.currentThread().isInterrupted() || isDestroyed()) return;
                io.execute(() -> {
                    try {
                        if (Thread.currentThread().isInterrupted()) return;
                        SecretStore store = new SecretStore(this); SubscriptionBook latest = store.loadBook();
                        SubscriptionBook next = latest.withUsage(received);
                        if (Thread.currentThread().isInterrupted()) return;
                        if (!received.isEmpty()) store.saveBook(next);
                        main.post(() -> { if (!isDestroyed()) { book = next; usageRefreshing = false; render(); } });
                    } catch (Exception e) { main.post(() -> usageRefreshing = false); }
                });
            } catch (Exception e) { main.post(() -> usageRefreshing = false); }
        });
    }

    private static java.util.Map<String, Integer> sourceCounts(SubscriptionBook book) throws Exception {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (SubscriptionBook.Source source : book.sources()) counts.put(source.id(), source.serverCount());
        return java.util.Map.copyOf(counts);
    }
    private void startVpn() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 51);
        startForegroundService(new Intent(this, PoolVpnService.class));
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 50) { if (result == RESULT_OK) startVpn(); else toast(R.string.permission_denied); }
        if (request == 61 && getPackageManager().canRequestPackageInstalls()) installDownloaded();
    }
    private void toast(int resource) { if (!isDestroyed()) Toast.makeText(this, resource, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout horizontal() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout page() {
        LinearLayout root = column(); root.setBackgroundColor(BACKGROUND); root.setPadding(dp(20), dp(12), dp(20), dp(16));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(20), dp(8) + insets.getSystemWindowInsetTop(), dp(20), dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        }); return root;
    }
    private GradientDrawable round(int color, int radius) { GradientDrawable v = new GradientDrawable(); v.setColor(color); v.setCornerRadius(dp(radius)); return v; }
    private GradientDrawable outlined(int color, int stroke, int radius) { GradientDrawable v = round(color, radius); v.setStroke(dp(1), stroke); return v; }
    private LinearLayout card(int padding) { LinearLayout v = column(); v.setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); v.setBackground(round(SURFACE, 20)); return v; }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setGravity(Gravity.CENTER_VERTICAL); v.setPadding(0, dp(4), 0, dp(4)); return v; }
    private TextView number(String value, int size, int color) { TextView v = text(value, size, color); v.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return v; }
    private void bold(TextView view) { view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); }
    private ImageView image(int resource) { ImageView v = new ImageView(this); v.setImageResource(resource); v.setScaleType(ImageView.ScaleType.FIT_CENTER); v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return v; }
    private FrameLayout iconButton(int resource, int description) {
        FrameLayout v = new FrameLayout(this); v.setContentDescription(getString(description)); v.setClickable(true); v.setFocusable(true);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x163660DD), round(SURFACE, 16), null));
        ImageView icon = image(resource); icon.setImageTintList(ColorStateList.valueOf(INK)); v.addView(icon, centered(24, 24)); return v;
    }
    private FrameLayout.LayoutParams centered(int width, int height) { return new FrameLayout.LayoutParams(dp(width), dp(height), Gravity.CENTER); }
    private void space(LinearLayout parent, int size) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size))); }
    private void divider(LinearLayout parent) { View line = new View(this); line.setBackgroundColor(LINE); parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1))); }
    private void section(LinearLayout parent, int title) { TextView text = text(getString(title), 13, MUTED); bold(text); text.setPadding(dp(4), 0, 0, dp(10)); parent.addView(text); }
    private Button button(int label, boolean primary) {
        Button v = new Button(this); v.setStateListAnimator(null); v.setText(label); v.setAllCaps(false); v.setTextColor(primary ? Color.WHITE : ACCENT); v.setTextSize(14);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x303660DD), round(primary ? ACCENT : PALE, 12), null));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48)); p.topMargin = dp(8); v.setLayoutParams(p); return v;
    }
    private Button smallButton(String label, int description) {
        Button v = new Button(this); v.setStateListAnimator(null); v.setText(label); v.setContentDescription(getString(description)); v.setTextColor(ACCENT);
        v.setMinWidth(0); v.setMinimumWidth(0); v.setPadding(0, 0, 0, 0); v.setAllCaps(false);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x163660DD), round(Color.TRANSPARENT, 24), null)); return v;
    }
    private Button navigationRow(LinearLayout parent, int label, String value) {
        Button v = smallButton(getString(label) + (value.isEmpty() ? "" : "  ·  " + value) + "  ›", label);
        v.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); v.setPadding(dp(4), dp(8), dp(4), dp(8));
        v.setTextColor(INK); v.setTextSize(15); v.setMinimumHeight(dp(56)); parent.addView(v, new LinearLayout.LayoutParams(-1, -2)); return v;
    }
    private LinearLayout trafficCard(int label, String arrow) {
        LinearLayout card = card(14); card.setMinimumHeight(dp(84));
        TextView heading = text(arrow + "  " + getString(label), 12, MUTED); card.addView(heading);
        TextView value = text("0 B", 21, INK); bold(value); card.addView(value); return card;
    }
    private String bytes(long value) {
        if (value < 1024) return value + " B";
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        if (value < 1024 * 1024) return String.format(locale, "%.1f KB", value / 1024.0);
        if (value < 1024L * 1024 * 1024) return String.format(locale, "%.1f MB", value / (1024.0 * 1024));
        return String.format(locale, "%.1f GB", value / (1024.0 * 1024 * 1024));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putBoolean("settings", settings != null && settings.isShowing());
        state.putInt("tutorial", tutorialStep);
    }
    @Override protected void onStart() { super.onStart(); updates.attach(updateObserver); }
    @Override protected void onStop() { updates.detach(updateObserver); super.onStop(); }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        nextTrafficSample = 0;
        main.post(tick);
    }
    @Override protected void onPause() { resumed = false; main.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() {
        if (settings != null) settings.dismiss(); if (tutorial != null) tutorial.dismiss(); if (updateDialog != null) updateDialog.dismiss();
        if (usageRefreshing) {
            android.content.SharedPreferences preferences = getSharedPreferences("subscriptions", MODE_PRIVATE);
            if (preferences.getLong("usage_last_attempt", 0) == usageAttempt) preferences.edit().remove("usage_last_attempt").apply();
        }
        io.shutdownNow(); updateIo.shutdownNow(); usageIo.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
