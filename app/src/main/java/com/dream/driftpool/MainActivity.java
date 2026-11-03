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
    private final Handler main = new Handler(Looper.getMainLooper());
    private SubscriptionBook book = new SubscriptionBook(List.of());
    private List<Subscription.Node> nodes = List.of();
    private boolean loading = true, importing, resumed, routingReady, routingBusy, reopenSettings;
    private FrameLayout power;
    private ImageView powerImage;
    private TextView connectionState, connectionAction, poolCount, totalDown, totalUp, importStatus;
    private LinearLayout rows, sourceRows;
    private View emptyPool;
    private Switch smartSwitch;
    private Button addSources, refreshSources, languageButton;
    private Dialog settings;
    private AlertDialog tutorial, updateDialog;
    private UpdateRelease updateRelease;
    private boolean updateReady, updateBusy, reopenUpdates;
    private int tutorialStep = -1;
    private String rowSignature = "", importMessage = "", settingsSourceKey = "";
    private java.util.Map<String, Integer> sourceCounts = java.util.Map.of();
    private int previousPhase;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            render(); main.postDelayed(this, 1500);
        }
    };

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppLanguage.wrap(base)); }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        reopenUpdates = UpdateManager.OPEN.equals(getIntent().getAction());
        getIntent().setAction(Intent.ACTION_MAIN);
        reopenSettings = savedInstanceState != null && savedInstanceState.getBoolean("settings");
        int restoredStep = savedInstanceState == null ? -1 : savedInstanceState.getInt("tutorial", -1);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root = page();
        LinearLayout header = horizontal();
        TextView brand = text(getString(R.string.app_name), 24, INK); bold(brand);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(56), 1));
        FrameLayout gear = iconButton(R.drawable.ic_settings, R.string.settings); gear.setId(R.id.settings_button);
        gear.setOnClickListener(v -> openSettings()); header.addView(gear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);
        ScrollView homeScroll = new ScrollView(this); homeScroll.setFillViewport(true);
        LinearLayout content = column(); homeScroll.addView(content);
        root.addView(homeScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout hero = column(); hero.setGravity(Gravity.CENTER); hero.setPadding(0, dp(16), 0, dp(14));
        power = new FrameLayout(this); power.setId(R.id.connect_button); power.setClickable(true); power.setFocusable(true);
        power.setBackground(new RippleDrawable(ColorStateList.valueOf(0x203660DD), round(PALE, 100), null));
        FrameLayout inner = new FrameLayout(this); inner.setBackground(round(ACCENT, 100));
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
        UpdateManager.schedule(this);
        updateIo.execute(() -> { try { UpdateManager.automatic(this); } catch (Exception ignored) { /* Retry on next launch/job. */ } });
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
        languageButton = navigationRow(appearance, R.string.language, AppLanguage.selected(this).equals("ru") ? "Русский" : "English");
        languageButton.setId(R.id.language_button); languageButton.setOnClickListener(v -> chooseLanguage());
        divider(appearance);
        Button guide = navigationRow(appearance, R.string.tutorial, ""); guide.setId(R.id.tutorial_button);
        guide.setOnClickListener(v -> { dialog.dismiss(); showTutorial(0); });
        divider(appearance);
        Button updates = navigationRow(appearance, R.string.updates, installedVersion()); updates.setId(R.id.updates_button);
        updates.setOnClickListener(v -> openUpdates()); divider(appearance);
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

    private void openUpdates() {
        if (updateDialog != null && updateDialog.isShowing()) return;
        updateRelease = null; updateReady = false; updateBusy = false;
        updateDialog = new AlertDialog.Builder(this).setTitle(R.string.updates).setMessage(R.string.update_checking)
                .setNegativeButton(R.string.close, null).setPositiveButton(R.string.check_updates, null).create();
        updateDialog.setOnDismissListener(dialog -> { updateDialog = null; });
        updateDialog.setOnShowListener(dialog -> updateDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (updateBusy) return;
            if (updateRelease == null) checkUpdate();
            else if (updateReady) installDownloaded(); else downloadUpdate();
        }));
        updateDialog.show(); checkUpdate();
    }

    private void checkUpdate() {
        if (updateDialog == null || updateBusy) return;
        AlertDialog target = updateDialog; updateBusy = true;
        target.setMessage(getString(R.string.update_checking)); target.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        updateIo.execute(() -> {
            UpdateRelease found = null; boolean failed = false, ready = false;
            try { found = UpdateManager.check(this); } catch (Exception e) { failed = true; found = UpdateManager.cached(this); }
            try { if (!UpdateManager.newer(this, found)) found = null; } catch (Exception e) { found = null; failed = true; }
            if (found != null) ready = UpdateManager.ready(this, found);
            UpdateRelease result = found; boolean error = failed, downloaded = ready;
            main.post(() -> {
                if (isDestroyed() || updateDialog != target) return;
                updateBusy = false; updateRelease = result; updateReady = downloaded;
                renderUpdate(error ? R.string.update_check_failed : result == null ? R.string.update_current : 0);
            });
        });
    }

    private void renderUpdate(int message) {
        if (updateDialog == null) return;
        String text = getString(R.string.installed_version, installedVersion()) + "\n\n";
        if (updateRelease != null) text += getString(R.string.update_available, updateRelease.versionName()) + "\n"
                + getString(updateReady ? R.string.update_ready : R.string.update_size, updateRelease.size() / 1_000_000.0) + "\n\n";
        if (message != 0) text += getString(message) + "\n\n";
        text += getString(R.string.update_automatic_hint);
        if (updateRelease != null) text += "\n\n" + getString(R.string.update_install_hint);
        updateDialog.setMessage(text);
        Button action = updateDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        action.setText(updateRelease == null ? R.string.check_updates : updateReady ? R.string.install_update : R.string.download_update);
        action.setEnabled(!updateBusy);
    }

    private void downloadUpdate() {
        if (updateDialog == null || updateRelease == null || updateBusy) return;
        AlertDialog target = updateDialog; UpdateRelease release = updateRelease; updateBusy = true;
        target.setMessage(getString(R.string.update_downloading)); target.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        updateIo.execute(() -> {
            boolean success = false;
            try { UpdateManager.download(this, release); success = true; } catch (Exception ignored) { }
            boolean downloaded = success;
            main.post(() -> {
                if (isDestroyed() || updateDialog != target) return;
                updateBusy = false; updateReady = downloaded; renderUpdate(downloaded ? 0 : R.string.update_download_failed);
            });
        });
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
        if (updateDialog != null) updateDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        UpdateRelease release = updateRelease;
        updateIo.execute(() -> {
            boolean verified = UpdateManager.ready(this, release);
            main.post(() -> {
                if (isDestroyed()) return;
                updateBusy = false;
                if (!verified) { updateReady = false; renderUpdate(R.string.update_download_failed); return; }
                try {
                    Intent installer = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(UpdateProvider.uri(this))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(installer);
                } catch (android.content.ActivityNotFoundException | SecurityException e) { toast(R.string.update_install_failed); }
                renderUpdate(0);
            });
        });
    }

    private void showRoutingInfo() {
        new AlertDialog.Builder(this).setTitle(R.string.smart_title)
                .setMessage(getString(R.string.smart_description) + "\n\n" + getString(R.string.smart_refresh_description))
                .setPositiveButton(R.string.close, null).show();
    }

    private void chooseLanguage() {
        String current = AppLanguage.selected(this);
        new AlertDialog.Builder(this).setTitle(R.string.language)
                .setSingleChoiceItems(new String[]{"English", "Русский"}, current.equals("ru") ? 1 : 0, (dialog, which) -> {
                    String next = which == 1 ? "ru" : "en"; dialog.dismiss();
                    if (next.equals(current)) return;
                    if (AppLanguage.save(this, next)) recreate(); else toast(R.string.operation_failed);
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showTutorial(int step) {
        if (tutorial != null) tutorial.dismiss();
        tutorialStep = step;
        int[] titles = {R.string.guide_add_title, R.string.guide_connect_title, R.string.guide_pool_title};
        int[] descriptions = {R.string.guide_add_body, R.string.guide_connect_body, R.string.guide_pool_body};
        String[] symbols = {"+", "⏻", "⇄"};
        LinearLayout body = column(); body.setPadding(dp(24), dp(24), dp(24), dp(16));
        TextView counter = text(getString(R.string.guide_step, step + 1, 3), 12, MUTED); body.addView(counter);
        TextView symbol = text(symbols[step], 40, ACCENT); symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(round(PALE, 20)); LinearLayout.LayoutParams symbolParams = new LinearLayout.LayoutParams(dp(76), dp(76));
        symbolParams.topMargin = dp(20); symbolParams.bottomMargin = dp(16); body.addView(symbol, symbolParams);
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
        tutorial.show();
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
                SubscriptionRefresh.Result result = SubscriptionRefresh.run(store.loadBook(), urls, SubscriptionFetcher::fetch);
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
                + sourceCounts + editable;
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
            }
            if (!book.sources().isEmpty()) space(sourceRows, 12);
        }
        if (smartSwitch != null) smartSwitch.setEnabled(routingReady && !routingBusy);
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
    @Override protected void onResume() { super.onResume(); resumed = true; main.post(tick); }
    @Override protected void onPause() { resumed = false; main.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() {
        if (settings != null) settings.dismiss(); if (tutorial != null) tutorial.dismiss(); if (updateDialog != null) updateDialog.dismiss();
        io.shutdownNow(); updateIo.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
