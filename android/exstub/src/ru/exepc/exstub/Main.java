package ru.exepc.exstub;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Значок игры на рабочем столе.
 *
 * Всё, что он делает, — зовёт eX внутри DustoreX с id своей игры. Id спрятан в имени
 * пакета: `ru.exepc.exgame.<id>`. Если DustoreX не установлен, честно говорит об этом —
 * игра лежит внутри него, и без него запускать её нечем.
 */
public final class Main extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        String pkg = getPackageName();
        String id = pkg.substring(pkg.lastIndexOf('.') + 1);

        Intent play = new Intent("ru.exepc.ex.PLAY");
        play.setClassName("ru.dustore.launcher", "ru.dustore.launcher.ExActivity");
        play.putExtra("game", id);
        play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(play);
            finish();
        } catch (Exception missing) {
            TextView text = new TextView(this);
            text.setText("Игра запускается через DustoreX — установите его и откройте "
                    + "библиотеку: игра там.");
            text.setTextColor(Color.WHITE);
            text.setTextSize(16);
            text.setGravity(Gravity.CENTER);
            text.setPadding(60, 60, 60, 60);
            text.setBackgroundColor(0xEE151217);
            setContentView(text);
        }
    }
}
