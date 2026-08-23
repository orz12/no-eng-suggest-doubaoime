package com.doubao.ime.noensuggest.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.doubao.ime.noensuggest.ModuleStatusProtocol;
import com.doubao.ime.noensuggest.ModuleStatusReceiver;
import com.doubao.ime.noensuggest.ModuleApplication;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;

public class MainActivity extends Activity implements ModuleApplication.StateListener {
    private static final int PAGE_HOME = 0;
    private static final int PAGE_LOGS = 1;
    private static final int PAGE_ABOUT = 2;
    private static final long RECENT_STATUS_MS = 10 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView[] navigationItems = new TextView[3];

    private View[] pages;
    private int selectedPage = PAGE_HOME;
    private TextView frameworkStatusText;
    private TextView frameworkStatusDetail;
    private TextView targetStatusText;
    private TextView targetStatusDetail;
    private TextView hookRuntime;
    private TextView targetVersion;
    private TextView aboutVersion;
    private TextView logPath;
    private TextView logView;
    private TextView logProgress;
    private TextView loggingState;
    private Button restartButton;
    private Switch logSwitch;
    private ScrollView logScroll;

    private boolean receiverRegistered;
    private boolean frameworkChecking;
    private boolean receivedLiveStatus;
    private boolean targetResponding;
    private boolean suppressLogSwitch;
    private boolean logLoading;
    private boolean logHasMore;
    private long logNextOffset;
    private long requestedLogOffset;
    private long frameworkCheckStartedAt;
    private int logRequestId;
    private int frameworkCheckGeneration;

