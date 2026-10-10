package ru.dustore.launcher;

import android.app.Application;

/** Первая точка процесса: настройки Prime читаются раньше, чем Ui соберёт палитру. */
public final class DustoreApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Сезон раньше палитры: Ui берёт цвета один раз, при первой загрузке класса.
        Season.load(this);
        Prime.load(this);
        ExTaskBinding.install(this);
    }
}
