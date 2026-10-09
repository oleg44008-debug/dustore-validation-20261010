package ru.dustore.launcher;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * Звук игры, пришедший с компьютера.
 *
 * Без него eXeportal наполовину бесполезен, и это не про удобство: магазин держится на
 * хоррорах, а хоррор без звука — просто человек в тёмном коридоре. Шаги за спиной и скрип
 * двери — половина игры.
 *
 * Играем сырые 16-битные отсчёты как есть: сжимать их незачем — по кабелю и по домашнему
 * Wi-Fi поток в полтора мегабита проходит спокойно, а любое сжатие добавило бы задержку
 * ровно там, где она слышна как запаздывание звука от картинки.
 *
 * Формат приходит с каждой порцией: на компьютере посреди игры втыкают наушники, частота
 * вывода меняется, и проигрыватель надо пересобрать — иначе звук поедет по высоте.
 */
final class Speaker {

    private AudioTrack track;
    private int rate;
    private int channels;

    /** Кладёт порцию в очередь на воспроизведение. */
    synchronized void play(byte[] message) {
        if (message == null || message.length <= 5) {
            return;
        }

        int wantRate = ((message[0] & 0xFF) << 24) | ((message[1] & 0xFF) << 16)
                | ((message[2] & 0xFF) << 8) | (message[3] & 0xFF);
        int wantChannels = message[4] & 0xFF;

        if (wantRate < 8000 || wantRate > 192000 || wantChannels < 1 || wantChannels > 2) {
            return;
        }

        if (track == null || rate != wantRate || channels != wantChannels) {
            open(wantRate, wantChannels);
        }

        if (track == null) {
            return;
        }

        try {
            track.write(message, 5, message.length - 5);
        } catch (Exception broken) {
            // Проигрыватель мог умереть вместе с устройством вывода — соберём заново.
            close();
        }
    }

    private void open(int wantRate, int wantChannels) {
        close();

        try {
            int mask = wantChannels == 1
                    ? AudioFormat.CHANNEL_OUT_MONO
                    : AudioFormat.CHANNEL_OUT_STEREO;

            int minimum = AudioTrack.getMinBufferSize(wantRate, mask,
                    AudioFormat.ENCODING_PCM_16BIT);

            if (minimum <= 0) {
                return;
            }

            // Буфер вдвое больше минимального: меньше — трещит на загруженном телефоне,
            // сильно больше — звук начинает отставать от картинки, и это слышно.
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(wantRate)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(minimum * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            track.play();

            rate = wantRate;
            channels = wantChannels;
        } catch (Exception broken) {
            track = null;
        }
    }

    synchronized void close() {
        if (track == null) {
            return;
        }

        try {
            track.pause();
            track.flush();
            track.release();
        } catch (Exception ignored) {
            // Уже мёртв — и хорошо.
        }

        track = null;
        rate = 0;
        channels = 0;
    }
}
