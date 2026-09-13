package com.example.screenshotmover.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** File-ops implementation of PluginContext, scoped per plugin id for logs/prefs. */
public class PluginContextImpl implements PluginContext {

    private final Context app;
    private final String pluginId;

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
                // noinspection ResultOfMethodCallIgnored
                src.delete();
                // remove stale MediaStore row for old path, then index new file
                deleteFromMediaStore(srcPath);
                scan(srcPath);
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
        return dst;
    }

    @Override public void scan(String path) {
        try {
            MediaScannerConnection.scanFile(app, new String[]{path}, null, null);
        } catch (Exception ignored) {}
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
}
