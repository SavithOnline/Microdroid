package com.example.screenshotmover.plugin;

/**
 * Narrow, auditable API exposed to script plugins (v1: files + logging only).
 * All paths are absolute device paths (e.g. /storage/emulated/0/Download/x).
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

    /** Per-plugin key/value store. */
    String getData(String key, String def);
    void putData(String key, String value);
}
