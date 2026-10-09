package ru.dustore.launcher;

import android.content.Context;
import java.io.InputStream;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Wine -> private PulseAudio socket -> PCM -> Android AudioTrack. No network or PC. */
final class ExAudio {
    private final Context context;
    private final Speaker speaker = new Speaker();
    private volatile boolean running;
    private Process server;
    private Process capture;

    ExAudio(Context context) { this.context = context.getApplicationContext(); }

    String start() {
        try {
            File socket = new File(Ex.runDir(context), "pulse.sock");
            // The runtime allows one game session at a time; remove only our stale socket.
            if (socket.exists()) socket.delete();
            server = Ex.launch(context, new String[] { "/usr/bin/pulseaudio",
                    "--daemonize=no", "--use-pid-file=no", "--exit-idle-time=-1",
                    "--disable-shm=yes", "-n",
                    "--load=module-native-protocol-unix socket=/run/ex/pulse.sock auth-anonymous=1",
                    "--load=module-null-sink sink_name=ex rate=48000 channels=2",
                    "--log-target=stderr" }, null);
            drain(server.getInputStream());
            long deadline = android.os.SystemClock.uptimeMillis() + 10000;
            while (!socket.exists() && ExProcess.alive(server)
                    && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50);
            if (!socket.exists() || !ExProcess.alive(server)) throw new Exception("PulseAudio не открыл сокет");
            capture = Ex.launch(context, new String[] { "/usr/bin/parec",
                    "--server=unix:/run/ex/pulse.sock", "--device=ex.monitor", "--raw",
                    "--format=s16le", "--channels=2", "--rate=48000", "--latency-msec=30" }, null, false);
            drain(capture.getErrorStream());
            running = true;
            new Thread(this::play, "ex-audio").start();
            return null;
        } catch (Exception error) {
            stop();
            return "Звук eX: " + error.getMessage();
        }
    }

    private void drain(InputStream stream) {
        new Thread(() -> {
            try (InputStream in = stream) {
                byte[] bytes = new byte[2048]; int count;
                while ((count = in.read(bytes)) > 0)
                    Ex.log(context, "аудио: " + new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }, "ex-audio-log").start();
    }

    private void play() {
        long audible = 0;
        try (InputStream in = capture.getInputStream()) {
            byte[] packet = new byte[3840 + 5]; // 20 ms at 48 kHz, stereo PCM16.
            packet[0] = 0; packet[1] = 0; packet[2] = (byte) 187; packet[3] = (byte) 128; packet[4] = 2;
            while (running) {
                int filled = 5;
                while (running && filled < packet.length) {
                    int count = in.read(packet, filled, packet.length - filled);
                    if (count < 0) return;
                    filled += count;
                }
                if (!running) return;
                speaker.play(packet);
                if (audible == 0) {
                    for (int i = 5; i < packet.length; i += 2) {
                        int sample = (short) ((packet[i] & 255) | (packet[i + 1] << 8));
                        if (Math.abs(sample) > 32) { audible++; Ex.log(context, "eX: PCM игры поступает в Android AudioTrack"); break; }
                    }
                }
            }
        } catch (Exception error) {
            if (running) Ex.log(context, "звук остановился: " + error.getMessage());
        } finally { speaker.close(); }
    }

    synchronized void stop() {
        running = false;
        if (capture != null) {
            capture.destroy();
            capture = null;
        }
        Process pulse = server;
        server = null;
        if (pulse != null) {
            // Destroying PRoot alone can leave its PulseAudio child behind. Ask the
            // server to exit over its private socket, then close the launcher process.
            new Thread(() -> {
                try {
                    Process quit = Ex.launch(context, new String[] {
                            "/usr/bin/pactl", "--server=unix:/run/ex/pulse.sock", "exit"
                    }, null, false);
                    try { ExProcess.await(quit, 2, TimeUnit.SECONDS); }
                    finally { quit.destroy(); }
                } catch (Exception ignored) { }
                pulse.destroy();
            }, "ex-audio-stop").start();
        }
        speaker.close();
    }
}