    private int colorBackground;
    private int colorCard;
    private int colorInner;
    private int colorPrimary;
    private int colorSecondary;
    private int colorAccent;
    private int colorDanger;
    private int colorBorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        configureColors();
        getWindow().setStatusBarColor(colorBackground);
        getWindow().setNavigationBarColor(colorBackground);
        setContentView(createRootView());
        renderTargetVersion();
        renderModuleVersion();
        renderFrameworkStatus();
        renderTargetNotResponding(false);
        showPage(PAGE_HOME);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleApplication.addStateListener(this);
        renderFrameworkStatus();
        registerStatusReceiver();
        queryStatus();
        if (selectedPage == PAGE_LOGS) {
            resetLogs();
        }
    }

    @Override
    protected void onStop() {
        ModuleApplication.removeStateListener(this);
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    public void onFrameworkStateChanged() {
        if (!frameworkChecking) {
            renderFrameworkStatus();
            return;
        }
        int generation = frameworkCheckGeneration;
        long elapsed = System.currentTimeMillis() - frameworkCheckStartedAt;
        long remaining = Math.max(0L, 450L - elapsed);
        handler.postDelayed(() -> {
            if (frameworkChecking && generation == frameworkCheckGeneration) {
                frameworkChecking = false;
                renderFrameworkStatus();
            }
        }, remaining);
    }

    private View createRootView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colorBackground);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        }

        FrameLayout pageHost = new FrameLayout(this);
        pages = new View[]{createHomePage(), createLogsPage(), createAboutPage()};
        for (View page : pages) {
            pageHost.addView(page, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        root.addView(pageHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(createBottomNavigation(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(66)));
        return root;
    }

    private View createHomePage() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        LinearLayout content = pageContent();

        content.addView(text("豆包输入法英文直输", 28f, colorPrimary, Typeface.BOLD));
        content.addView(text("LSPosed 模块控制与输入体验", 14f,
                colorSecondary, Typeface.NORMAL), topMargin(4));

        EditText experienceInput = new EditText(this);
        experienceInput.setHint("点此体验……");
        experienceInput.setHintTextColor(colorSecondary);
        experienceInput.setTextColor(colorPrimary);
        experienceInput.setTextSize(16f);
        experienceInput.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        experienceInput.setPadding(dp(14), 0, dp(14), 0);
        experienceInput.setSingleLine(true);
        experienceInput.setInputType(InputType.TYPE_CLASS_TEXT);
        experienceInput.setSaveEnabled(false);
        experienceInput.setFreezesText(false);
        experienceInput.setBackground(rounded(colorInner, colorBorder, 12));
        content.addView(experienceInput, fixedHeightMargin(52, 16));

        LinearLayout statusCard = card();
        statusCard.addView(sectionTitle("运行状态"));
        LinearLayout statusColumns = new LinearLayout(this);
        statusColumns.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout frameworkColumn = new LinearLayout(this);
        frameworkColumn.setOrientation(LinearLayout.VERTICAL);
        frameworkColumn.addView(text("LSPosed 模块", 13f,
                colorSecondary, Typeface.BOLD));
        frameworkStatusText = text("检测中", 19f, colorSecondary, Typeface.BOLD);
        frameworkColumn.addView(frameworkStatusText, topMargin(7));
        frameworkStatusDetail = text("等待官方框架服务…",
                11f, colorSecondary, Typeface.NORMAL);
        frameworkStatusDetail.setLineSpacing(0f, 1.12f);
        frameworkColumn.addView(frameworkStatusDetail, topMargin(5));
        statusColumns.addView(frameworkColumn,
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        View divider = new View(this);
        divider.setBackgroundColor(colorBorder);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                dp(1), LinearLayout.LayoutParams.MATCH_PARENT);
        dividerParams.leftMargin = dp(10);
        dividerParams.rightMargin = dp(10);
        statusColumns.addView(divider, dividerParams);

        LinearLayout targetColumn = new LinearLayout(this);
        targetColumn.setOrientation(LinearLayout.VERTICAL);
        targetColumn.addView(text("豆包输入法进程", 13f,
                colorSecondary, Typeface.BOLD));
        targetStatusText = text("检测中", 19f, colorSecondary, Typeface.BOLD);
        targetColumn.addView(targetStatusText, topMargin(7));
        targetStatusDetail = text("等待实时回应…",
                11f, colorSecondary, Typeface.NORMAL);
        targetStatusDetail.setLineSpacing(0f, 1.12f);
        targetColumn.addView(targetStatusDetail, topMargin(5));
        statusColumns.addView(targetColumn,
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusCard.addView(statusColumns, topMargin(12));

        Button recheck = button("重新检测", false);
        recheck.setOnClickListener(v -> {
            recheckFrameworkStatus();
            queryStatus();
        });
        statusCard.addView(recheck, buttonMargin());
        content.addView(statusCard, cardMargin());

        LinearLayout versionCard = card();
        versionCard.addView(sectionTitle("豆包输入法版本"));
        targetVersion = text("正在读取…", 15f, colorPrimary, Typeface.NORMAL);
        targetVersion.setLineSpacing(0f, 1.2f);
        versionCard.addView(targetVersion, topMargin(10));
        restartButton = button("一键重启豆包输入法", true);
        restartButton.setOnClickListener(v -> restartTarget());
        versionCard.addView(restartButton, buttonMargin());
        content.addView(versionCard, cardMargin());

        LinearLayout hookCard = card();
        hookCard.addView(sectionTitle("Hook 修改位置与作用"));
        hookRuntime = text("等待运行状态…", 13f, colorSecondary, Typeface.BOLD);
        hookCard.addView(hookRuntime, topMargin(10));
        TextView hookList = text(hookDescription(), 13f, colorPrimary, Typeface.NORMAL);
        hookList.setLineSpacing(dp(2), 1.16f);
        hookCard.addView(hookList, topMargin(12));
        content.addView(hookCard, cardMargin());
        content.addView(space(16));

        content.setFocusableInTouchMode(true);
        content.requestFocus();
        page.addView(content);
        return page;
    }

    private View createLogsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(20), dp(18), dp(14));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text("运行日志", 26f, colorPrimary, Typeface.BOLD),
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        logSwitch = new Switch(this);
        logSwitch.setShowText(false);
        logSwitch.setButtonTintList(ColorStateList.valueOf(colorAccent));
        titleRow.addView(logSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));
        page.addView(titleRow);

        loggingState = text("", 13f, colorSecondary, Typeface.NORMAL);
        page.addView(loggingState, topMargin(2));
        logPath = text("路径：等待目标进程回应", 11f, colorSecondary, Typeface.NORMAL);
        logPath.setTypeface(Typeface.MONOSPACE);
        page.addView(logPath, topMargin(8));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = button("从头刷新", false);
        refresh.setOnClickListener(v -> resetLogs());
        Button clear = button("清空日志", false);
        clear.setBackgroundTintList(ColorStateList.valueOf(colorDanger));
        clear.setTextColor(Color.WHITE);
        clear.setOnClickListener(v -> confirmClearLogs());
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        clearParams.leftMargin = dp(10);
        actions.addView(clear, clearParams);
        page.addView(actions, topMargin(12));

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackground(rounded(colorInner, colorBorder, 12));
        logView = text("正在读取日志…", 11f, colorPrimary, Typeface.NORMAL);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        logScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            View child = logScroll.getChildAt(0);
            if (child != null && y + logScroll.getHeight() >= child.getHeight() - dp(120)) {
                loadMoreLogs();
            }
        });
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        logParams.topMargin = dp(12);
        page.addView(logScroll, logParams);

        logProgress = text("", 11f, colorSecondary, Typeface.NORMAL);
        logProgress.setGravity(Gravity.CENTER);
        page.addView(logProgress, topMargin(8));

        SharedPreferences prefs = statusPreferences();
        suppressLogSwitch = true;
        logSwitch.setChecked(prefs.getBoolean(
                ModuleStatusReceiver.KEY_LOGGING_ENABLED, false));
        suppressLogSwitch = false;
        renderLoggingState(logSwitch.isChecked());
        logSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!suppressLogSwitch) {
                setLoggingEnabled(checked);
            }
        });
        return page;
    }

    private View createAboutPage() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        LinearLayout content = pageContent();
        content.addView(text("关于", 28f, colorPrimary, Typeface.BOLD));
        content.addView(text("豆包输入法英文直输", 14f,
                colorSecondary, Typeface.NORMAL), topMargin(4));

        LinearLayout introCard = card();
        introCard.addView(sectionTitle("模块介绍"));
        TextView introduction = text(
                "本模块面向官方豆包输入法，通过 LSPosed 在英文键盘下实现单字符直接上屏，"
                        + "同时阻断英文预编辑、候选词、联想词和切换语言时的重复提交。\n\n"
                        + "中文拼音、中文候选与中文联想保持输入法原有行为。",
                14f, colorPrimary, Typeface.NORMAL);
        introduction.setLineSpacing(dp(2), 1.2f);
        introCard.addView(introduction, topMargin(10));
        content.addView(introCard, topMargin(18));

        LinearLayout infoCard = card();
        infoCard.addView(sectionTitle("模块信息"));
        infoCard.addView(infoRow("作者", "orz12"), topMargin(12));
        aboutVersion = infoRow("版本", "正在读取…");
        infoCard.addView(aboutVersion, topMargin(8));
        infoCard.addView(infoRow("目标包名",
                ModuleStatusProtocol.TARGET_PACKAGE), topMargin(8));
        content.addView(infoCard, cardMargin());

        LinearLayout noticeCard = card();
        noticeCard.addView(sectionTitle("使用说明"));
        noticeCard.addView(text(
                "请在 LSPosed 中启用本模块，并将豆包输入法加入作用域。"
                        + "更新模块后需要重启豆包输入法进程，首页可直接执行。",
                14f, colorPrimary, Typeface.NORMAL), topMargin(10));
        content.addView(noticeCard, cardMargin());
        content.addView(space(16));

        page.addView(content);
        return page;
    }

    private View createBottomNavigation() {
        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(10), dp(7), dp(10), dp(7));
        navigation.setBackgroundColor(colorCard);
        navigation.setElevation(dp(12));
        String[] labels = {"首页", "日志", "关于"};
        for (int i = 0; i < labels.length; i++) {
            final int page = i;
            TextView item = text(labels[i], 14f, colorSecondary, Typeface.NORMAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(v -> showPage(page));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) {
                params.leftMargin = dp(6);
            }
            navigation.addView(item, params);
            navigationItems[i] = item;
        }
        return navigation;
    }

    private void showPage(int page) {
        selectedPage = page;
        for (int i = 0; i < pages.length; i++) {
            boolean selected = i == page;
            pages[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            navigationItems[i].setTextColor(selected ? colorAccent : colorSecondary);
            navigationItems[i].setTypeface(Typeface.create(
                    Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL));
            navigationItems[i].setBackground(selected
                    ? rounded(colorInner, colorBorder, 12) : null);
        }
        if (page == PAGE_LOGS) {
            resetLogs();
        }
    }

    private void setLoggingEnabled(boolean enabled) {
        statusPreferences().edit()
                .putBoolean(ModuleStatusReceiver.KEY_LOGGING_ENABLED, enabled)
                .commit();
        renderLoggingState(enabled);
        sendBroadcast(ModuleStatusProtocol.command(ModuleStatusProtocol.ACTION_SET_LOGGING)
                .putExtra(ModuleStatusProtocol.EXTRA_LOG_ENABLED, enabled));
        if (enabled) {
            Toast.makeText(this, "日志记录已开启", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "日志记录已关闭", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderLoggingState(boolean enabled) {
        loggingState.setText(enabled
                ? "日志记录已开启；滚动到底部会自动加载下一段。"
                : "日志记录已关闭（默认）；仍可查看或清空已有日志。");
        loggingState.setTextColor(enabled ? colorAccent : colorSecondary);
    }

    private void resetLogs() {
        if (logView == null) {
            return;
        }
        logRequestId++;
        logNextOffset = 0L;
        requestedLogOffset = 0L;
        logHasMore = true;
        logLoading = false;
        logView.setText("");
        logScroll.scrollTo(0, 0);
        loadMoreLogs();
    }

    private void loadMoreLogs() {
        if (logView == null || logLoading || !logHasMore) {
            return;
        }
        logLoading = true;
        requestedLogOffset = logNextOffset;
        int requestId = logRequestId;
        long offset = requestedLogOffset;
        logProgress.setText(offset == 0L ? "正在读取第一段…" : "正在加载更多…");
        sendBroadcast(ModuleStatusProtocol.command(ModuleStatusProtocol.ACTION_GET_LOGS)
                .putExtra(ModuleStatusProtocol.EXTRA_LOG_OFFSET, offset)
                .putExtra(ModuleStatusProtocol.EXTRA_LOG_REQUEST_ID, requestId));
        handler.postDelayed(() -> {
            if (logLoading && logRequestId == requestId && requestedLogOffset == offset) {
                logLoading = false;
                if (offset == 0L && logView.length() == 0) {
                    logView.setText("无法读取日志：豆包输入法进程未回应。\n"
                            + "请确认模块已激活并启动一次输入法。");
                }
                logProgress.setText("读取超时，点击“从头刷新”重试");
            }
        }, 1800L);
    }

    private void handleLogResult(Intent intent) {
        int requestId = intent.getIntExtra(ModuleStatusProtocol.EXTRA_LOG_REQUEST_ID, -1);
        if (requestId != logRequestId) {
            return;
        }
        logLoading = false;
        String path = intent.getStringExtra(ModuleStatusProtocol.EXTRA_LOG_PATH);
        String chunk = intent.getStringExtra(ModuleStatusProtocol.EXTRA_LOG_TEXT);
        logPath.setText("路径：" + safe(path) + "（每段约 16 KB）");
        if (requestedLogOffset == 0L) {
            logView.setText(safe(chunk));
        } else if (chunk != null && !chunk.isEmpty() && !"暂无日志".equals(chunk)) {
            logView.append(chunk);
        }
        logNextOffset = intent.getLongExtra(
                ModuleStatusProtocol.EXTRA_LOG_NEXT_OFFSET, logNextOffset);
        logHasMore = intent.getBooleanExtra(
                ModuleStatusProtocol.EXTRA_LOG_HAS_MORE, false);
        logProgress.setText(logHasMore ? "向下滚动自动加载更多" : "已加载全部日志");
        if (intent.getBooleanExtra(ModuleStatusProtocol.EXTRA_LOG_CLEARED, false)) {
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        }
        logScroll.post(() -> {
            View child = logScroll.getChildAt(0);
            if (logHasMore && child != null && child.getHeight() <= logScroll.getHeight()) {
                loadMoreLogs();
            }
        });
    }

    private void confirmClearLogs() {
        new AlertDialog.Builder(this)
                .setTitle("清空运行日志")
                .setMessage("将清空豆包输入法进程中的模块日志，此操作不可撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    logRequestId++;
                    logLoading = true;
                    requestedLogOffset = 0L;
                    logView.setText("正在清空日志…");
                    logProgress.setText("");
                    sendBroadcast(ModuleStatusProtocol.command(
                                    ModuleStatusProtocol.ACTION_CLEAR_LOGS)
                            .putExtra(ModuleStatusProtocol.EXTRA_LOG_REQUEST_ID, logRequestId));
                })
                .show();
    }

    private void registerStatusReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ModuleStatusProtocol.ACTION_REPORT);
        filter.addAction(ModuleStatusProtocol.ACTION_LOGS_RESULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            //noinspection UnspecifiedRegisterReceiverFlag
            registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void queryStatus() {
        receivedLiveStatus = false;
        targetResponding = false;
        setTargetStatus("检测中", colorSecondary, "正在等待实时回应…");
        sendBroadcast(ModuleStatusProtocol.command(ModuleStatusProtocol.ACTION_QUERY));
        handler.postDelayed(() -> {
            if (!receivedLiveStatus) {
                renderTargetNotResponding(true);
            }
        }, 1200L);
    }

    private void recheckFrameworkStatus() {
        frameworkChecking = true;
        frameworkCheckGeneration++;
        frameworkCheckStartedAt = System.currentTimeMillis();
        frameworkStatusText.setText("检测中");
        frameworkStatusText.setTextColor(colorSecondary);
        frameworkStatusDetail.setText("正在重新验证 libxposed 官方服务…");
        ModuleApplication.recheckXposedService();
    }

    private void renderFrameworkStatus() {
        XposedService service = ModuleApplication.getXposedService();
        if (service == null) {
            frameworkStatusText.setText("未激活");
            frameworkStatusText.setTextColor(colorDanger);
            frameworkStatusDetail.setText("未连接到 libxposed 官方服务");
            return;
        }
        try {
            List<String> scope = service.getScope();
            boolean targetInScope = scope != null
                    && scope.contains(ModuleStatusProtocol.TARGET_PACKAGE);
            frameworkStatusText.setText("已激活");
            frameworkStatusText.setTextColor(colorAccent);
            frameworkStatusDetail.setText(service.getFrameworkName() + " "
                    + service.getFrameworkVersion() + " · API " + service.getApiVersion()
                    + "\n豆包输入法作用域：" + (targetInScope ? "已勾选" : "未勾选"));
        } catch (Throwable t) {
            frameworkStatusText.setText("已激活");
            frameworkStatusText.setTextColor(colorAccent);
            frameworkStatusDetail.setText("框架服务已连接，详细信息读取失败");
        }
    }

    private void renderTargetNotResponding(boolean timedOut) {
        targetResponding = false;
        SharedPreferences prefs = statusPreferences();
        long lastSeen = prefs.getLong(ModuleStatusReceiver.KEY_LAST_SEEN, 0L);
        String detail = timedOut ? "本次检测未收到回应" : "尚未收到实时回应";
        if (lastSeen > 0L) {
            detail += "\n上次回应：" + formatTime(lastSeen);
        }
        setTargetStatus("未回应", colorDanger, detail);
        hookRuntime.setText("当前未获得豆包输入法进程的实时 Hook 状态");
        hookRuntime.setTextColor(colorSecondary);
    }

    private void renderLiveStatus(Intent intent) {
        receivedLiveStatus = true;
        targetResponding = true;
        boolean javaReady = intent.getBooleanExtra(
                ModuleStatusProtocol.EXTRA_JAVA_READY, false);
        boolean nativeReady = intent.getBooleanExtra(
                ModuleStatusProtocol.EXTRA_NATIVE_READY, false);
        int pid = intent.getIntExtra(ModuleStatusProtocol.EXTRA_PID, -1);
        long at = intent.getLongExtra(
                ModuleStatusProtocol.EXTRA_REPORTED_AT, System.currentTimeMillis());
        String build = intent.getStringExtra(ModuleStatusProtocol.EXTRA_HOOK_BUILD);
        setTargetStatus("已回应", colorAccent,
                "PID " + pid + "\n" + formatTime(at));
        renderHookRuntime(javaReady, nativeReady, pid, build);
        String path = intent.getStringExtra(ModuleStatusProtocol.EXTRA_LOG_PATH);
        if (path != null && !path.isEmpty()) {
            logPath.setText("路径：" + path);
        }
    }

    private void renderHookRuntime(boolean javaReady, boolean nativeReady, int pid, String build) {
        StringBuilder value = new StringBuilder();
        value.append(javaReady ? "● Java Hook 已加载" : "○ Java Hook 等待加载");
        value.append("\n");
        value.append(nativeReady ? "● Native Hook 已就绪" : "○ Native Hook 等待就绪");
        if (pid > 0) {
            value.append("\n进程 PID：").append(pid);
        }
        if (build != null && !build.isEmpty()) {
            value.append("　Hook 构建：").append(build);
        }
        hookRuntime.setText(value);
        hookRuntime.setTextColor(javaReady && nativeReady ? colorAccent : colorDanger);
    }

    private void setTargetStatus(String value, int color, String detail) {
        targetStatusText.setText(value);
        targetStatusText.setTextColor(color);
        targetStatusDetail.setText(detail);
    }

    private void renderTargetVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    ModuleStatusProtocol.TARGET_PACKAGE, 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            targetVersion.setText("版本名称：" + safe(info.versionName)
                    + "\n详细版本号（versionCode）：" + versionCode
                    + "\n包名：" + ModuleStatusProtocol.TARGET_PACKAGE);
            restartButton.setEnabled(true);
        } catch (PackageManager.NameNotFoundException e) {
            targetVersion.setText("未检测到豆包输入法\n包名："
                    + ModuleStatusProtocol.TARGET_PACKAGE);
            restartButton.setEnabled(false);
        }
    }

    private void renderModuleVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            aboutVersion.setText("版本\n" + safe(info.versionName)
                    + "（" + versionCode + "）");
        } catch (PackageManager.NameNotFoundException e) {
            aboutVersion.setText("版本\n未知");
        }
    }

    private void restartTarget() {
        restartButton.setEnabled(false);
        restartButton.setText("正在重启…");
        if (targetResponding) {
            sendBroadcast(ModuleStatusProtocol.command(ModuleStatusProtocol.ACTION_RESTART));
            Toast.makeText(this, "已请求豆包输入法进程自行重启", Toast.LENGTH_SHORT).show();
            finishRestartUi();
            return;
        }
        Toast.makeText(this, "正在请求超级用户权限，请在授权管理器中允许",
                Toast.LENGTH_LONG).show();
        restartTargetWithRoot();
    }

    private void restartTargetWithRoot() {
        new Thread(() -> {
            boolean success = false;
            try {
                String target = ModuleStatusProtocol.TARGET_PACKAGE;
                String component = target + "/.ImeService";
                String command = "old_pid=$(pidof " + target + "); "
                        + "if [ -n \"$old_pid\" ]; then kill -9 $old_pid || exit 10; fi; "
                        + "sleep 1; "
                        + "ime enable " + component + " >/dev/null 2>&1; "
                        + "ime set " + component + " >/dev/null 2>&1 || exit 11; "
                        + "i=0; while [ $i -lt 20 ]; do "
                        + "new_pid=$(pidof " + target + "); "
                        + "if [ -n \"$new_pid\" ] && [ \"$new_pid\" != \"$old_pid\" ]; "
                        + "then exit 0; fi; "
                        + "sleep 0.5; i=$((i+1)); done; exit 12";
                Process process = new ProcessBuilder("su", "-c", command)
                        .redirectErrorStream(true)
                        .start();
                boolean finished = process.waitFor(30, TimeUnit.SECONDS);
                success = finished && process.exitValue() == 0;
                if (!finished) {
                    process.destroy();
                }
            } catch (Throwable ignored) {
                success = false;
            }
            boolean restarted = success;
            runOnUiThread(() -> {
                if (restarted) {
                    Toast.makeText(this, "已通过超级用户权限重启并重新绑定豆包输入法",
                            Toast.LENGTH_SHORT).show();
                    finishRestartUi();
                } else {
                    restartButton.setEnabled(true);
                    restartButton.setText("一键重启豆包输入法");
                    showManualRestartFallback();
                }
            });
        }, "doubao-root-restart").start();
    }

    private void finishRestartUi() {
        handler.postDelayed(() -> {
            restartButton.setEnabled(true);
            restartButton.setText("一键重启豆包输入法");
            queryStatus();
        }, 2200L);
    }

    private void showManualRestartFallback() {
        new AlertDialog.Builder(this)
                .setTitle("无法自动重启")
                .setMessage("未获得超级用户授权，普通应用不能终止其它应用的进程。"
                        + "Android 的 restartInput 只会重建输入会话，不能触发 LSPosed 首次注入。\n\n"
                        + "可前往应用详情手动强行停止豆包输入法；也可打开输入法选择器，"
                        + "先切换到其它输入法再切回豆包输入法，但系统可能保留原进程，"
                        + "因此这种方式不保证完成注入。")
                .setNegativeButton("取消", null)
                .setNeutralButton("选择输入法", (dialog, which) -> showInputMethodPicker())
                .setPositiveButton("打开应用详情", (dialog, which) -> {
                    Intent intent = new Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + ModuleStatusProtocol.TARGET_PACKAGE));
                    startActivity(intent);
                })
                .show();
    }

    private void showInputMethodPicker() {
        InputMethodManager manager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.showInputMethodPicker();
            Toast.makeText(this, "请先切换到其它输入法，再切回豆包输入法",
                    Toast.LENGTH_LONG).show();
        }
    }

    private SharedPreferences statusPreferences() {
        return getSharedPreferences(ModuleStatusReceiver.PREFS, Context.MODE_PRIVATE);
    }

    private LinearLayout pageContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(22), dp(18), dp(24));
        return content;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(colorCard, colorBorder, 16));
        return card;
    }

    private TextView sectionTitle(String value) {
        return text(value, 17f, colorPrimary, Typeface.BOLD);
    }

    private TextView infoRow(String label, String value) {
        TextView row = text(label + "\n" + value, 14f, colorPrimary, Typeface.NORMAL);
        row.setLineSpacing(dp(2), 1.12f);
        return row;
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? colorAccent : colorBorder));
        button.setTextColor(primary ? Color.WHITE : colorPrimary);
        return button;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private View space(int heightDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return view;
    }

    private LinearLayout.LayoutParams topMargin(int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(marginDp);
        return params;
    }

    private LinearLayout.LayoutParams fixedHeightMargin(int heightDp, int marginTopDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp));
        params.topMargin = dp(marginTopDp);
        return params;
    }

    private LinearLayout.LayoutParams cardMargin() {
        return topMargin(14);
    }

    private LinearLayout.LayoutParams buttonMargin() {
        return fixedHeightMargin(48, 14);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String hookDescription() {
        return "以下修改只在检测到豆包输入法处于英文键盘或英文输入模式时生效。"
                + "中文键盘、中文拼音预编辑、中文候选和中文联想均继续调用输入法原始逻辑。\n\n"

                + "1. 英文状态识别与行为状态读取\n"
                + "涉及位置：InputModel::GetInputMode、WindowBoardView::GetBoardType、"
                + "WindowBoardView::GetKeyboardBehavior。\n"
                + "原始功能：这些方法分别提供输入引擎当前语言模式、正在显示的键盘板型，"
                + "以及按键应当采用直接输入还是进入组合输入流程的行为状态。\n"
                + "调整逻辑：模块同时检查 inputMode 和 boardType，而不是只依赖某一个 Java 布尔值，"
                + "避免中英切换过程中状态更新顺序不同造成误判。keyboard_behavior 的字段偏移"
                + "从当前版本函数指令中动态解析；后续所有拦截均以此英文状态为入口条件，"
                + "不是英文状态时立即执行原方法。\n\n"

                + "2. 普通英文字母按键的直接上屏\n"
                + "涉及位置：ButtonEnglishChar::OnButtonUp、ButtonChar::OnButtonUp、"
                + "English26Layout::OnButtonEnglishCharClicked。\n"
                + "原始功能：按下阶段的 CommitInput 会把字符送入英文组词模型；抬起阶段的"
                + " OnButtonUp 负责按键气泡收尾、长按选择结果以及单次 Shift 输入后的自动回落。"
                + "布局点击回调同步更新大小写状态和按键标签。\n"
                + "调整逻辑：模块在 ButtonChar::CommitInput 源头阻止字符进入 OIME 组词缓冲。"
                + "普通文本框在源头被消费后原始 OnButtonUp 不会自行上屏，因此 behavior=1"
                + "按键先从按键对象取得当前单字符并通过受控 CommitString 提交，随后仍继续"
                + "执行原始 OnButtonUp 和布局点击尾链，以同时保留直输、气泡收尾和单次 Shift 回落。"
                + "密码框例外：原 OnButtonUp 会先调用当前输入框的 IsPasswordBox，为真且"
                + "behavior=1 时自己 CommitString。此时模块不再预提交，只走原尾链，避免重复上屏。"
                + "长按 behavior=5 不提前提交，完整交给原始 Move/Up 滑选链；多字符英文整词提交仍被拦截。"
                + "底层 keycode 直提只作为版本差异兜底，并使用短时间标记避免重复提交。\n\n"

                + "3. 阻断字符进入英文组词缓冲区\n"
                + "涉及位置：ButtonChar::CommitInput、BoardController::CommitKeycode、"
                + "BoardController::PushCommitKeycode、InputModel::CommitKeycode、"
                + "InputModel::PushCommitKeycode、InputModel::Impl::Input、"
                + "InputModel::OnUpdateEnglish26PreCommit。\n"
                + "原始功能：这些方法位于按键输入到英文引擎之间的不同层级，负责把 keycode、"
                + "字符及上下文压入模型，累计形成单词，并触发英文 pre-commit、纠错和候选计算。\n"
                + "调整逻辑：英文模式下不再让普通字母进入累计组词流程。能够可靠转换成单个 ASCII"
                + "字符的 keycode 直接提交；已经由上层完成提交的调用直接消费；"
                + "英文预提交更新被停止并随后清理状态。这样即使候选栏已经隐藏，"
                + "引擎内部也不会继续保存一个看不见的 test 之类的完整单词。\n\n"

                + "4. Native 预编辑产生与结束流程\n"
                + "涉及位置：BoardController::UpdatePreedit、BoardController::FinishPreedit、"
                + "KeyboardCallbackImpl::UpdatePreedit。\n"
                + "原始功能：UpdatePreedit 把引擎中的组合文本送往回调层和编辑器，"
                + "用于显示英文下划线、组合串和光标；FinishPreedit 在空格、切换语言、"
                + "结束输入等场景决定提交还是丢弃这段组合文本。\n"
                + "调整逻辑：非空英文预编辑在 Native 层直接吞掉，不允许继续写入编辑器；"
                + "空字符串仍然放行，因为它用于清除编辑器中已经存在的 composing 状态。"
                + "若版本特定路径仍抵达 KeyboardCallbackImpl::UpdatePreedit，模块只救援"
                + "累计串最后一个尚未直提的 ASCII 字符，并在豆包输入法自身的 Native"
                + " 回调栈内执行 InputModel::Clear；这样既立即复位底层引擎，又避免从模块"
                + " JNI ClassLoader 调用清理导致崩溃。"
                + "结束英文预编辑时强制使用 discard 语义，而不是把隐藏缓冲区作为一个单词提交。"
                + "只有在 startInputView 已完成后才执行需要 InputView 的清理，"
                + "避免输入法初始化阶段访问尚未建立的对象。\n\n"

                + "5. Java 预编辑末端保护\n"
                + "涉及位置：KeyboardJni.UpdatePreedit、KeyboardJni.UpdateCompStr、"
                + "KeyboardJni.finishPreedit。\n"
                + "原始功能：这些 JNI 方法是 Native 输入引擎到 Android InputConnection"
                + "之间的最终桥梁，负责更新 composing 文本、组合字符串以及结束预编辑时是否提交。\n"
                + "调整逻辑：英文非空 UpdatePreedit 和 UpdateCompStr 不再进入 InputConnection；"
                + "清除预编辑使用的空串继续执行。Java 层不再根据累计文本差值或全局递增序号"
                + "补交字符，因为异步回调与序号没有一一对应关系，补交会让编辑器正文和 Native"
                + " 词态分叉，并可能把下一条回调误认为上一条。若 unique-mode Native 源头拦截后"
                + "仍收到非空英文 preedit，Java 只吞掉并记录异常，不自行制造新的提交。"
                + "finishPreedit 的 commit 参数在英文状态下改为 discard，"
                + "防止结束输入时冲出整词。\n\n"

                + "6. 整词提交出口与重复提交去重\n"
                + "涉及位置：InputModel::Impl::CommitString、BoardController::CommitString、"
                + "KeyboardCallbackImpl::DoCommit、KeyboardJni.commitString、KeyboardJni.DoCommit。\n"
                + "原始功能：这些方法把引擎已经形成的字符串最终写入编辑器，"
                + "正常用于候选选择、空格确认、标点处理以及切换语言时提交剩余组合文本。\n"
                + "调整逻辑：英文状态下，未经模块明确授权的多字符字母数字提交会被拦截，"
                + "只有当前按键的单字符直接提交或受控符号提交可以通过。Java 和 Native 两端"
                + "都会阻止残留词态以多字符字母数字形式整体提交；中文提交和非英文文本仍走原流程。\n\n"

                + "7. 英文候选栏刷新链路\n"
                + "涉及位置：CandidateToolbarCenter::UpdateCandidateDisplay、"
                + "UpdateCandidate、OnAssociated、UpdateComposition，"
                + "CandidateRefreshManager::NotifyRefreshListener、NotifyCommitStringListeners，"
                + "CandidateContainerCenter::BuildAndPushAndroidSnapshot，"
                + "CandidateCompositionCenter::UpdateComp。\n"
                + "原始功能：这些组件从输入模型取得候选、纠错或联想结果，刷新候选工具栏，"
                + "构建 Android 侧候选快照，并更新候选区中的组合文本。\n"
                + "调整逻辑：英文状态下从候选生成、刷新通知、组合更新到最终 Android 快照全部停止，"
                + "并把 CandidateToolbarCenter、CandidateRefreshManager 和 CorrectionManager"
                + "恢复到空闲或清空状态。这样不仅隐藏现有候选，也阻止云候选、延迟刷新或"
                + "其它异步结果稍后重新出现。\n\n"

                + "8. Java 候选显示最终出口\n"
                + "涉及位置：KeyboardJni.notifyCandidateBarSnapshot、"
                + "KeyboardJni.notifyMoreCandidateSnapshot、KeyboardJni.ShouldShowCandidateInfo。\n"
                + "原始功能：前两个方法把普通候选栏和更多候选面板的快照真正送入 Android UI，"
                + "ShouldShowCandidateInfo 决定当前是否展示候选信息区域。\n"
                + "调整逻辑：英文状态下所有同名 snapshot 重载均直接返回，"
                + "ShouldShowCandidateInfo 固定返回 false。这一层作为 UI 末端兜底，"
                + "覆盖未经过部分 Native CandidateToolbar 回调的云候选和延迟候选。"
                + "中文状态下快照和显示判断不变。\n\n"

                + "9. 英文联想词和关联词计算\n"
                + "涉及位置：InputModel::Associate、InputModel::Impl::Associate 的两个重载、"
                + "InputModel::AssociateSelectText、InputModel::Impl::AssociateSelectText、"
                + "InputModel::OnAssociate、BoardController::Associate、"
                + "KeyboardCallbackImpl::NotifyUpdateAssociations。\n"
                + "原始功能：这些方法根据已输入或已提交文本请求下一词预测，处理选中的关联文本，"
                + "并通知候选栏刷新联想结果。\n"
                + "调整逻辑：英文状态下关联请求、选择关联文本、关联完成回调和刷新通知均不再继续，"
                + "同时设置跳过 association 的状态，避免刚提交一个字母后又以该字母为上下文"
                + "生成英文单词或短语。中文模式仍保留输入法原有的中文联想。\n\n"

                + "10. Java 联想接口\n"
                + "涉及位置：KeyboardJni.NotifyUpdateAssociations、getAssociations、"
                + "doAssociations、isAssociate。\n"
                + "原始功能：这些接口请求关联词数组、返回当前关联状态，"
                + "并把 Native 关联结果通知到 Java 层。\n"
                + "调整逻辑：英文状态下关联数组返回空数组、关联状态返回 false、"
                + "关联刷新通知不执行。模块不再调用或 Hook 全局 setAssociationEnabled，"
                + "避免英文阶段的关闭值残留到中文；切换到中文后所有联想接口立即走豆包输入法原逻辑。\n\n"

                + "11. 光标、选区、纠错和预编辑状态同步\n"
                + "涉及位置：English26Layout::OnSelectionUpdated、"
                + "KeyboardJni.onSelectionUpdated 及 SelectionUpdatedParams。\n"
                + "原始功能：用户移动光标、删除文本或编辑选区时，输入法会同步 selection，"
                + "并可能根据光标附近文本重新触发纠错、预编辑恢复和关联词计算。\n"
                + "调整逻辑：选区同步本身仍然执行，保证光标和编辑器状态正常；"
                + "但英文参数中的 need_association、enable_correct、has_preedit 被关闭，"
                + "pre_edit_text 被清空，并设置禁用光标变化关联的标记。"
                + "Native 英文选区回调也保留原始执行，以维持布局、光标和 Shift 状态；"
                + "它产生的英文关联和候选刷新由各自的专用 Hook 拦截，不再跳过整个 UI 回调。\n\n"

                + "12. 空格键与退格键\n"
                + "涉及位置：ButtonSpace::OnButtonUp、ButtonBackspace::OnButtonDown、"
                + "ButtonBackspace::ShowUpClear、"
                + "ButtonBackspace::OnButtonUp、KeyboardJni.ClearText、"
                + "KeyboardJni.ClearTextBeforeCursor、InputBoxScreenModel::UpClear、"
                + "InputBoxTranslateModel::UpClear、Jni_DoUpClearAction。\n"
                + "原始功能：英文组词状态下，空格通常先确认当前单词再输入空格；"
                + "普通退格通常优先删除预编辑缓冲区中的字符，而上滑退格会先判断是否仍有组合文本，"
                + "组合文本为空时才继续清除编辑器正文。\n"
                + "调整逻辑：英文模式下先清空隐藏引擎缓冲区、丢弃预编辑并清除候选，"
                + "再调用按键原方法。因此空格不会先冲出一个隐藏单词，退格也不会只删除"
                + "不可见的 preedit，而是按编辑器中的实际已上屏内容工作。上滑清除在"
                + "OnButtonDown 手势起点和 ShowUpClear 手势确认点分两层提前丢弃不可见的英文镜像，"
                + "并在输入框 UpClear 最终入口检查 IsTyping 前再次同步清除；"
                + "Jni_DoUpClearAction 入口同时作为其他版本可能存在的分发路径保护。"
                + "此外在 BoardController::CommitKeycode 和 PushCommitKeycode 增加稳定的源头闸门，"
                + "弥补部分版本通过虚表或尾调用绕过 ButtonChar、InputModel 符号入口的情况。"
                + "ClearText 完成后同步清除 Java 侧的异常预编辑诊断状态。"
                + "调用完成后再次标记跳过联想并恢复正常键盘行为。\n\n"

                + "13. 长按、上滑字符和符号提交\n"
                + "涉及位置：ButtonEnglishPushCommit::OnButtonUp、"
                + "ButtonChar::OnButtonLongPress、BoardController::CommitAppendSymbol、"
                + "BoardController::CommitSymbol。\n"
                + "原始功能：这些入口负责按键上滑字符、长按备选字符，以及标点符号的追加或直接提交；"
                + "部分路径会把符号附加到正在组合的英文单词后再整体提交。\n"
                + "调整逻辑：长按原函数设置的 keyboard_behavior=5 会一直保留到 Move/Up"
                + " 完成气泡滑选，模块不在 LongPress 回调后提前重置行为状态、隐藏气泡或清候选。"
                + "最终保留用户实际选择的长按字符和符号输入，但不允许它们携带隐藏英文组合串。"
                + "符号通过临时授权的单次提交路径输出，提交前后清理英文词态、候选和联想，"
                + "避免输入标点时把之前看不见的字母一起上屏。\n\n"

                + "14. 中英文模式、键盘板型和保留组合状态\n"
                + "涉及位置：InputModel::SetInputMode、"
                + "WindowBoardView::SetBoardType(InputMode)、"
                + "WindowBoardView::SetBoardType(InputBoardType)、"
                + "ButtonSwitchChineseEnglish::OnButtonUp、ButtonSwitchBoard::OnButtonUp、"
                + "InputModel::SetKeepCompositionOnEnglishSwitch。\n"
                + "原始功能：这些方法负责切换中英文输入模式和键盘布局，并可在切换时保留、"
                + "迁移或提交当前 composition，以便输入法延续未完成的单词或拼音。\n"
                + "调整逻辑：离开英文模式或切换板型前，先清除英文引擎缓冲和预编辑，"
                + "防止原方法在切换过程中提交隐藏整词；只有切换结果仍为英文时才继续清理英文候选。"
                + "KeepComposition 也只在当前英文状态下强制为 false。切回中文后不再执行"
                + " FinishPreedit、MarkSkip 或候选清理，避免吞掉第一条中文联想。\n\n"

                + "15. 输入视图生命周期与安全清理时机\n"
                + "涉及位置：KeyboardJni.startInputView、onFinishInputView、"
                + "onFinishInput、finishInputView。\n"
                + "原始功能：这些回调建立或释放当前 EditorInfo、InputConnection 和输入视图，"
                + "输入法也会在其中初始化候选、关联和预编辑状态。\n"
                + "调整逻辑：startInputView 成功后才把 Native 清理标记为可用，"
                + "结束输入或关闭输入视图时撤销该标记，"
                + "避免初始化早期或销毁后调用 FinishPreedit 导致空对象异常。"
                + "模块不会伪造密码框状态，也不绕过输入法原有的真实密码输入判断。\n\n"

                + "16. 版本兼容、符号解析和安装完整性\n"
                + "涉及位置：libkeyboard.so 的 ELF .dynsym 解析、ShadowHook 安装、"
                + "keyboard_behavior 偏移动态发现。\n"
                + "原始功能：这些属于模块注入层，用于定位豆包输入法 Native C++ 方法，"
                + "并不替代输入法自身业务逻辑。\n"
                + "调整逻辑：模块从当前进程 maps 获取实际 libkeyboard 基址，"
                + "再解析当前 APK 中 SO 的动态符号，而不是依赖固定函数地址；"
                + "当前单字符字段和 keyboard_behavior 字段通过目标函数指令动态推导，"
                + "并保留已知版本回退值。"
                + "ShadowHook 固定使用 unique 模式：本模块对每个地址只安装一个代理，"
                + "代理通过 orig trampoline 调用原方法。早期 shared 模式实现没有在代理返回前"
                + "弹出 ShadowHook 内部调用栈，导致同一 Hook 通常只命中一次，后续按键被误判为"
                + "循环重入而绕过；大小写和长按后的隐藏 composition 均由此产生。"
                + "直接输入、预编辑、整词提交、候选快照和关联链路中的关键 Hook 必须全部安装成功"
                + "才报告 Native Hook 已就绪；可选版本差异点缺失时记录状态但不影响中文输入。";
    }

    private String formatTime(long timestamp) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(timestamp));
    }

    private String safe(String value) {
        return value == null || value.isEmpty() ? "未知" : value;
    }

    private void configureColors() {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        colorBackground = Color.parseColor(night ? "#0F1216" : "#F3F5F7");
        colorCard = Color.parseColor(night ? "#1A1F25" : "#FFFFFF");
        colorInner = Color.parseColor(night ? "#11151A" : "#F7F8FA");
        colorPrimary = Color.parseColor(night ? "#F1F4F7" : "#18202A");
        colorSecondary = Color.parseColor(night ? "#A8B1BC" : "#66717E");
        colorAccent = Color.parseColor("#20A464");
        colorDanger = Color.parseColor("#D84A4A");
        colorBorder = Color.parseColor(night ? "#343B44" : "#E0E5EA");
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ModuleStatusProtocol.isTrusted(intent)
                    || !ModuleStatusProtocol.TARGET_PACKAGE.equals(
                    intent.getStringExtra(ModuleStatusProtocol.EXTRA_TARGET_PACKAGE))) {
                return;
            }
            if (ModuleStatusProtocol.ACTION_REPORT.equals(intent.getAction())) {
                renderLiveStatus(intent);
                return;
            }
            if (ModuleStatusProtocol.ACTION_LOGS_RESULT.equals(intent.getAction())) {
                handleLogResult(intent);
            }
        }
    };
}
