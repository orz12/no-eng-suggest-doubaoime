package com.doubao.ime.noensuggest;

import android.util.Log;
import android.app.Application;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedModule;

/**
 * 双通道日志：LSPosed 框架日志 + 落盘文件（便于 PC 侧 adb pull 读取）。
 */
final class FileLogger {
    private static final AtomicReference<File> LOG_FILE = new AtomicReference<>();
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
    private static final Object LOCK = new Object();

    /** 优先路径：IME 进程实测可写。 */
    static final String PRIMARY_PATH = "/sdcard/Download/DoubaoNoEnSuggest.log";
    static final String FALLBACK_PATH = "/data/local/tmp/DoubaoNoEnSuggest.log";

    private FileLogger() {
    }

    static void init(XposedModule module) {
        File primary = new File(PRIMARY_PATH);
        if (ensureWritable(primary)) {
            LOG_FILE.set(primary);
            i(module, "FileLogger ready path=" + primary.getAbsolutePath());
            return;
        }
        File fallback = new File(FALLBACK_PATH);
        if (ensureWritable(fallback)) {
            LOG_FILE.set(fallback);
            i(module, "FileLogger ready path=" + fallback.getAbsolutePath());
            return;
        }
        module.log(Log.WARN, ModuleMain.TAG,
                "FileLogger: no writable path (" + PRIMARY_PATH + " / " + FALLBACK_PATH + ")");
    }

    static void setEnabled(boolean enabled) {
        ENABLED.set(enabled);
    }

    static boolean isEnabled() {
        return ENABLED.get();
    }

    private static boolean ensureWritable(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write(new byte[0]);
            }
            // 尽量让 adb shell/pull 可读
            file.setReadable(true, false);
            file.setWritable(true, false);
            return file.canWrite();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void iRaw(String msg) {
        write(null, Log.INFO, msg, null);
    }

    static void i(XposedModule module, String msg) {
        write(module, Log.INFO, msg, null);
    }

    static void w(XposedModule module, String msg) {
        write(module, Log.WARN, msg, null);
    }

    static void e(XposedModule module, String msg, Throwable t) {
        write(module, Log.ERROR, msg, t);
    }

    private static void write(XposedModule module, int level, String msg, Throwable t) {
        if (!ENABLED.get()) {
            return;
        }
        if (module != null) {
            if (t != null) {
                module.log(level, ModuleMain.TAG, msg, t);
            } else {
                module.log(level, ModuleMain.TAG, msg);
            }
        } else if (LOG_FILE.get() == null) {
            // 尚未 init 时，先直接写主路径
            ensureWritable(new File(PRIMARY_PATH));
            LOG_FILE.compareAndSet(null, new File(PRIMARY_PATH));
        }
        File file = LOG_FILE.get();
        if (file == null) {
            return;
        }
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String levelName = levelName(level);
        StringBuilder line = new StringBuilder(ts)
                .append(' ').append(levelName)
                .append(" [pid=").append(Process.myPid())
                .append(" tid=").append(Process.myTid())
                .append(" proc=").append(processName())
                .append("] ").append(msg);
        if (t != null) {
            line.append('\n').append(Log.getStackTraceString(t));
        }
        line.append('\n');
        synchronized (LOCK) {
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write(line.toString());
            } catch (Throwable ignored) {
                // 落盘失败不影响输入法
            }
        }
    }

    private static String processName() {
        try {
            String name = Application.getProcessName();
            return name == null ? "?" : name;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String levelName(int level) {
        switch (level) {
            case Log.WARN:
                return "W";
            case Log.ERROR:
                return "E";
            case Log.DEBUG:
                return "D";
            default:
                return "I";
        }
    }

    static String readTail(int maxBytes) {
        File file = LOG_FILE.get();
        if (file == null) {
            file = new File(PRIMARY_PATH);
        }
        if (!file.exists()) {
            return "暂无日志";
        }
        synchronized (LOCK) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long length = raf.length();
                int count = (int) Math.min(Math.max(maxBytes, 1024), length);
                raf.seek(Math.max(0L, length - count));
                byte[] data = new byte[count];
                raf.readFully(data);
                String text = new String(data, StandardCharsets.UTF_8);
                if (length > count) {
                    return "…仅显示最后 " + count / 1024 + " KB…\n" + text;
                }
                return text.isEmpty() ? "暂无日志" : text;
            } catch (Throwable t) {
                return "读取日志失败：" + t.getMessage();
            }
        }
    }

    static LogChunk readChunk(long offset, int maxBytes) {
        File file = LOG_FILE.get();
        if (file == null) {
            file = new File(PRIMARY_PATH);
        }
        if (!file.exists()) {
            return new LogChunk("暂无日志", 0L, false);
        }
        synchronized (LOCK) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long length = raf.length();
                long start = Math.max(0L, Math.min(offset, length));
                raf.seek(start);
                int target = Math.max(4096, maxBytes);
                ByteArrayOutputStream out = new ByteArrayOutputStream(target + 512);
                int value;
                while ((value = raf.read()) != -1) {
                    out.write(value);
                    if (out.size() >= target && value == '\n') {
                        break;
                    }
                    if (out.size() >= target + 64 * 1024) {
                        break;
                    }
                }
                long next = raf.getFilePointer();
                String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                if (text.isEmpty() && start == 0L) {
                    text = "暂无日志";
                }
                return new LogChunk(text, next, next < length);
            } catch (Throwable t) {
                return new LogChunk("读取日志失败：" + t.getMessage(), offset, false);
            }
        }
    }

    static boolean clear() {
        File file = LOG_FILE.get();
        if (file == null) {
            file = new File(PRIMARY_PATH);
        }
        synchronized (LOCK) {
            try (FileOutputStream ignored = new FileOutputStream(file, false)) {
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    static String currentPath() {
        File f = LOG_FILE.get();
        return f == null ? "(none)" : f.getAbsolutePath();
    }

    static final class LogChunk {
        final String text;
        final long nextOffset;
        final boolean hasMore;

        LogChunk(String text, long nextOffset, boolean hasMore) {
            this.text = text;
            this.nextOffset = nextOffset;
            this.hasMore = hasMore;
        }
    }
}
