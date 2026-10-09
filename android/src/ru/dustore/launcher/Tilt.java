package ru.dustore.launcher;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Руль от наклона телефона.
 *
 * Палец на нарисованном руле работает, но руль — единственный элемент управления, который
 * человек и в жизни держит двумя руками и крутит целиком. Телефон это умеет: достаточно
 * читать, куда для него «низ».
 *
 * Считаем по вектору силы тяжести, а не по вращению: тяжесть есть всегда и никуда не
 * уплывает, а датчик вращения на дешёвых телефонах любит копить ошибку и уводить ноль —
 * в гонке это выглядит как машина, которая сама сворачивает в стену.
 *
 * Ноль задаётся при включении: как телефон держат в этот момент — так и «прямо». Поэтому
 * играть можно и сидя, и лёжа, и не выпрямляя руки.
 */
final class Tilt implements SensorEventListener {

    /** На сколько градусов наклона приходится полный поворот руля. */
    private static final float FullLock = 32f;

    /** Ниже этого наклон считается дрожанием руки, а не поворотом. */
    private static final float DeadZone = 2.5f;

    interface Listener {
        /** Положение руля от −1000 (влево) до 1000 (вправо). */
        void onSteer(int value);
    }

    private final SensorManager sensors;
    private final Sensor source;
    private final Listener listener;

    private boolean running;
    private boolean zeroed;
    private float zero;
    private int last = Integer.MIN_VALUE;

    Tilt(Context context, Listener listener) {
        this.listener = listener;
        this.sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        Sensor gravity = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);

        // На телефонах без отдельного датчика тяжести её роль играет акселерометр: тот же
        // вектор, только с дрожанием от движений руки.
        this.source = gravity != null || sensors == null
                ? gravity
                : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    /** Есть ли на этом телефоне чем мерить наклон. */
    boolean available() {
        return sensors != null && source != null;
    }

    void start() {
        if (!available() || running) {
            return;
        }

        running = true;
        zeroed = false;

        // GAME — частота для игр: чаще, чем интерфейсу, но без лишнего расхода батареи.
        sensors.registerListener(this, source, SensorManager.SENSOR_DELAY_GAME);
    }

    void stop() {
        if (!running) {
            return;
        }

        running = false;
        sensors.unregisterListener(this);

        // Отпускаем руль, иначе на компьютере останется зажатый поворот.
        if (last != 0) {
            last = 0;
            listener.onSteer(0);
        }
    }

    boolean live() {
        return running;
    }

    /** Заново считает текущее положение телефона «прямо». */
    void recentre() {
        zeroed = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!running || event.values.length < 2) {
            return;
        }

        float ax = event.values[0];
        float ay = event.values[1];

        // Телефон, лежащий плашмя на столе, «смотрит» тяжестью в свою третью ось: ax и ay у
        // него около нуля, и угол между ними — чистый шум, который мотал бы руль из стороны
        // в сторону сам по себе. Такие показания не считаем вовсе.
        if (Math.sqrt(ax * ax + ay * ay) < 2.2) {
            return;
        }

        // Угол между «низом» телефона и силой тяжести. В альбомной ориентации именно он
        // меняется, когда телефон крутят как руль.
        float angle = (float) Math.toDegrees(Math.atan2(ay, ax));

        if (!zeroed) {
            zero = angle;
            zeroed = true;
        }

        float delta = angle - zero;

        while (delta > 180f) {
            delta -= 360f;
        }

        while (delta < -180f) {
            delta += 360f;
        }

        if (Math.abs(delta) < DeadZone) {
            delta = 0f;
        }

        int value = Math.round(Math.max(-1f, Math.min(1f, delta / FullLock)) * 1000f);

        // Шлём только заметные изменения: тридцать писем в секунду об одном и том же
        // забивают очередь нажатий и добавляют задержку остальным кнопкам.
        if (last == Integer.MIN_VALUE || Math.abs(value - last) >= 25
                || (value == 0 && last != 0)) {
            last = value;
            listener.onSteer(value);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Точность тяжести не меняется так, чтобы на это стоило отвечать.
    }
}
