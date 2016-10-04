package de.robv.android.xposed.installer.util;

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.installer.R;
import de.robv.android.xposed.installer.XposedApp;
import de.robv.android.xposed.installer.repo.Module;
import de.robv.android.xposed.installer.repo.ModuleVersion;
import de.robv.android.xposed.installer.repo.ReleaseType;

public class DownloadsUtil {
    public static final String MIME_TYPE_APK = "application/vnd.android.package-archive";
    public static final String MIME_TYPE_ZIP = "application/zip";
    private static final Map<String, DownloadFinishedCallback> mCallbacks = new HashMap<>();
    private static final XposedApp mApp = XposedApp.getInstance();
    private static final SharedPreferences mPref = mApp
            .getSharedPreferences("download_cache", Context.MODE_PRIVATE);

    public static DownloadInfo add(Context context, String title, String url, DownloadFinishedCallback callback, MIME_TYPES mimeType) {

        return add(context, title, url, callback, mimeType, false, false);
    }

    public static DownloadInfo add(Context context, String title, String url, DownloadFinishedCallback callback, MIME_TYPES mimeType,
                                   boolean save) {

        return add(context, title, url, callback, mimeType, save, false);
    }

    public static DownloadInfo add(Context context, String title, String url, DownloadFinishedCallback callback, MIME_TYPES mimeType, boolean save, boolean module) {
        removeAllForUrl(context, url);

        synchronized (mCallbacks) {
            mCallbacks.put(url, callback);
        }

        String savePath = "XposedInstaller";
        if (module) {
            savePath = XposedApp.getDownloadPath().replace(Environment.getExternalStorageDirectory() + "", "");
        }

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Request request = new Request(Uri.parse(url));
        request.setTitle(title);
        request.setMimeType(mimeType.toString());
        if (save) {
            try {
                request.setDestinationInExternalPublicDir(savePath, title + mimeType.getExtension());
            } catch (IllegalStateException e) {
                Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
        request.setNotificationVisibility(Request.VISIBILITY_VISIBLE);
        long id = dm.enqueue(request);

        return getById(context, id);
    }

    public static ModuleVersion getStableVersion(Module m) {
        for (int i = 0; i < m.versions.size(); i++) {
            ModuleVersion mvTemp = m.versions.get(i);

            if (mvTemp.relType == ReleaseType.STABLE) {
                return mvTemp;
            }
        }
        return null;
    }


    public static DownloadInfo getById(Context context, long id) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = dm.query(new Query().setFilterById(id));
        if (!c.moveToFirst()) {
            c.close();
            return null;
        }

        int columnUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI);
        int columnTitle = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE);
        int columnLastMod = c.getColumnIndexOrThrow(
                DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP);
        int columnFilename = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME);
        int columnStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS);
        int columnTotalSize = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
        int columnBytesDownloaded = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
        int columnReason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON);

        int status = c.getInt(columnStatus);
        String localFilename = c.getString(columnFilename);
        if (status == DownloadManager.STATUS_SUCCESSFUL && !new File(localFilename).isFile()) {
            dm.remove(id);
            c.close();
            return null;
        }

        DownloadInfo info = new DownloadInfo(id, c.getString(columnUri),
                c.getString(columnTitle), c.getLong(columnLastMod),
                localFilename, status,
                c.getInt(columnTotalSize), c.getInt(columnBytesDownloaded),
                c.getInt(columnReason));
        c.close();

        return info;
    }

    public static DownloadInfo getLatestForUrl(Context context, String url) {
        List<DownloadInfo> all = getAllForUrl(context, url);
        return all.isEmpty() ? null : all.get(0);
    }

    public static List<DownloadInfo> getAllForUrl(Context context, String url) {
        DownloadManager dm = (DownloadManager) context
                .getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = dm.query(new Query());
        int columnId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
        int columnUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI);
        int columnTitle = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE);
        int columnLastMod = c.getColumnIndexOrThrow(
                DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP);
        int columnFilename = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME);
        int columnStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS);
        int columnTotalSize = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
        int columnBytesDownloaded = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
        int columnReason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON);

        List<DownloadInfo> downloads = new ArrayList<>();
        while (c.moveToNext()) {
            if (!url.equals(c.getString(columnUri)))
                continue;

            int status = c.getInt(columnStatus);
            String localFilename = c.getString(columnFilename);
            if (status == DownloadManager.STATUS_SUCCESSFUL && !new File(localFilename).isFile()) {
                dm.remove(c.getLong(columnId));
                continue;
            }

            downloads.add(new DownloadInfo(c.getLong(columnId),
                    c.getString(columnUri), c.getString(columnTitle),
                    c.getLong(columnLastMod), localFilename,
                    status, c.getInt(columnTotalSize),
                    c.getInt(columnBytesDownloaded), c.getInt(columnReason)));
        }
        c.close();

        Collections.sort(downloads);
        return downloads;
    }

    public static void removeById(Context context, long id) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        dm.remove(id);
    }

    public static void removeAllForUrl(Context context, String url) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = dm.query(new Query());
        int columnId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
        int columnUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI);

        List<Long> idsList = new ArrayList<>();
        while (c.moveToNext()) {
            if (url.equals(c.getString(columnUri)))
                idsList.add(c.getLong(columnId));
        }
        c.close();

        if (idsList.isEmpty())
            return;

        long ids[] = new long[idsList.size()];
        for (int i = 0; i < ids.length; i++)
            ids[i] = idsList.get(0);

        dm.remove(ids);
    }

    public static void removeOutdated(Context context, long cutoff) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = dm.query(new Query());
        int columnId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
        int columnLastMod = c.getColumnIndexOrThrow(
                DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP);

        List<Long> idsList = new ArrayList<>();
        while (c.moveToNext()) {
            if (c.getLong(columnLastMod) < cutoff)
                idsList.add(c.getLong(columnId));
        }
        c.close();

        if (idsList.isEmpty())
            return;

        long ids[] = new long[idsList.size()];
        for (int i = 0; i < ids.length; i++)
            ids[i] = idsList.get(0);

        dm.remove(ids);
    }

    public static void triggerDownloadFinishedCallback(Context context, long id) {
        DownloadInfo info = getById(context, id);
        if (info == null || info.status != DownloadManager.STATUS_SUCCESSFUL)
            return;

        DownloadFinishedCallback callback;
        synchronized (mCallbacks) {
            callback = mCallbacks.get(info.url);
        }

        if (callback == null)
            return;

        callback.onDownloadFinished(context, info);
    }

    public static SyncDownloadInfo downloadSynchronously(String url, File target) {
        // TODO Potential parameter?
        final boolean useNotModifiedTags = true;

        URLConnection connection = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            connection = new URL(url).openConnection();
            connection.setDoOutput(false);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);

            if (connection instanceof HttpURLConnection) {
                // Disable transparent gzip encoding for gzipped files
                if (url.endsWith(".gz"))
                    connection.addRequestProperty("Accept-Encoding", "identity");

                String modified = mPref.getString("download_" + url + "_modified", null);
                String etag = mPref.getString("download_" + url + "_etag", null);

                if (modified != null)
                    connection.addRequestProperty("If-Modified-Since", modified);
                if (etag != null)
                    connection.addRequestProperty("If-None-Match", etag);
            }

            connection.connect();

            if (connection instanceof HttpURLConnection) {
                HttpURLConnection httpConnection = (HttpURLConnection) connection;
                int responseCode = httpConnection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    return new SyncDownloadInfo(SyncDownloadInfo.STATUS_NOT_MODIFIED, null);
                } else if (responseCode < 200 || responseCode >= 300) {
                    return new SyncDownloadInfo(SyncDownloadInfo.STATUS_FAILED,
                            mApp.getString(R.string.repo_download_failed_http,
                                    url, responseCode,
                                    httpConnection.getResponseMessage()));
                }
            }

            in = connection.getInputStream();
            out = new FileOutputStream(target);
            byte buf[] = new byte[1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }

            if (connection instanceof HttpURLConnection) {
                HttpURLConnection httpConnection = (HttpURLConnection) connection;
                String modified = httpConnection.getHeaderField("Last-Modified");
                String etag = httpConnection.getHeaderField("ETag");

                mPref.edit()
                        .putString("download_" + url + "_modified", modified)
                        .putString("download_" + url + "_etag", etag).apply();
            }

            return new SyncDownloadInfo(SyncDownloadInfo.STATUS_SUCCESS, null);

        } catch (Throwable t) {
            return new SyncDownloadInfo(SyncDownloadInfo.STATUS_FAILED,
                    mApp.getString(R.string.repo_download_failed, url,
                            t.getMessage()));

        } finally {
            if (connection != null && connection instanceof HttpURLConnection)
                ((HttpURLConnection) connection).disconnect();
            if (in != null)
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            if (out != null)
                try {
                    out.close();
                } catch (IOException ignored) {
                }
        }
    }

    public static void clearCache(String url) {
        if (url != null) {
            mPref.edit().remove("download_" + url + "_modified")
                    .remove("download_" + url + "_etag").apply();
        } else {
            mPref.edit().clear().apply();
        }
    }

    public enum MIME_TYPES {
        APK {
            public String toString() {
                return MIME_TYPE_APK;
            }

            public String getExtension() {
                return ".apk";
            }
        },
        ZIP {
            public String toString() {
                return MIME_TYPE_ZIP;
            }

            public String getExtension() {
                return ".zip";
            }
        };

        public String getExtension() {
            return null;
        }
    }

    public interface DownloadFinishedCallback {
        void onDownloadFinished(Context context, DownloadInfo info);
    }

    public static class DownloadInfo implements Comparable<DownloadInfo> {
        public final long id;
        public final String url;
        public final String title;
        public final long lastModification;
        public final String localFilename;
        public final int status;
        public final int totalSize;
        public final int bytesDownloaded;
        public final int reason;

        private DownloadInfo(long id, String url, String title, long lastModification, String localFilename, int status, int totalSize, int bytesDownloaded, int reason) {
            this.id = id;
            this.url = url;
            this.title = title;
            this.lastModification = lastModification;
            this.localFilename = localFilename;
            this.status = status;
            this.totalSize = totalSize;
            this.bytesDownloaded = bytesDownloaded;
            this.reason = reason;
        }

        @Override
        public int compareTo(@NonNull DownloadInfo another) {
            int compare = (int) (another.lastModification
                    - this.lastModification);
            if (compare != 0)
                return compare;
            return this.url.compareTo(another.url);
        }
    }

    public static class SyncDownloadInfo {
        public static final int STATUS_SUCCESS = 0;
        public static final int STATUS_NOT_MODIFIED = 1;
        public static final int STATUS_FAILED = 2;

        public final int status;
        public final String errorMessage;

        private SyncDownloadInfo(int status, String errorMessage) {
            this.status = status;
            this.errorMessage = errorMessage;
        }
    }
}