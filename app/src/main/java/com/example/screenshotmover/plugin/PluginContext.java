package com.example.screenshotmover.plugin;

/**
 * Convenience file + logging API exposed to script plugins (v1).
 * This is NOT a sandbox: BeanShell scripts can also reach the full Java API
 * (e.g. Runtime.getRuntime()) with the app's permissions, so only import
 * scripts you trust. All paths are absolute device paths
 * (e.g. /storage/emulated/0/Download/x).
 */
public interface PluginContext {
    /** Append to this plugin's run log (visible in Logs). */
    void log(String msg);

    /** Names inside dirPath, or null if missing/not a directory. */
    String[] list(String dirPath);
    boolean exists(String path);
    boolean isFile(String path);
    boolean isDirectory(String path);
    /** File length in bytes, -1 if missing. */
    long length(String path);
    boolean mkdirs(String dirPath);
    /** Copy file; creates parent dirs; never overwrites (adds _1, _2...). Returns dest path or null. */
    String copy(String srcPath, String dstDirPath);
    /** Delete file or empty dir. */
    boolean delete(String path);
    /**
     * Move file into dstDirPath (copy + size-verify + delete source).
     * Never overwrites. Returns dest path or null.
     */
    String move(String srcPath, String dstDirPath);
    /** Ask MediaStore to index a new file. */
    void scan(String path);

    /** Turn the LED torch on/off. Returns true if the state was applied. */
    boolean flashlight(boolean on);
    /** Blink the torch onMs/offMs, always ending off. Returns completed cycles. */
    int blink(int times, int onMs, int offMs);

    /** Post a system notification. */
    boolean notify(String title, String text);
    /** Vibrate for ms milliseconds. */
    boolean vibrate(int ms);
    /** Speak text with the system TTS engine. */
    boolean speak(String text);
    /** Set the music stream volume, 0-100. */
    boolean setVolume(int percent);
    /** Set screen brightness, 0-100 (needs the Write settings special access). */
    boolean setBrightness(int percent);
    /** Enable/disable Do Not Disturb (needs notification policy access). */
    boolean dnd(boolean on);
    /** Launch an app by package name. */
    boolean launchApp(String pkg);
    /** Open a URL in the default browser. */
    boolean openUrl(String url);
    /** Send an SMS (needs the SMS permission). */
    boolean sendSms(String number, String text);
    /** Place a call (needs the Phone permission). */
    boolean call(String number);
    /** HTTP GET returning the body, or "ERROR: ..." on failure. */
    String httpGet(String url);
    /** HTTP POST returning the body, or "ERROR: ..." on failure. */
    String httpPost(String url, String body, String contentType);
    /** Copy text to the clipboard. */
    void setClipboard(String text);
    /** Show a toast (only useful while the app is in the foreground). */
    void toast(String msg);

    /** Tap an absolute screen coordinate (needs Accessibility). */
    boolean tap(int x, int y);
    /** Swipe an absolute screen line (needs Accessibility). */
    boolean swipe(int x1, int y1, int x2, int y2, int durationMs);
    /** Replace the text of the focused input (needs Accessibility). */
    boolean typeText(String text);
    /** Press Back (needs Accessibility). */
    boolean pressBack();
    /** Press Home (needs Accessibility). */
    boolean home();
    /** Scroll the focused scrollable view, forward or backward (needs Accessibility). */
    boolean scroll(boolean forward);
    /** Click the first node whose text/content-desc contains this string (needs Accessibility). */
    boolean clickText(String text);
    /** True when a node with this text/content-desc exists on screen (needs Accessibility). */
    boolean findText(String text);
    /** Package name of the app currently on screen, "" if unknown (needs Accessibility). */
    String currentApp();

    /** Per-plugin key/value store. */
    String getData(String key, String def);
    void putData(String key, String value);
}
