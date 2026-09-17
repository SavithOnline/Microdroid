package com.example.screenshotmover.plugin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.telephony.SmsManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.example.screenshotmover.trigger.MicrodroidAccessibilityService;

/** File-ops implementation of PluginContext, scoped per plugin id for logs/prefs. */
public class PluginContextImpl implements PluginContext {

    private final Context app;
    private final String pluginId;
    private String torchId;
    private boolean torchWarned;

    public PluginContextImpl(Context ctx, String pluginId) {
        this.app = ctx.getApplicationContext();
        this.pluginId = pluginId;
    }

    @Override
    public void log(String msg) {
        PluginManager.appendLog(app, pluginId, msg == null ? "null" : msg);
    }

    @Override
    public String[] list(String dirPath) {
        try {
            File d = new File(dirPath);
            String[] fs = d.list();
            return fs == null ? null : fs.clone();
        } catch (Exception e) {
            return null;
        }
    }

    @Override public boolean exists(String path) {
        try { return new File(path).exists(); } catch (Exception e) { return false; }
    }

    @Override public boolean isFile(String path) {
        try { return new File(path).isFile(); } catch (Exception e) { return false; }
    }

    @Override public boolean isDirectory(String path) {
        try { return new File(path).isDirectory(); } catch (Exception e) { return false; }
    }

