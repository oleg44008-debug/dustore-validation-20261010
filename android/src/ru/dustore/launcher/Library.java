package ru.dustore.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.app.DownloadManager;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Библиотека игр, скачанных через приложение.
 *
 * Настольный лаунчер качает архив, распаковывает и запускает .exe. На телефоне игра —
 * это apk, и путь другой: скачать, установить, открыть. Но смысл библиотеки тот же самый
 * и держится на одном: **оценку можно поставить только тому, что скачано отсюда и во что
 * играли**. Ради этого библиотека и запоминает, что было скачано, что установлено и
 * сколько человек в этом провёл — без этих трёх фактов отзыв не отличить от мнения
 * прохожего.
 *
 * Всё лежит в одном файле library.json во внутренней папке приложения. Записей здесь
 * десятки, а не тысячи: база данных ради такого — лишняя деталь, которая может сломаться.
 */
final class Library {

    private static final String FILE = "library.json";

    /** Одна игра. */
    static final class Entry {
        long downloadId;
        String gameKey;
        String title;
        String pageUrl;
        String fileName;
        String packageName;
        /** «app» — Android, «web» — веб-сборка, «pc» — сборка для Windows, «unknown» — неясно. */
        String kind;
        /** Куда распакована веб-сборка. */
        String folder;
        /** Id игры в eX, если сборку для Windows уже эмулировали. */
        String exId;
        long addedUtc;
        long playedMinutes;
        long playStartedAt;
        boolean launched;
        boolean downloadCancelled;

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("downloadId", downloadId);
                json.put("gameKey", gameKey);
                json.put("title", title);
                json.put("pageUrl", pageUrl);
                json.put("fileName", fileName);
                json.put("packageName", packageName);
                json.put("kind", kind);
                json.put("folder", folder);
                json.put("exId", exId);
                json.put("addedUtc", addedUtc);
                json.put("playedMinutes", playedMinutes);
                json.put("launched", launched);
                json.put("downloadCancelled", downloadCancelled);
            } catch (Exception ignored) {
            }
            return json;
        }

        static Entry fromJson(JSONObject json) {
            Entry entry = new Entry();
            entry.downloadId = json.optLong("downloadId", 0);
            entry.gameKey = json.optString("gameKey", null);
            entry.title = json.optString("title", "Игра");
            entry.pageUrl = json.optString("pageUrl", null);
            entry.fileName = json.optString("fileName", null);
            entry.packageName = json.optString("packageName", null);
            entry.kind = json.optString("kind", null);
            entry.folder = json.optString("folder", null);
            entry.exId = json.isNull("exId") ? null : json.optString("exId", null);
            entry.addedUtc = json.optLong("addedUtc", 0);
            entry.playedMinutes = json.optLong("playedMinutes", 0);
            // Записи, сделанные до появления этого поля: если время уже накоплено, значит
            // в игру заходили — иначе человек потерял бы право на отзыв из-за обновления.
            entry.launched = json.optBoolean("launched", json.optLong("playedMinutes", 0) > 0);
            entry.downloadCancelled = json.optBoolean("downloadCancelled", false);
            return entry;
        }

        boolean isWeb() {
            return "web".equals(kind);
        }

        boolean isApp() {
            return "app".equals(kind);
        }

        boolean isPc() {
            return "pc".equals(kind);
        }
    }

    private final Context context;
    private final List<Entry> entries = new ArrayList<>();

    Library(Context context) {
        this.context = context.getApplicationContext();
        load();
    }

    synchronized List<Entry> all() {
        return new ArrayList<>(entries);
    }

    Entry byDownload(long id) {
        for (Entry entry : all()) {
            if (entry.downloadId == id) {
                return entry;
            }
        }
        return null;
    }

    Entry byPackage(String packageName) {
        if (packageName == null) {
            return null;
        }

        for (Entry entry : all()) {
            if (packageName.equals(entry.packageName)) {
                return entry;
            }
        }
        return null;
    }

    synchronized void add(Entry entry) {
        // Повторное скачивание той же игры заменяет запись, а не добавляет вторую:
        // человек обновил игру, а не завёл ещё одну.
        for (int i = 0; i < entries.size(); i++) {
            if (entry.gameKey != null && entry.gameKey.equals(entries.get(i).gameKey)) {
                // Время игры сохраняется; файл и способ запуска определяются заново
                // только после успешного завершения этой загрузки.
                entry.playedMinutes = entries.get(i).playedMinutes;
                entry.launched = entries.get(i).launched;
                entries.set(i, entry);
                save();
                return;
            }
        }

        entries.add(0, entry);
        save();
    }

    synchronized void remove(Entry entry) {
        entries.remove(entry);

        File file = fileOf(entry);
        if (file != null && file.exists() && owned(file)) {
            file.delete();
        }

        // Распакованная игра тоже уходит: оставить её — значит держать на телефоне
        // десятки мегабайт того, что человек только что убрал из виду.
        if (entry.folder != null) {
            File folder = new File(entry.folder);
            if (owned(folder)) Unpack.deleteAll(folder);
        }

        save();
    }

    private boolean owned(File file) {
        try {
            String path = file.getCanonicalPath();
            for (File root : new File[] { context.getFilesDir(), context.getExternalFilesDir(null) }) {
                if (root != null && path.startsWith(root.getCanonicalPath() + File.separator)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** Готова ли игра к запуску: приложение — скачано, веб-сборка — распакована. */
    boolean ready(Entry entry) {
        if (entry.downloadCancelled) return false;
        if (entry.isWeb()) {
            return entry.folder != null && WebEntry.file(new File(entry.folder)) != null;
        }

        return downloaded(entry) && downloadComplete(entry);
    }

    File fileOf(Entry entry) {
        if (entry.fileName == null) {
            return null;
        }

        File dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        return dir == null ? null : new File(dir, entry.fileName);
    }

    boolean downloaded(Entry entry) {
        File file = fileOf(entry);
        return file != null && file.exists() && file.length() > 0;
    }

    /** A file exists from the first downloaded byte; only a successful transfer is playable. */
    boolean downloadComplete(Entry entry) {
        return downloadStatus(entry) == DownloadManager.STATUS_SUCCESSFUL;
    }

    boolean downloadFailed(Entry entry) {
        if (entry.downloadCancelled) return true;
        return downloadStatus(entry) == DownloadManager.STATUS_FAILED;
    }

    private int downloadStatus(Entry entry) {
        if (entry.downloadId <= 0) return DownloadManager.STATUS_SUCCESSFUL;
        try {
            DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(entry.downloadId))) {
                if (cursor == null || !cursor.moveToFirst()) {
                    return entry.kind != null && downloaded(entry)
                            ? DownloadManager.STATUS_SUCCESSFUL : DownloadManager.STATUS_PENDING;
                }
                return cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            }
        } catch (Exception unavailable) {
            return DownloadManager.STATUS_PENDING;
        }
    }

    /**
     * Разбирается, что именно скачалось, и готовит игру к запуску.
     *
     * Опознание идёт по содержимому файла: на сайте веб-сборка называется .apk, и по
     * имени эти два случая не различить. Приложению достаточно узнать имя пакета,
     * веб-сборку надо распаковать — зато потом она запускается прямо здесь.
     */
    void examine(Entry entry) {
        File file = fileOf(entry);
        if (file == null || !file.exists() || !downloadComplete(entry)) {
            return;
        }

        Unpack.Kind kind = Unpack.kindOf(file);

        if (kind == Unpack.Kind.APP) {
            entry.kind = "app";
            entry.packageName = readPackageName(entry);
            save();
            return;
        }

        if (kind == Unpack.Kind.WEB) {
            File target = new File(new File(context.getFilesDir(), "games"),
                    entry.gameKey == null ? "game" : entry.gameKey.replaceAll("[^A-Za-z0-9]", "_"));

            File index = Unpack.web(file, target);

            if (index != null) {
                entry.kind = "web";
                entry.folder = index.getAbsolutePath();

                // Архив больше не нужен: игра распакована, а места на телефоне жалко.
                file.delete();
            } else {
                entry.kind = "unknown";
            }

            save();
            return;
        }

        if (kind == Unpack.Kind.PC) {
            // Сборка для Windows, честно скачанная на телефон. Запустить её здесь нельзя,
            // но и делать вид, что её не было, — хуже: файл лежит, человек его качал.
            entry.kind = "pc";
            save();
            return;
        }

        entry.kind = "unknown";
        save();
    }

    /**
     * Имя пакета читается из самого скачанного файла.
     *
     * Иначе установленную игру не с чем связать: в системе она называется по имени
     * пакета, а на сайте — человеческим названием, и совпадать они не обязаны.
     */
    String readPackageName(Entry entry) {
        File file = fileOf(entry);
        if (file == null || !file.exists()) {
            return null;
        }

        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageArchiveInfo(file.getAbsolutePath(), 0);
            return info == null ? null : info.packageName;
        } catch (Exception ex) {
            return null;
        }
    }

    boolean installed(Entry entry) {
        if (entry.packageName == null) {
            return false;
        }

        try {
            context.getPackageManager().getPackageInfo(entry.packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ex) {
            return false;
        }
    }

    Intent launchIntent(Entry entry) {
        if (entry.packageName == null) {
            return null;
        }

        return context.getPackageManager().getLaunchIntentForPackage(entry.packageName);
    }

    /**
     * Засекает начало игры.
     *
     * Право на отзыв появляется прямо здесь, в момент запуска, а не после возвращения:
     * человек уже в игре, и всё, что он о ней скажет, — законно. Ждать, пока он выйдет,
     * значит терять тех, кто закрыл лаунчер и не вернулся.
     */
    void startedPlaying(Entry entry) {
        entry.playStartedAt = System.currentTimeMillis();

        if (!entry.launched) {
            entry.launched = true;
            save();
        }
    }

    /**
     * Закрывает засечку и возвращает, сколько минут прибавилось.
     *
     * Способ намеренно простой: игра запускается отсюда, значит момент запуска известен, а
     * возвращение человека в приложение — это конец игры. Точное время работы чужого
     * приложения Android отдаёт только с особым разрешением, которое пришлось бы просить
     * отдельным походом в настройки, — ради счётчика минут это слишком.
     */
    long stoppedPlaying(Entry entry) {
        if (entry.playStartedAt == 0) {
            return 0;
        }

        long minutes = (System.currentTimeMillis() - entry.playStartedAt) / 60000;
        entry.playStartedAt = 0;

        if (minutes <= 0) {
            return 0;
        }

        // Сутки — это не игра, а забытый в фоне телефон. Такое время в счёт не идёт.
        if (minutes > 24 * 60) {
            return 0;
        }

        entry.playedMinutes += minutes;
        save();

        return minutes;
    }

    /**
     * Догоняет то, что случилось без нас.
     *
     * Между двумя открытиями приложения человек мог поставить игру, запустить её с
     * домашнего экрана и наиграть час. Здесь это подхватывается: имя пакета дочитывается,
     * если раньше не удалось, а время сверяется с системным учётом.
     */
    void catchUp() {
        boolean changed = false;

        for (Entry entry : all()) {
            // Имя пакета могло не прочитаться сразу — например, файл ещё дописывался.
            // Без повторной попытки установленная игра навсегда осталась бы «не
            // установленной», и кнопка «Установить» не сменилась бы на «Играть».
            if (entry.packageName == null && !entry.isWeb() && ready(entry)) {
                entry.packageName = readPackageName(entry);
                changed |= entry.packageName != null;
            }

        }

        if (changed) {
            save();
        }
    }

    /**
     * Ищет только что установленную игру среди приложений телефона.
     *
     * Запасной путь на случай, когда имя пакета из файла прочитать не удалось: сравниваем
     * список запускаемых приложений до установки и после — что появилось, то и поставили.
     */
    java.util.Set<String> launchable() {
        java.util.Set<String> names = new java.util.HashSet<>();

        try {
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);

            for (android.content.pm.ResolveInfo info
                    : context.getPackageManager().queryIntentActivities(main, 0)) {
                if (info.activityInfo != null) {
                    names.add(info.activityInfo.packageName);
                }
            }
        } catch (Exception ex) {
            // Пустой список просто означает, что запасной путь не сработал.
        }

        return names;
    }

    // ------------------------------------------------------------------ хранение ----

    private void load() {
        File file = new File(context.getFilesDir(), FILE);
        if (!file.exists()) {
            return;
        }

        try {
            JSONArray list = new JSONArray(ExFileRead.utf8(file, ExFileRead.LIBRARY_BYTES));
            for (int i = 0; i < list.length(); i++) {
                entries.add(Entry.fromJson(list.getJSONObject(i)));
            }
        } catch (Exception ex) {
            // Испорченный файл не должен мешать приложению открыться: библиотека
            // начнётся заново, а игры на месте — их видно по установленным пакетам.
        }
    }

    synchronized void save() {
        JSONArray list = new JSONArray();
        for (Entry entry : entries) {
            list.put(entry.toJson());
        }

        File file = new File(context.getFilesDir(), FILE);
        File temporary = new File(context.getFilesDir(), FILE + ".tmp");

        // Сначала во временный файл, потом переименование: прерванная запись не должна
        // оставить обрезанный список вместо целого.
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(list.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception ex) {
            return;
        }

        if (!temporary.renameTo(file)) {
            temporary.delete();
        }
    }
}
