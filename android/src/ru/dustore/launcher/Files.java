package ru.dustore.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Выдача скачанного файла системному установщику.
 *
 * Отдать установщику путь вида /storage/... нельзя: начиная с Android 7 передача file://
 * между приложениями запрещена и приводит к падению. Обычно эту задачу решает FileProvider
 * из библиотеки поддержки, но всё приложение здесь собирается без единой зависимости —
 * шестьдесят килобайт и никакого Gradle, — и тащить ради одного класса целую библиотеку
 * было бы несоразмерно. Провайдер на полсотни строк делает ровно то же самое.
 *
 * Наружу не выставлен (exported="false"): доступ выдаётся поштучно, флагом
 * FLAG_GRANT_READ_URI_PERMISSION на том самом намерении, которым файл открывают, и живёт
 * ровно до конца установки.
 */
public final class Files extends ContentProvider {

    static final String AUTHORITY = "ru.dustore.launcher.files";
    static final String APK_TYPE = "application/vnd.android.package-archive";

    static Uri uriFor(File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(file.getName()));
    }

    static Uri diagnosticLog() { return Uri.parse("content://" + AUTHORITY + "/diagnostics/ex.log"); }

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * Файл по имени — и только из своей папки загрузок.
     *
     * Имя приходит снаружи, поэтому одной склейки путей мало: «../../» увело бы чтение
     * куда угодно в данные приложения. Отсюда сверка настоящего пути с настоящим путём
     * папки, уже после раскрытия всех «точка-точка».
     */
    private File resolve(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        String name = uri.getLastPathSegment();

        if (context == null || name == null) {
            throw new FileNotFoundException("Нет такого файла");
        }

        // Only this exact user-selected diagnostic file is shareable outside Downloads.
        if ("/diagnostics/ex.log".equals(uri.getPath())) {
            File outside = context.getExternalFilesDir(null);
            File log = outside == null ? null : new File(outside, "ex.log");
            if (log == null || !log.isFile()) throw new FileNotFoundException("Журнал ещё не создан");
            return log;
        }

        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            throw new FileNotFoundException("Папка загрузок недоступна");
        }

        File file = new File(dir, Uri.decode(name));

        try {
            if (!file.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                throw new FileNotFoundException("Файл вне папки загрузок");
            }
        } catch (Exception ex) {
            throw new FileNotFoundException("Не удалось проверить путь");
        }

        if (!file.exists()) {
            throw new FileNotFoundException("Файл не найден");
        }

        return file;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // Только чтение, каким бы режимом ни попросили: установщику больше и не нужно.
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * Установщик перед показом окна спрашивает имя и размер. Без ответа он показывает
     * пустое окно или отказывается ставить, поэтому эти две колонки обязательны.
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file;

        try {
            file = resolve(uri);
        } catch (FileNotFoundException ex) {
            return null;
        }

        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;

        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];

        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = file.length();
            } else {
                row[i] = null;
            }
        }

        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "/diagnostics/ex.log".equals(uri.getPath()) ? "text/plain" : APK_TYPE;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Только чтение");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Только чтение");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Только чтение");
    }
}
