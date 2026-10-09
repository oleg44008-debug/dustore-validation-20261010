package ru.dustore.launcher;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Пробный запуск без экрана: доходит ли игра до первого кадра.
 *
 * Это честность перед сборкой APK. Экран открывается тот же, что и в игре, только рисовать
 * ему некуда — он лишь считает кадры, пришедшие в окна самой игры (не в рабочий стол
 * Wine). Полминуты ожидания, потом всё гасится. Результат — одна строка на карточку:
 * «первый кадр за 12 с» или «кадра не было за 30 с».
 */
final class ExProbe {

    /**
     * Полторы минуты, не полминуты: первый запуск переводит стомегабайтную программу
     * на ходу и собирает конвейеры видеокарты — на живой игре первый кадр пришёл на
     * второй минуте, и «кадра не было за 30 с» было бы враньём про рабочую игру.
     */
    private static final int WAIT_SECONDS = 90;

    static String run(Context context, ExGame game, Ex.Progress say) {
        return run(context, game, say, null);
    }

    static String run(Context context, ExGame game, Ex.Progress say, Object existingSession) {
        if (game.recipe == null || game.recipe.exe == null) {
            return "нечего запускать";
        }
        Object session = existingSession == null ? new Object() : existingSession;
        if (!ExSession.acquire(session)) return "проверка отложена: другая игра ещё работает";

        int width = ExActivity.gameWidth(game.recipe);
        int height = ExActivity.gameHeight(game.recipe);
        ExDisplay display = new ExDisplay(Ex.runDir(context), width,
                height, new ExDisplay.Watcher() {

                    @Override
                    public void say(String line) {
                        if (say != null && line != null && !line.startsWith("кадр")) {
                            say.say("проба: " + line);
                        }
                    }

                    @Override
                    public void changed() {
                        // Кадры считает сам экран — здесь их не нужно даже рисовать.
                    }
                });

        String opened = display.start();

        if (opened != null) {
            if (existingSession == null) ExSession.release(session);
            return "экран не открылся: " + opened;
        }

        Process process = null;
        ExWork.Hook cancelProcess = null;
        long started = SystemClock.uptimeMillis();
        int lowRamSamples = 0;
        long lastReport = 0;

        try {
            boolean nativeWine = ExNative.ready(context);
            Ex.log(context, "Проба: среда " + (nativeWine ? "Hangover ARM64" : "Box64 Wine"));
            java.util.List<String> args = game.recipe.launchArgs(width, height);
            String command = nativeWine
                    ? ExNative.gameCommand("/games-local/" + game.folder.getName(),
                            game.recipe.exe, args, width, height)
                    : Ex.gameCommand("/games-local/" + game.folder.getName(),
                            game.recipe.exe, args, width, height);

            process = Ex.launch(context, new String[] { "/bin/bash", "-c", command },
                    nativeWine ? ExNative.environment(game.recipe, game.recipe.env)
                            : game.recipe.env);

            final Process running = process;
            cancelProcess = ExWork.hook(() -> {
                running.destroy();
                try { running.getInputStream().close(); } catch (Exception ignored) { }
            });

            // Вывод игры читается в сторонке: полный буфер остановил бы её саму.
            new Thread(() -> {
                try (InputStream stream = running.getInputStream()) {
                    byte[] buffer = new byte[4096];

                    int read;
                    while ((read = stream.read(buffer)) > 0) {
                        Ex.log(context, "проба: " + new String(buffer, 0, read,
                                StandardCharsets.UTF_8).trim());
                    }
                } catch (Exception ignored) {
                    // Процесс закончился.
                }
            }, "ex-probe-out").start();

            while (SystemClock.uptimeMillis() - started < WAIT_SECONDS * 1000L) {
                ExWork.check();
                long[] memory = ExMemory.read();
                long now = SystemClock.uptimeMillis();
                if (lastReport == 0 || now - lastReport >= 5000) {
                    Ex.log(context, new java.util.Date() + " проба: "
                            + ExMemory.snapshot(context, memory));
                    lastReport = now;
                }
                lowRamSamples = ExMemory.lowRam(memory) ? lowRamSamples + 1 : 0;
                if (lowRamSamples >= 8) {
                    Ex.log(context, "Проба остановлена из-за памяти: "
                            + ExMemory.snapshot(context, memory));
                    return "проба остановлена: телефону не хватает памяти для этого запуска";
                }

                if (display.gameFrames() > 0) {
                    long seconds = (SystemClock.uptimeMillis() - started + 999) / 1000;
                    return "первый кадр за " + seconds + " с";
                }

                if (!ExProcess.alive(running)) {
                    return "игра закрылась сама через "
                            + (SystemClock.uptimeMillis() - started) / 1000 + " с";
                }

                Thread.sleep(250);
            }

            return "кадра не было за " + WAIT_SECONDS + " с";
        } catch (Exception broken) {
            ExWork.check();
            return "не запустилась: " + broken.getMessage();
        } finally {
            if (cancelProcess != null) cancelProcess.close();
            if (process != null) {
                process.destroy();
            }
            try {
                ExWork.cleanup(() -> {
                    try {
                        if (ExNative.ready(context)) ExNative.stop(context);
                        else Ex.run(context, new String[] { "/usr/bin/timeout", "--kill-after=2", "10",
                                "/opt/wine/bin/wineserver", "-k" }, false, null);
                    } finally { Ex.stopOwnChildren(); }
                });
            } finally {
                display.stop();
                if (existingSession == null) ExSession.release(session);
            }
        }
    }

    private ExProbe() {
    }
}
