package ru.dustore.launcher;

import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable metadata snapshot. All file, package and download-provider work runs off the UI thread. */
final class LibraryState {
    final Library.Entry entry;
    final boolean installed, ready, failed, stubInstalled, apkReady;
    final int downloadStatus, downloadReason;
    final long downloadedBytes, totalBytes;
    final Drawable icon;
    final ExGame game;
    final String normalizedTitle, signature;
    private static final android.util.LruCache<String, Drawable.ConstantState> ICONS = new android.util.LruCache<>(64);

    LibraryState(Library.Entry entry, boolean installed, boolean ready, int status, int reason,
                 long bytes, long total, Drawable icon, ExGame game, boolean stubInstalled, boolean apkReady) {
        this.entry = entry; this.installed = installed; this.ready = ready;
        downloadStatus = status; downloadReason = reason; downloadedBytes = bytes; totalBytes = total;
        failed = status == DownloadManager.STATUS_FAILED || entry.downloadCancelled;
        this.icon = icon; this.game = game; this.stubInstalled = stubInstalled; this.apkReady = apkReady;
        normalizedTitle = (entry.title == null ? "" : entry.title).toLowerCase(Locale.getDefault());
        signature = entry.title + "|" + entry.kind + "|" + entry.packageName + "|" + entry.folder + "|" + entry.exId
                + "|" + entry.playedMinutes + "|" + installed + "|" + ready + "|" + status + "|" + reason + "|" + entry.downloadCancelled
                + "|" + stubInstalled + "|" + apkReady + "|" + (game == null ? "" : game.state + "|" + game.probe
                + "|" + (game.recipe == null ? "" : game.recipe.expect));
    }

    boolean activeDownload() {
        return !entry.downloadCancelled && !ready && !installed
                && (downloadStatus == DownloadManager.STATUS_PENDING || downloadStatus == DownloadManager.STATUS_RUNNING
                || downloadStatus == DownloadManager.STATUS_PAUSED);
    }

    boolean playable() {
        return installed || entry.isWeb() && ready || game != null && game.recipe != null
                && (ExGame.PREPARED.equals(game.state) || ExGame.APK.equals(game.state));
    }

    String downloadText() {
        if (entry.downloadCancelled) return "Загрузка отменена";
        if (downloadStatus == DownloadManager.STATUS_PAUSED) {
            if (downloadReason == DownloadManager.PAUSED_WAITING_FOR_NETWORK) return "Ждём сеть";
            if (downloadReason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) return "Ждём Wi-Fi";
            if (downloadReason == DownloadManager.PAUSED_WAITING_TO_RETRY) return "Android повторит загрузку";
            return "Загрузка приостановлена Android";
        }
        if (downloadStatus == DownloadManager.STATUS_PENDING) return "В очереди загрузок";
        if (downloadStatus == DownloadManager.STATUS_FAILED) {
            switch (downloadReason) {
                case DownloadManager.ERROR_INSUFFICIENT_SPACE: return "Не хватает места на устройстве";
                case DownloadManager.ERROR_DEVICE_NOT_FOUND: return "Хранилище недоступно";
                case DownloadManager.ERROR_HTTP_DATA_ERROR: return "Сбой передачи данных";
                case DownloadManager.ERROR_UNHANDLED_HTTP_CODE: return "Сервер отклонил загрузку";
                case DownloadManager.ERROR_TOO_MANY_REDIRECTS: return "Слишком много перенаправлений";
                case DownloadManager.ERROR_CANNOT_RESUME: return "Загрузку нужно начать заново";
                case DownloadManager.ERROR_FILE_ALREADY_EXISTS: return "Файл уже существует";
                case DownloadManager.ERROR_FILE_ERROR: return "Не удалось записать файл";
                default: return "Ошибка загрузки";
            }
        }
        return "Скачивается";
    }

    String byteProgress() {
        if (!activeDownload() || downloadedBytes < 0) return "";
        String done = String.format(Locale.getDefault(), "%.1f МБ", downloadedBytes / 1048576d);
        if (totalBytes <= 0) return done;
        return done + " из " + String.format(Locale.getDefault(), "%.1f МБ", totalBytes / 1048576d)
                + " · " + Math.min(100, downloadedBytes * 100 / totalBytes) + "%";
    }

    static List<LibraryState> load(Context context, Library library) {
        List<Library.Entry> entries = library.all();
        Map<Long, long[]> downloads = new HashMap<>();
        ArrayList<Long> ids = new ArrayList<>();
        for (Library.Entry entry : entries) if (entry.downloadId > 0 && !entry.downloadCancelled) ids.add(entry.downloadId);
        if (!ids.isEmpty()) {
            long[] values = new long[ids.size()]; for (int i = 0; i < values.length; i++) values[i] = ids.get(i);
            try {
                DownloadManager manager = context.getSystemService(DownloadManager.class);
                try (Cursor cursor = manager == null ? null : manager.query(new DownloadManager.Query().setFilterById(values))) {
                    if (cursor != null) {
                        int id = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
                        int status = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS);
                        int reason = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON);
                        int bytes = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        int total = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                        while (cursor.moveToNext()) downloads.put(cursor.getLong(id), new long[] {
                                cursor.getInt(status), cursor.getInt(reason), cursor.getLong(bytes), cursor.getLong(total) });
                    }
                }
            } catch (RuntimeException unavailable) { }
        }
        List<LibraryState> result = new ArrayList<>(entries.size());
        for (Library.Entry entry : entries) {
            File file = library.fileOf(entry);
            boolean exists = file != null && file.isFile() && file.length() > 0;
            long[] transfer = downloads.get(entry.downloadId);
            int status = entry.downloadId <= 0 || transfer == null && entry.kind != null && exists
                    ? DownloadManager.STATUS_SUCCESSFUL : transfer == null ? DownloadManager.STATUS_PENDING : (int) transfer[0];
            int reason = transfer == null ? 0 : (int) transfer[1];
            boolean installed = false; Drawable icon = null;
            if (entry.packageName != null && !entry.packageName.isEmpty()) {
                try {
                    PackageInfo information = context.getPackageManager().getPackageInfo(entry.packageName, 0);
                    installed = true;
                    String key = entry.packageName + "|" + information.lastUpdateTime;
                    Drawable.ConstantState cached = ICONS.get(key);
                    icon = cached == null ? context.getPackageManager().getApplicationIcon(entry.packageName) : cached.newDrawable(context.getResources());
                    if (cached == null && icon.getConstantState() != null) ICONS.put(key, icon.getConstantState());
                } catch (Exception missing) { }
            }
            boolean ready = !entry.downloadCancelled && (entry.isWeb() ? entry.folder != null
                    && WebEntry.file(new File(entry.folder)) != null : exists && status == DownloadManager.STATUS_SUCCESSFUL);
            ExGame game = entry.exId == null || entry.exId.isEmpty() ? null : ExGame.load(context, entry.exId);
            boolean stub = false;
            if (game != null && game.stubPackage != null) {
                try { context.getPackageManager().getPackageInfo(game.stubPackage, 0); stub = true; }
                catch (Exception missing) { }
            }
            boolean apk = game != null && ExGame.APK.equals(game.state) && game.apk != null && game.apk.isFile();
            result.add(new LibraryState(entry, installed, ready, status, reason,
                    transfer == null ? -1 : transfer[2], transfer == null ? -1 : transfer[3], icon, game, stub, apk));
        }
        return result;
    }
}
