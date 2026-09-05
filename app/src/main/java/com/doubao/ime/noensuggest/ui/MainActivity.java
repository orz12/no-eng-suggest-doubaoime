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
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
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
    private TextView hookVersionNote;
    private TextView hookMismatchNote;
    private TextView hookWarning;
    private FlowLayout hookTagFlow;
    private LinearLayout hookSectionsHost;
    private final List<View> hookSectionBodies = new ArrayList<>();
    private final List<TextView> hookSectionHeaders = new ArrayList<>();
    private final List<FlowLayout> hookSectionStatusHosts = new ArrayList<>();
    private int expandedHookSection = -1;
    private TextView tagJava;
    private TextView tagNative;
    private TextView tagInstall;
    private TextView tagSkip;
    private TextView tagFail;
    private Map<String, String> lastHookStatus = new HashMap<>();
    private long lastBehaviorOff;
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
        hookVersionNote = text("模块版本：等待目标进程回报", 12f, colorSecondary, Typeface.NORMAL);
        hookCard.addView(hookVersionNote, topMargin(6));
        hookMismatchNote = text("", 12f, colorDanger, Typeface.NORMAL);
        hookMismatchNote.setVisibility(View.GONE);
        hookMismatchNote.setLineSpacing(dp(2), 1.15f);
        hookCard.addView(hookMismatchNote, topMargin(4));
        hookTagFlow = new FlowLayout(this);
        buildHookTags(hookTagFlow);
        hookCard.addView(hookTagFlow, topMargin(10));
        hookWarning = text("", 12f, colorDanger, Typeface.NORMAL);
        hookWarning.setVisibility(View.GONE);
        hookWarning.setLineSpacing(dp(2), 1.15f);
        hookCard.addView(hookWarning, topMargin(8));
        // Accordion: tap a numbered item to expand. Colored title dots summarize status.
        // Intentionally not shown as on-screen tip text.
        hookSectionsHost = new LinearLayout(this);
        hookSectionsHost.setOrientation(LinearLayout.VERTICAL);
        buildHookSections(hookSectionsHost);
        hookCard.addView(hookSectionsHost, topMargin(10));
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
                        + "并阻断普通输入场景中的英文预编辑、候选词、联想词与切换语言时的重复提交。\n\n"
                        + "翻译面板英文框仍支持直输与手势大写/符号，同时避免刷新路径死锁与"
                        + "隐式词态残留。中文拼音、中文候选与中文联想保持输入法原有行为。",
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
        TextView repoRow = linkRow("仓库",
                "https://github.com/orz12/no-eng-suggest-doubaoime");
        infoCard.addView(repoRow, topMargin(8));
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
        renderHookPanel(false, false, null, null, 0, 0, 0, null, null, null, 0L);
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
        String detail = intent.getStringExtra(ModuleStatusProtocol.EXTRA_RUNTIME_DETAIL);
        int hookOk = intent.getIntExtra(ModuleStatusProtocol.EXTRA_HOOK_OK, 0);
        int hookTotal = intent.getIntExtra(ModuleStatusProtocol.EXTRA_HOOK_TOTAL, 0);
        int hookFail = intent.getIntExtra(ModuleStatusProtocol.EXTRA_HOOK_FAIL, 0);
        String failNames = intent.getStringExtra(ModuleStatusProtocol.EXTRA_HOOK_FAIL_NAMES);
        String skipNames = intent.getStringExtra(ModuleStatusProtocol.EXTRA_HOOK_SKIP_NAMES);
        String statusMap = intent.getStringExtra(ModuleStatusProtocol.EXTRA_HOOK_STATUS_MAP);
        long behaviorOff = intent.getLongExtra(ModuleStatusProtocol.EXTRA_BEHAVIOR_OFFSET, 0L);
        setTargetStatus("已回应", colorAccent,
                "PID " + pid + "\n" + formatTime(at));
        renderHookPanel(javaReady, nativeReady, build, detail,
                hookOk, hookTotal, hookFail, failNames, skipNames, statusMap, behaviorOff);
        String path = intent.getStringExtra(ModuleStatusProtocol.EXTRA_LOG_PATH);
        if (path != null && !path.isEmpty()) {
            logPath.setText("路径：" + path);
        }
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

    private TextView linkRow(String label, String url) {
        TextView row = text(label + "\n" + url, 14f, colorPrimary, Typeface.NORMAL);
        row.setLineSpacing(dp(2), 1.12f);
        row.setAutoLinkMask(Linkify.WEB_URLS);
        row.setText(label + "\n" + url);
        row.setLinksClickable(true);
        row.setLinkTextColor(colorAccent);
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

    private void buildHookTags(FlowLayout flow) {
        flow.removeAllViews();
        tagJava = createHookTag("Java …", colorSecondary);
        tagNative = createHookTag("Native …", colorSecondary);
        tagInstall = createHookTag("ShadowHook …", colorSecondary);
        tagSkip = createHookTag("未识别 …", colorSecondary);
        tagFail = createHookTag("失败 …", colorDanger);
        tagSkip.setVisibility(View.GONE);
        tagFail.setVisibility(View.GONE);
        tagInstall.setOnClickListener(v -> expandOverview());
        tagSkip.setOnClickListener(v -> expandOverview());
        tagFail.setOnClickListener(v -> expandOverview());
        flow.addView(tagJava);
        flow.addView(tagNative);
        flow.addView(tagInstall);
        flow.addView(tagSkip);
        flow.addView(tagFail);
    }

    private void expandOverview() {
        expandHookSection(hookSectionBodies.size() - 1);
    }

    private TextView createHookTag(String label, int color) {
        TextView tag = text(label, 12f, color, Typeface.BOLD);
        tag.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorInner);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), colorBorder);
        tag.setBackground(bg);
        return tag;
    }

    private TextView createStatusChip(String label, int color) {
        TextView tag = text(label, 11f, color, Typeface.NORMAL);
        tag.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(softColor(color));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), color);
        tag.setBackground(bg);
        return tag;
    }

    private void styleHookTag(TextView tag, String label, int color, boolean emphasis) {
        if (tag == null) {
            return;
        }
        tag.setText(label);
        tag.setTextColor(color);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(emphasis ? softColor(color) : colorInner);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), emphasis ? color : colorBorder);
        tag.setBackground(bg);
    }

    private int softColor(int color) {
        return Color.argb(36, Color.red(color), Color.green(color), Color.blue(color));
    }

    private Map<String, String> parseHookStatusMap(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return map;
        }
        for (String entry : raw.split(";")) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq >= entry.length() - 1) {
                continue;
            }
            map.put(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
        }
        return map;
    }

    private void renderHookPanel(boolean javaReady, boolean nativeReady, String build,
            String detail, int hookOk, int hookTotal, int hookFail, String failNames,
            String skipNames, String statusMap, long behaviorOff) {
        lastHookStatus = parseHookStatusMap(statusMap);
        lastBehaviorOff = behaviorOff;
        if (hookVersionNote != null) {
            hookVersionNote.setText(build == null || build.isEmpty()
                    ? "模块版本：未知"
                    : ("模块版本：" + build));
        }
        if (hookMismatchNote != null) {
            if (detail != null && !detail.isEmpty()) {
                hookMismatchNote.setVisibility(View.VISIBLE);
                hookMismatchNote.setText(detail);
            } else {
                hookMismatchNote.setVisibility(View.GONE);
                hookMismatchNote.setText("");
            }
        }
        styleHookTag(tagJava, javaReady ? "Java 已加载" : "Java 未加载",
                javaReady ? colorAccent : colorDanger, javaReady);
        styleHookTag(tagNative, nativeReady ? "Native 已就绪" : "Native 未就绪",
                nativeReady ? colorAccent : colorDanger, nativeReady);
        if (hookTotal > 0) {
            boolean installOk = hookFail == 0 && nativeReady;
            styleHookTag(tagInstall, "ShadowHook " + hookOk + "/" + hookTotal,
                    installOk ? colorAccent : colorDanger, true);
        } else {
            styleHookTag(tagInstall, "ShadowHook —", colorSecondary, false);
        }
        int skipCount = countCsv(skipNames);
        if (skipCount > 0) {
            tagSkip.setVisibility(View.VISIBLE);
            styleHookTag(tagSkip, "未识别 " + skipCount, Color.parseColor("#C9881C"), true);
        } else {
            tagSkip.setVisibility(View.GONE);
        }
        if (hookFail > 0) {
            tagFail.setVisibility(View.VISIBLE);
            styleHookTag(tagFail, "失败 " + hookFail, colorDanger, true);
        } else {
            tagFail.setVisibility(View.GONE);
        }

        StringBuilder warn = new StringBuilder();
        if (skipCount > 0 && skipNames != null && !skipNames.isEmpty()) {
            warn.append("未识别：").append(fullNameList(skipNames));
            warn.append("（可选符号在当前豆包版本中不存在，未挂钩；不属于失败）");
        }
        if (hookFail > 0 && failNames != null && !failNames.isEmpty()) {
            if (warn.length() > 0) {
                warn.append('\n');
            }
            warn.append("失败：").append(fullNameList(failNames));
        }
        if (warn.length() == 0) {
            hookWarning.setVisibility(View.GONE);
            hookWarning.setText("");
        } else {
            hookWarning.setVisibility(View.VISIBLE);
            hookWarning.setText(warn);
        }
        refreshHookSectionStatuses(javaReady);
    }

    private int countCsv(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String part : csv.split(",")) {
            if (!part.trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private String fullNameList(String names) {
        String[] parts = names.split(",");
        StringBuilder out = new StringBuilder();
        int limit = Math.min(parts.length, 8);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(parts[i].trim());
        }
        if (parts.length > limit) {
            out.append(" 等");
        }
        return out.toString();
    }


    private void buildHookSections(LinearLayout host) {
        hookSectionBodies.clear();
        hookSectionHeaders.clear();
        hookSectionStatusHosts.clear();
        expandedHookSection = -1;
        host.removeAllViews();
        HookSection[] sections = hookSections();
        for (int i = 0; i < sections.length; i++) {
            host.addView(createHookSection(i, sections[i], false),
                    i == 0 ? topMargin(0) : topMargin(8));
        }
        host.addView(createHookSection(sections.length, overviewSection(), true),
                topMargin(14));
    }

    private View createHookSection(int index, HookSection section, boolean overview) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(overview ? 12 : 10), dp(12), dp(overview ? 12 : 10));
        GradientDrawable bg = new GradientDrawable();
        if (overview) {
            bg.setColor(colorCard);
            bg.setCornerRadius(dp(10));
            bg.setStroke(dp(2), colorAccent);
        } else {
            bg.setColor(colorInner);
            bg.setCornerRadius(dp(12));
            bg.setStroke(dp(1), colorBorder);
        }
        box.setBackground(bg);

        TextView header = overview
                ? text(section.title, 12f, colorAccent, Typeface.BOLD)
                : text("● " + section.title, 13f, colorSecondary, Typeface.BOLD);
        header.setLineSpacing(0f, 1.15f);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(overview ? View.VISIBLE : View.GONE);
        if (section.blurb != null && !section.blurb.isEmpty()) {
            TextView blurb = text(section.blurb, 12f, colorSecondary, Typeface.NORMAL);
            blurb.setLineSpacing(dp(2), 1.18f);
            body.addView(blurb);
        }
        FlowLayout statusHost = new FlowLayout(this);
        body.addView(statusHost, topMargin(6));

        if (!overview) {
            header.setOnClickListener(v -> toggleHookSection(index));
            box.setOnClickListener(v -> toggleHookSection(index));
        }
        box.addView(header);
        box.addView(body, topMargin(8));

        hookSectionHeaders.add(header);
        hookSectionBodies.add(body);
        hookSectionStatusHosts.add(statusHost);
        return box;
    }

    private void toggleHookSection(int index) {
        if (index < 0 || index >= hookSectionBodies.size() - 1) {
            return;
        }
        if (expandedHookSection == index) {
            collapseHookSection(index);
            expandedHookSection = -1;
            return;
        }
        if (expandedHookSection >= 0 && expandedHookSection < hookSectionBodies.size() - 1) {
            collapseHookSection(expandedHookSection);
        }
        expandHookSection(index);
    }

    private void expandHookSection(int index) {
        if (index < 0 || index >= hookSectionBodies.size()) {
            return;
        }
        if (index == hookSectionBodies.size() - 1) {
            hookSectionBodies.get(index).setVisibility(View.VISIBLE);
            return;
        }
        if (expandedHookSection >= 0
                && expandedHookSection != index
                && expandedHookSection < hookSectionBodies.size() - 1) {
            collapseHookSection(expandedHookSection);
        }
        hookSectionBodies.get(index).setVisibility(View.VISIBLE);
        expandedHookSection = index;
    }

    private void collapseHookSection(int index) {
        if (index < 0 || index >= hookSectionBodies.size() - 1) {
            return;
        }
        hookSectionBodies.get(index).setVisibility(View.GONE);
    }

    private void refreshHookSectionStatuses(boolean javaReady) {
        HookSection[] sections = hookSections();
        for (int i = 0; i < sections.length && i < hookSectionHeaders.size(); i++) {
            HookSection section = sections[i];
            int summary = summarizeSection(section, javaReady, false);
            TextView header = hookSectionHeaders.get(i);
            header.setText("● " + section.title);
            header.setTextColor(summaryColor(summary));

            FlowLayout flow = hookSectionStatusHosts.get(i);
            flow.removeAllViews();
            // Explicit markers are declared on each section; never invent tags from emptiness.
            if (section.javaSide) {
                flow.addView(createStatusChip(
                        javaReady ? "Java 侧（随模块加载）" : "Java 侧未加载",
                        javaReady ? colorAccent : colorSecondary));
            }
            if (section.behaviorOffset) {
                if (lastBehaviorOff > 0L) {
                    flow.addView(createStatusChip(
                            "behavior偏移量 0x" + Long.toHexString(lastBehaviorOff),
                            colorAccent));
                } else {
                    flow.addView(createStatusChip(
                            javaReady ? "behavior偏移量 未解析" : "behavior偏移量 —",
                            javaReady ? colorDanger : colorSecondary));
                }
            }
            for (String hook : section.hooks) {
                String st = lastHookStatus.containsKey(hook)
                        ? lastHookStatus.get(hook) : "unknown";
                flow.addView(createStatusChip(formatHookChip(hook, st), statusColor(st)));
            }
        }

        int overviewIndex = hookSectionBodies.size() - 1;
        if (overviewIndex >= sections.length && overviewIndex < hookSectionHeaders.size()) {
            int summary = summarizeSection(overviewSection(), javaReady, true);
            TextView header = hookSectionHeaders.get(overviewIndex);
            header.setText(overviewSection().title);
            header.setTextColor(summaryColor(summary));
            FlowLayout flow = hookSectionStatusHosts.get(overviewIndex);
            flow.removeAllViews();
            if (lastHookStatus.isEmpty()) {
                flow.addView(createStatusChip("等待 ShadowHook 回报", colorSecondary));
            } else {
                boolean anyAbnormal = false;
                for (Map.Entry<String, String> e : lastHookStatus.entrySet()) {
                    String st = e.getValue();
                    if ("ok".equals(st)) {
                        continue;
                    }
                    anyAbnormal = true;
                    flow.addView(createStatusChip(
                            formatHookChip(e.getKey(), st), statusColor(st)));
                }
                if (!anyAbnormal) {
                    flow.addView(createStatusChip("全部成功", colorAccent));
                }
            }
        }
    }

    /** ok: name only; abnormal: name + status suffix. */
    private String formatHookChip(String name, String st) {
        if ("ok".equals(st)) {
            return name;
        }
        if ("skip".equals(st)) {
            return name + " · 未识别";
        }
        if ("fail".equals(st)) {
            return name + " · 失败";
        }
        return name + " · 未知";
    }

    /** 0 unknown, 1 ok, 2 skip, 3 fail */
    private int summarizeSection(HookSection section, boolean javaReady, boolean overview) {
        if (overview) {
            boolean hasFail = false;
            boolean hasSkip = false;
            boolean hasOk = false;
            for (String st : lastHookStatus.values()) {
                if ("fail".equals(st)) {
                    hasFail = true;
                } else if ("skip".equals(st)) {
                    hasSkip = true;
                } else if ("ok".equals(st)) {
                    hasOk = true;
                }
            }
            if (hasFail) {
                return 3;
            }
            if (hasSkip) {
                return 2;
            }
            return hasOk ? 1 : 0;
        }
        if (section.hooks.length == 0) {
            if (section.behaviorOffset) {
                return lastBehaviorOff > 0L ? 1 : 0;
            }
            if (section.javaSide) {
                return javaReady ? 1 : 0;
            }
            return 0;
        }
        boolean any = false;
        boolean hasFail = false;
        boolean hasSkip = false;
        boolean hasUnknown = false;
        for (String hook : section.hooks) {
            String st = lastHookStatus.get(hook);
            if (st == null) {
                hasUnknown = true;
                continue;
            }
            any = true;
            if ("fail".equals(st)) {
                hasFail = true;
            } else if ("skip".equals(st)) {
                hasSkip = true;
            }
        }
        if (hasFail) {
            return 3;
        }
        if (!any || hasUnknown) {
            return 0;
        }
        if (hasSkip) {
            return 2;
        }
        return 1;
    }

    private int summaryColor(int summary) {
        if (summary == 1) {
            return colorAccent;
        }
        if (summary == 2) {
            return Color.parseColor("#C9881C");
        }
        if (summary == 3) {
            return colorDanger;
        }
        return colorSecondary;
    }

    private String statusLabel(String st) {
        if ("ok".equals(st)) {
            return "";
        }
        if ("skip".equals(st)) {
            return "未识别";
        }
        if ("fail".equals(st)) {
            return "失败";
        }
        return "未知";
    }

    private int statusColor(String st) {
        if ("ok".equals(st)) {
            return colorAccent;
        }
        if ("skip".equals(st)) {
            return Color.parseColor("#C9881C");
        }
        if ("fail".equals(st)) {
            return colorDanger;
        }
        return colorSecondary;
    }

    private static final class HookSection {
        final String title;
        final String blurb;
        /** Explicit Java-side work; shown as its own tag, never inferred from empty hooks. */
        final boolean javaSide;
        /** Section reports keyboard_behavior offset discovery. */
        final boolean behaviorOffset;
        final String[] hooks;

        HookSection(String title, String blurb, boolean javaSide, boolean behaviorOffset,
                String... hooks) {
            this.title = title;
            this.blurb = blurb;
            this.javaSide = javaSide;
            this.behaviorOffset = behaviorOffset;
            this.hooks = hooks;
        }
    }

    private HookSection overviewSection() {
        return new HookSection(
                "ShadowHook 总览",
                "这里只列出异常的 ShadowHook 安装项，便于对照排查。"
                        + "「未识别」表示可选符号在当前豆包版本中不存在，不属于失败；"
                        + "「失败」表示挂钩没有成功。若全部正常，则显示「全部成功」。",
                false, false
        );
    }

    private HookSection[] hookSections() {
        // Hook names must match native do_hook() labels exactly.
        // javaSide / behaviorOffset are explicit markers for non-native-hook work.
        return new HookSection[]{
                new HookSection(
                        "1. 英文状态与 behavior 偏移",
                        "读取当前输入模式（inputMode）和键盘板型（boardType），判断是否处于英文输入。"
                                + "同时解析 keyboard_behavior 字段在键盘对象内存中的字节偏移。"
                                + "behavior 是键盘内部用来记录「当前按键姿态」的状态值，"
                                + "例如普通点按、长按弹出气泡、上滑输入等。"
                                + "模块在英文直接上屏后需要把它复位，因此必须知道这个偏移。"
                                + "本项包含 Java 侧状态判断，以及 Native 侧偏移解析；"
                                + "解析失败时，长按或上滑之后的状态复位可能异常。",
                        true, true
                ),
                new HookSection(
                        "2. 英文字母直接上屏",
                        "拦截英文字母按键，改为把单个字符直接提交到输入框，而不是先进入组词缓冲。",
                        false, false,
                        "ButtonEnglishChar::OnButtonUp", "ButtonChar::OnButtonUp",
                        "ButtonChar::CommitInput", "English26Layout::OnButtonEnglishCharClicked"
                ),
                new HookSection(
                        "3. 阻断进入英文组词缓冲",
                        "阻止字母按键进入输入引擎的组词和预提交链路，避免生成隐藏的英文词态。",
                        false, false,
                        "BoardController::CommitKeycode", "BoardController::PushCommitKeycode",
                        "InputModel::CommitKeycode", "InputModel::PushCommitKeycode",
                        "InputModel::Impl::Input", "InputModel::OnUpdateEnglish26PreCommit"
                ),
                new HookSection(
                        "4. 翻译面板刷新防护",
                        "在翻译面板刷新路径上绕过 Clear，避免刷新与清理互相等待造成卡死。",
                        false, false,
                        "TranslateModel::DelayRefreshResponse"
                ),
                new HookSection(
                        "5. Native 预编辑",
                        "在非翻译场景下拦截英文 composing（预编辑串）；结束预编辑时改为丢弃，而不是提交。",
                        false, false,
                        "BoardController::FinishPreedit", "BoardController::UpdatePreedit",
                        "KeyboardCallbackImpl::UpdatePreedit"
                ),
                new HookSection(
                        "6. Java 预编辑末端",
                        "在 Java 侧拦截 UpdatePreedit 与 finishPreedit，作为预编辑链路的最后一道保护。",
                        true, false
                ),
                new HookSection(
                        "7. 整词与符号提交出口",
                        "拦截未经授权的多字符整词提交；符号按受控方式单次提交，并在提交后清理残留词态。",
                        false, false,
                        "InputModel::Impl::CommitString", "BoardController::CommitString",
                        "BoardController::CommitAppendSymbol", "BoardController::CommitSymbol",
                        "KeyboardCallbackImpl::DoCommit"
                ),
                new HookSection(
                        "8. 英文候选栏",
                        "停止英文候选词的生成、刷新和推送，避免候选栏继续干扰直接上屏。",
                        false, false,
                        "CandidateToolbarCenter::UpdateCandidateDisplay",
                        "CandidateToolbarCenter::UpdateCandidate",
                        "CandidateToolbarCenter::OnAssociated",
                        "CandidateToolbarCenter::UpdateComposition",
                        "CandidateRefreshManager::NotifyRefreshListener",
                        "CandidateRefreshManager::NotifyCommitStringListeners",
                        "CandidateContainerCenter::BuildAndPushAndroidSnapshot",
                        "CandidateCompositionCenter::UpdateComp"
                ),
                new HookSection(
                        "9. Java 候选显示出口",
                        "拦截候选快照相关的 JNI 出口，阻止英文候选最终显示到界面。",
                        true, false
                ),
                new HookSection(
                        "10. 英文联想",
                        "停止英文联想的请求、选择和通知，避免联想结果继续出现。",
                        false, false,
                        "InputModel::Associate", "InputModel::Impl::Associate#1",
                        "InputModel::Impl::Associate#2", "InputModel::AssociateSelectText",
                        "InputModel::Impl::AssociateSelectText", "InputModel::OnAssociate",
                        "BoardController::Associate",
                        "KeyboardCallbackImpl::NotifyUpdateAssociations"
                ),
                new HookSection(
                        "11. 选区同步",
                        "保留光标和选区同步，同时关闭英文场景下联想、纠错等副作用。",
                        false, false,
                        "English26Layout::OnSelectionUpdated"
                ),
                new HookSection(
                        "12. 空格与退格",
                        "在空格或退格生效前，先清理引擎里隐藏的英文词态和预编辑，再执行原来的按键或上滑清除逻辑，避免需要多按一次。",
                        false, false,
                        "ButtonSpace::OnButtonUp", "ButtonBackspace::OnButtonDown",
                        "ButtonBackspace::ShowUpClear", "ButtonBackspace::OnButtonUp",
                        "Jni_DoUpClearAction", "InputBoxScreenModel::UpClear",
                        "InputBoxTranslateModel::UpClear"
                ),
                new HookSection(
                        "13. 长按与上滑字符",
                        "完整保留长按气泡和上滑输入的原有链路；在操作结束后清理可能残留的隐藏词态。",
                        false, false,
                        "ButtonEnglishPushCommit::OnButtonUp", "ButtonChar::OnButtonLongPress"
                ),
                new HookSection(
                        "14. 中英切换与保留组合",
                        "离开英文模式前先清理缓冲；禁止在切换到英文时保留 composition（组合串）。",
                        false, false,
                        "InputModel::SetInputMode",
                        "WindowBoardView::SetBoardType(InputMode)",
                        "WindowBoardView::SetBoardType(InputBoardType)",
                        "ButtonSwitchChineseEnglish::OnButtonUp",
                        "ButtonSwitchBoard::OnButtonUp",
                        "InputModel::SetKeepCompositionOnEnglishSwitch"
                ),
                new HookSection(
                        "15. 输入视图生命周期",
                        "在 startInputView / finishInputView 的时机控制清理动作，避免在输入视图尚未就绪时做不安全的清理。",
                        true, false
                ),
        };
    }

    /** Wrap children to next line when width is insufficient. */
    private static final class FlowLayout extends ViewGroup {
        private final int gapH;
        private final int gapV;

        FlowLayout(Context context) {
            super(context);
            float d = context.getResources().getDisplayMetrics().density;
            gapH = Math.round(8 * d);
            gapV = Math.round(8 * d);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int x = getPaddingLeft();
            int y = getPaddingTop();
            int rowH = 0;
            int limit = width - getPaddingRight();
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                int maxChildW = Math.max(0, width - getPaddingLeft() - getPaddingRight());
                int childWSpec = MeasureSpec.makeMeasureSpec(maxChildW, MeasureSpec.AT_MOST);
                int childHSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                child.measure(childWSpec, childHSpec);
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (x > getPaddingLeft() && x + cw > limit) {
                    x = getPaddingLeft();
                    y += rowH + gapV;
                    rowH = 0;
                }
                x += cw + gapH;
                rowH = Math.max(rowH, ch);
            }
            int height = y + rowH + getPaddingBottom();
            setMeasuredDimension(width, resolveSize(Math.max(height, getPaddingTop() + getPaddingBottom()),
                    heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int x = getPaddingLeft();
            int y = getPaddingTop();
            int rowH = 0;
            int limit = r - l - getPaddingRight();
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (x > getPaddingLeft() && x + cw > limit) {
                    x = getPaddingLeft();
                    y += rowH + gapV;
                    rowH = 0;
                }
                child.layout(x, y, x + cw, y + ch);
                x += cw + gapH;
                rowH = Math.max(rowH, ch);
            }
        }
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
