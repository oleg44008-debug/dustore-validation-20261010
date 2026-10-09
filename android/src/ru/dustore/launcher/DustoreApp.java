package ru.dustore.launcher;

import android.app.Application;

/** Первая точка процесса: настройки Prime читаются раньше, чем Ui соберёт палитру. */
public final class DustoreApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Prime.load(this);
        ExTaskBinding.install(this);
    }
}