    @Override public long length(String path) {
        try {
            File f = new File(path);
            return f.isFile() ? f.length() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override public boolean mkdirs(String dirPath) {
        try {
            File d = new File(dirPath);
            return d.exists() || d.mkdirs();
        } catch (Exception e) {
            return false;
        }
    }

    @Override public String copy(String srcPath, String dstDirPath) {
        return copyOrMove(srcPath, dstDirPath, false);
    }

    @Override public boolean delete(String path) {
        try {
            File f = new File(path);
            if (f.isDirectory()) {
                String[] kids = f.list();
                if (kids != null && kids.length > 0) return false; // refuse non-empty dirs
            }
            return f.delete();
        } catch (Exception e) {
            return false;
        }
    }

    @Override public String move(String srcPath, String dstDirPath) {
        return copyOrMove(srcPath, dstDirPath, true);
    }

    private String copyOrMove(String srcPath, String dstDirPath, boolean deleteSource) {
        try {
            File src = new File(srcPath);
            if (!src.isFile()) return null;
            File dstDir = new File(dstDirPath);
            if (!dstDir.exists() && !dstDir.mkdirs()) return null;
            File dst = unique(new File(dstDir, src.getName()));
            if (dst == null) return null;
            long len = src.length();
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }
            if (dst.length() != len) {
                // noinspection ResultOfMethodCallIgnored
                dst.delete();
                return null;
            }
            if (deleteSource) {
                if (!src.delete()) {
                    // rollback the copy so a failed move never leaves a duplicate
                    // noinspection ResultOfMethodCallIgnored
                    dst.delete();
                    return null;
                }
                // remove the stale MediaStore row only after the source is really gone
                deleteFromMediaStore(srcPath);
            }
            scan(dst.getAbsolutePath());
            // also scan parent dirs so Gallery album appears/refreshes
            scan(dstDir.getAbsolutePath());
            return dst.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static File unique(File dst) {
        if (!dst.exists()) return dst;
        String name = dst.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; n < 1000; n++) {
            File c = new File(dst.getParent(), base + "_" + n + ext);
            if (!c.exists()) return c;
        }
        return null; // give up rather than overwrite an existing file
    }

    @Override public void scan(String path) {
        try {
            MediaScannerConnection.scanFile(app, new String[]{path}, null, null);
        } catch (Exception ignored) {}
    }

    @Override public boolean flashlight(boolean on) {
        CameraManager cm = cameraManager();
        if (cm == null) return false;
        if (!on) {
            return torchId != null && tryTorch(cm, torchId, false);
        }
        if (torchId != null) return tryTorch(cm, torchId, true);
        Exception last = null;
        String preferred = null;
        try {
            for (String id : cm.getCameraIdList()) {
                try {
                    CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                    if (!Boolean.TRUE.equals(ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) continue;
                    Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        preferred = id;
                        break;
                    }
                    if (preferred == null) preferred = id;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        if (preferred != null) {
            try {
                cm.setTorchMode(preferred, true);
                torchId = preferred;
                return true;
            } catch (Exception e) {
                last = e;
            }
        }
        try {
            for (String id : cm.getCameraIdList()) {
                try {
                    cm.setTorchMode(id, true);
                    torchId = id;
                    return true;
                } catch (Exception e) {
                    last = e;
                }
            }
        } catch (Exception e) {
            last = e;
        }
        if (!torchWarned) {
            torchWarned = true;
            log("Flashlight unavailable: " + (last == null ? "no flash unit" : String.valueOf(last)));
        }
        return false;
    }

    @Override public int blink(int times, int onMs, int offMs) {
        if (times < 1) times = 1;
        if (times > 20) times = 20;
        if (onMs < 50) onMs = 50;
        if (onMs > 2000) onMs = 2000;
        if (offMs < 0) offMs = 0;
        if (offMs > 2000) offMs = 2000;
        int done = 0;
        try {
            for (int i = 0; i < times; i++) {
                if (!flashlight(true)) break;
                sleep(onMs);
                flashlight(false);
                done++;
                if (i < times - 1) sleep(offMs);
            }
        } finally {
            flashlight(false);
        }
        return done;
    }

    private CameraManager cameraManager() {
        try {
            return (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean tryTorch(CameraManager cm, String id, boolean on) {
        try {
            cm.setTorchMode(id, on);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deleteFromMediaStore(String path) {
        try {
            Uri ext = MediaStore.Files.getContentUri("external");
            app.getContentResolver().delete(ext, MediaStore.MediaColumns.DATA + "=?", new String[]{path});
        } catch (Exception ignored) {}
        // also try Images/Video collections explicitly for older resolvers
        try {
            app.getContentResolver().delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.DATA + "=?", new String[]{path});
        } catch (Exception ignored) {}
        try {
            app.getContentResolver().delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.DATA + "=?", new String[]{path});
        } catch (Exception ignored) {}
    }

    @Override public String getData(String key, String def) {
        try {
            SharedPreferences sp = app.getSharedPreferences("plugindata_" + pluginId, Context.MODE_PRIVATE);
            return sp.getString(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    @Override public void putData(String key, String value) {
        try {
            app.getSharedPreferences("plugindata_" + pluginId, Context.MODE_PRIVATE)
                    .edit().putString(key, value).apply();
        } catch (Exception ignored) {}
    }

    // ---------- actions ----------

    private static TextToSpeech sharedTts;

    @Override public boolean notify(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            String ch = "microdroid_script";
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(ch, "Script notifications",
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification n = new Notification.Builder(app, ch)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title == null ? "" : title)
                    .setContentText(text == null ? "" : text)
                    .setAutoCancel(true)
                    .build();
            nm.notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), n);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public boolean vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return false;
            long dur = Math.max(1, Math.min(ms, 10_000));
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(dur);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public boolean speak(String text) {
        try {
            if (text == null || text.trim().isEmpty()) return false;
            if (sharedTts == null) sharedTts = new TextToSpeech(app, status -> {});
            sharedTts.speak(text, TextToSpeech.QUEUE_ADD, null, "microdroid-" + System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public boolean setVolume(int percent) {
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int v = Math.max(0, Math.min(100, percent)) * max / 100;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public boolean setBrightness(int percent) {
        try {
            if (!Settings.System.canWrite(app)) return false;
            int v = Math.max(1, Math.min(100, percent)) * 255 / 100;
            return Settings.System.putInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
        } catch (Exception e) {
            return false;
        }
    }

    @Override public boolean dnd(boolean on) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !nm.isNotificationPolicyAccessGranted()) return false;
            nm.setInterruptionFilter(on
                    ? NotificationManager.INTERRUPTION_FILTER_NONE
                    : NotificationManager.INTERRUPTION_FILTER_ALL);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public boolean launchApp(String pkg) {
        try {
            Intent i = app.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public boolean openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public boolean sendSms(String number, String text) {
        try {
            SmsManager sm = SmsManager.getDefault();
            if (sm == null) return false;
            sm.sendTextMessage(number, null, text == null ? "" : text, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public boolean call(String number) {
        try {
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public String httpGet(String url) {
        return http("GET", url, null, null);
    }

    @Override public String httpPost(String url, String body, String contentType) {
        return http("POST", url, body, contentType);
    }

    private static String http(String method, String url, String body, String contentType) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            if (body != null) {
                c.setDoOutput(true);
                if (contentType == null || contentType.isEmpty()) contentType = "text/plain; charset=utf-8";
                c.setRequestProperty("Content-Type", contentType);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                }
            }
            String resp = sb.length() > 20_000 ? sb.substring(0, 20_000) : sb.toString();
            return code >= 400 ? "ERROR: HTTP " + code + ": " + resp : resp;
        } catch (Exception e) {
            return "ERROR: " + e;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    @Override public void setClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("microdroid", text == null ? "" : text));
        } catch (Exception ignored) {}
    }

    @Override public void toast(String msg) {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(app, msg == null ? "" : msg, android.widget.Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    // ---------- accessibility automation ----------

    @Override public boolean tap(int x, int y) {
        MicrodroidAccessibilityService s = MicrodroidAccessibilityService.get();
        return s != null && s.tap(x, y);
    }

    @Override public boolean swipe(int x1, int y1, int x2, int y2, int durationMs) {
        MicrodroidAccessibilityService s = MicrodroidAccessibilityService.get();
        return s != null && s.swipe(x1, y1, x2, y2, durationMs);
    }

    @Override public boolean typeText(String text) {
        MicrodroidAccessibilityService s = MicrodroidAccessibilityService.get();
        return s != null && s.typeText(text);
    }

    @Override public boolean pressBack() {
        MicrodroidAccessibilityService s = MicrodroidAccessibilityService.get();
        return s != null && s.pressBack();
    }

    @Override public boolean home() {
        MicrodroidAccessibilityService s = MicrodroidAccessibilityService.get();
        return s != null && s.home();
    }

    @Override public boolean scroll(boolean forward) {
        MicrodroidAccessibilityService s = MicrodroidAccessibilityService.get();
        return s != null && s.scroll(forward);
    }

    @Override public boolean clickText(String text) {
        MicrodroidAccessibilityService s = MicrodroidAccessibilityService.get();
        return s != null && s.clickText(text);
    }

    @Override public boolean findText(String text) {
        MicrodroidAccessibilityService s = MicrodroidAccessibilityService.get();
        return s != null && s.findText(text);
    }

    @Override public String currentApp() {
        MicrodroidAccessibilityService s = MicrodroidAccessibilityService.get();
        return s == null ? "" : s.currentApp();
    }
}
