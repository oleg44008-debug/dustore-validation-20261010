package ru.dustore.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Экран eX — наш собственный, написанный здесь целиком.
 *
 * Это то место, из-за которого систему вообще можно называть своей. Раньше картинку игре
 * давал чужой X-сервер: его ставили в контейнер, он рисовал в свой файл, мы этот файл
 * подглядывали. Работало — но экран был не наш, а взятый.
 *
 * Здесь всё наоборот. Приложение само открывает сокет и само отвечает на протокол, по
 * которому программа для Windows разговаривает с экраном (Wayland). Игра присылает нам
 * готовые кадры прямо в общую память, мы кладём их на экран телефона; касания и клавиши
 * уходят обратно тем же соединением. Между игрой и стеклом телефона нет ни одной чужой
 * программы.
 *
 * Что здесь реализовано — ровно то, что спрашивает слой Windows и ни строчкой больше:
 *
 *   * wl_display / wl_registry — знакомство и список того, что экран умеет;
 *   * wl_compositor / wl_surface / wl_region — окна и их содержимое;
 *   * wl_subcompositor / wl_subsurface — кусок окна поверх окна;
 *   * wl_shm — общая память под кадры (кадр не копируется по сокету, он просто общий);
 *   * wl_seat / wl_pointer / wl_keyboard — мышь и клавиатура;
 *   * wl_output — какой у нас экран;
 *   * xdg_wm_base / xdg_surface / xdg_toplevel / xdg_popup — окна верхнего уровня и меню;
 *   * wp_viewporter — растяжение кадра под наш размер.
 *
 * Разбор устроен так, чтобы ошибку было видно сразу: каждое сообщение — это номер объекта,
 * номер запроса и его длина; неизвестный запрос не роняет экран, а попадает в журнал.
 */
final class ExDisplay {

    /** Как экран зовут изнутри системы: XDG_RUNTIME_DIR/WAYLAND_DISPLAY. */
    static final String SOCKET_NAME = "ex-0";

    /** Что происходит на экране — для журнала и для перерисовки. */
    interface Watcher {

        void say(String line);

        /** Пришёл новый кадр или окно изменилось. */
        void changed();
    }

    private final File runDir;
    private final Watcher watcher;

    private final int screenW;
    private final int screenH;

    private volatile boolean running;

    private LocalSocket bound;
    private LocalServerSocket server;
    private Thread acceptor;

    private final List<Client> clients = new ArrayList<>();

    /** Окна снизу вверх: последнее в списке рисуется поверх. */
    private final List<Wnd> stack = new ArrayList<>();

    private int serial;
    private File keymapFile;
    private int keymapSize;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    /** Куда указывает мышь на экране игры. */
    private int pointerX;
    private int pointerY;
    private int modifiers;

    /**
     * Защёлкнутые клавиши — сейчас это только Caps Lock.
     *
     * Их состояние живёт отдельно от нажатых: если Caps уехала как «нажата», игра считает
     * её отпущенной сразу же, и большие буквы получаются только пока палец на клавише.
     */
    private int locked;

    /** Caps Lock в счёте клавиатуры. */
    private static final int LOCK_BIT = 2;

    ExDisplay(File runDir, int screenW, int screenH, Watcher watcher) {
        this.runDir = runDir;
        this.screenW = screenW;
        this.screenH = screenH;
        this.watcher = watcher;
    }

    int width() {
        return screenW;
    }

    int height() {
        return screenH;
    }

    /**
     * Открывает экран. Возвращает null при успехе или причину отказа.
     *
     * Сокет открывается в файловой системе, а не в скрытом пространстве имён Android:
     * игра ищет экран по обычному пути, и другого способа ей об этом сказать нет.
     * Публичного способа привязать серверный сокет к пути в Android нет, зато есть
     * обычный сокет, у которого можно спросить его же описатель, — из него сервер и
     * делается.
     */
    String start() {
        try {
            File socketFile = new File(runDir, SOCKET_NAME);

            if (socketFile.exists() && !socketFile.delete()) {
                return "старый сокет экрана не удаляется: " + socketFile;
            }

            // Раскладку пишем на диск: игра просит её описателем файла, чтобы не гонять
            // килобайты текста по сокету на каждом запуске.
            keymapFile = new File(runDir, "keymap.xkb");
            byte[] keymap = ExKeymap.text().getBytes(StandardCharsets.UTF_8);

            try (FileOutputStream out = new FileOutputStream(keymapFile)) {
                out.write(keymap);
                out.write(0);
            }

            keymapSize = keymap.length + 1;

            bound = new LocalSocket(LocalSocket.SOCKET_STREAM);
            bound.bind(new LocalSocketAddress(socketFile.getAbsolutePath(),
                    LocalSocketAddress.Namespace.FILESYSTEM));

            server = new LocalServerSocket(bound.getFileDescriptor());
            running = true;

            acceptor = new Thread(this::accept, "ex-display");
            acceptor.start();

            say("Экран eX открыт: " + socketFile.getAbsolutePath()
                    + " (" + screenW + "×" + screenH + ")");

            return null;
        } catch (Exception ex) {
            stop();
            return "экран не открылся: " + ex;
        }
    }

    private void accept() {
        while (running) {
            try {
                LocalSocket socket = server.accept();
                Client client = new Client(socket);

                synchronized (clients) {
                    clients.add(client);
                }

                new Thread(client, "ex-client").start();
                say("Игра подключилась к экрану.");
            } catch (Exception ex) {
                if (running) {
                    say("экран: " + ex);
                }

                break;
            }
        }
    }

    void stop() {
        running = false;

        List<Client> leaving;

        synchronized (clients) {
            // По списку идём не напрямую: закрытие клиента вычёркивает его из этого же
            // списка, и обход рассыпается на первом же шаге — а следом не закрывается и
            // сам сокет экрана, то есть он остаётся жить после выхода из игры.
            leaving = new ArrayList<>(clients);
            clients.clear();
        }

        for (Client client : leaving) {
            client.close();
        }

        synchronized (stack) {
            stack.clear();
        }

        try {
            if (server != null) {
                server.close();
            }
        } catch (Exception ignored) {
            // Закрываем на выходе — жаловаться уже некому.
        }

        try {
            if (bound != null) {
                bound.close();
            }
        } catch (Exception ignored) {
            // То же самое.
        }

        server = null;
        bound = null;
    }

    private void say(String line) {
        if (watcher != null) {
            watcher.say(line);
        }
    }

    private void changed() {
        if (watcher != null) {
            watcher.changed();
        }
    }

    /** Сколько кадров пришло в окна самой игры, а не в рабочий стол. */
    private final java.util.concurrent.atomic.AtomicInteger gameFrames = new java.util.concurrent.atomic.AtomicInteger();

    int gameFrames() {
        return gameFrames.get();
    }

    /** Есть ли хоть одно окно с картинкой. */
    boolean hasPicture() {
        synchronized (stack) {
            for (Wnd window : stack) {
                if (window.bitmap != null) {
                    return true;
                }
            }
        }

        return false;
    }

    /** Название окна игры — то, что она сама о себе сказала. */
    String title() {
        synchronized (stack) {
            for (int i = stack.size() - 1; i >= 0; i--) {
                if (stack.get(i).title != null) {
                    return stack.get(i).title;
                }
            }
        }

        return null;
    }

    /**
     * Рисует всё, что показывает игра, вписывая экран игры в отведённое место.
     *
     * Вписываем с сохранением пропорций: растянутая на весь телефон картинка 4:3 выглядит
     * сломанной, а игра о размерах телефона ничего не знает и знать не должна.
     */
    void paint(Canvas canvas, int viewW, int viewH) {
        canvas.drawColor(Color.BLACK);

        float scale = Math.min(viewW / (float) screenW, viewH / (float) screenH);
        int drawW = Math.round(screenW * scale);
        int drawH = Math.round(screenH * scale);
        int left = (viewW - drawW) / 2;
        int top = (viewH - drawH) / 2;

        synchronized (stack) {
            for (int i = 0; i < stack.size(); i++) {
                Wnd window = stack.get(i);
                Bitmap bitmap = window.bitmap;

                if (bitmap == null || bitmap.isRecycled()) {
                    continue;
                }

                int atX = window.originX();
                int atY = window.originY();

                Rect target = window.paintTarget;
                target.set(
                        left + Math.round(atX * scale),
                        top + Math.round(atY * scale),
                        left + Math.round((atX + window.showW()) * scale),
                        top + Math.round((atY + window.showH()) * scale));

                canvas.drawBitmap(bitmap, null, target, paint);
            }
        }
    }

    /** Два кадра экрана по очереди: пока один показывают, в другой рисуем. */
    private final Bitmap[] screens = new Bitmap[2];
    private final Canvas[] screenCanvases = new Canvas[2];
    private int screenTurn;

    /**
     * Весь экран игры одной картинкой — окна в порядке показа, поверх чёрного.
     *
     * Нужно паду: он рисует кадр и кнопки поверх него одним движением, и ему нужна одна
     * картинка, а не список окон. Два буфера по очереди: пока пад рисует прежний, новый
     * собирается в другой, и картинка не рвётся пополам.
     */
    Bitmap compose() {
        screenTurn ^= 1;
        Bitmap screen = screens[screenTurn];

        if (screen == null) {
            screen = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888);
            screens[screenTurn] = screen;
            screenCanvases[screenTurn] = new Canvas(screen);
        }

        Canvas canvas = screenCanvases[screenTurn];
        canvas.drawColor(Color.BLACK);

        synchronized (stack) {
            for (int i = 0; i < stack.size(); i++) {
                Wnd window = stack.get(i);
                Bitmap bitmap = window.bitmap;

                if (bitmap == null || bitmap.isRecycled()) {
                    continue;
                }

                int atX = window.originX();
                int atY = window.originY();

                window.composeTarget.set(atX, atY, atX + window.showW(), atY + window.showH());
                canvas.drawBitmap(bitmap, null, window.composeTarget, paint);
            }
        }

        return screen;
    }

    /** Обратный перевод: точка на экране телефона → точка на экране игры. */
    int[] toGame(float viewX, float viewY, int viewW, int viewH) {
        float scale = Math.min(viewW / (float) screenW, viewH / (float) screenH);
        int drawW = Math.round(screenW * scale);
        int drawH = Math.round(screenH * scale);
        int left = (viewW - drawW) / 2;
        int top = (viewH - drawH) / 2;

        int x = Math.round((viewX - left) / scale);
        int y = Math.round((viewY - top) / scale);

        return new int[] {
                Math.max(0, Math.min(screenW - 1, x)),
                Math.max(0, Math.min(screenH - 1, y))
        };
    }

    // ------------------------------------------------------------------
    // Ввод: то, что человек делает пальцем, — в том виде, в каком этого ждёт игра.
    // ------------------------------------------------------------------

    void pointerMove(int x, int y) {
        Wnd target = focused();

        if (target != null && target.client.lockedPointer != 0) {
            // Абсолютная координата при захвате мыши не имеет смысла. Прежняя
            // разность со старым pointerX превращала каждый тап в огромный поворот.
            return;
        }

        pointerX = x;
        pointerY = y;

        if (target == null || target.client.pointer == 0) {
            return;
        }

        Client client = target.client;
        int time = (int) SystemClock.uptimeMillis();

        client.enterPointer(target);
        client.send(new Msg(client.pointer, 2).u(time)
                .f(x - target.originX()).f(y - target.originY()));
        client.pointerFrame();
    }

    /**
     * Сдвиг мыши — то, чем смотрят по сторонам в играх от первого лица.
     *
     * Такая игра запирает указатель (zwp_locked_pointer) и читает только сдвиги
     * (zwp_relative_pointer): пока указатель заперт, обычных движений посылать нельзя —
     * по протоколу их и не бывает. Незапертому окну уходят и сдвиг, и новое место.
     */
    void relativeMove(int dx, int dy) {
        Wnd target = focused();

        if (target == null || target.client.pointer == 0) {
            return;
        }

        Client client = target.client;
        client.enterPointer(target);

        if (client.relativePointer != 0) {
            long micro = SystemClock.uptimeMillis() * 1000L;
            client.send(new Msg(client.relativePointer, 0)
                    .u((int) (micro >>> 32)).u((int) micro)
                    .f(dx).f(dy).f(dx).f(dy));
        }

        if (client.lockedPointer == 0) {
            pointerX = Math.max(0, Math.min(screenW - 1, pointerX + dx));
            pointerY = Math.max(0, Math.min(screenH - 1, pointerY + dy));
            client.send(new Msg(client.pointer, 2).u((int) SystemClock.uptimeMillis())
                    .f(pointerX - target.originX()).f(pointerY - target.originY()));
        }

        client.pointerFrame();
    }

    /** Заперт ли указатель игрой — тогда тап по экрану не переставляет его. */
    boolean locked() {
        Wnd target = focused();
        return target != null && target.client.lockedPointer != 0;
    }

    /** Кнопка мыши в номерах ядра Linux: 0x110 левая, 0x111 правая, 0x112 средняя. */
    void pointerButton(int button, boolean down) {
        Wnd target = focused();

        if (target == null || target.client.pointer == 0) {
            return;
        }

        Client client = target.client;
        client.enterPointer(target);
        client.send(new Msg(client.pointer, 3)
                .u(++serial).u((int) SystemClock.uptimeMillis())
                .u(button).u(down ? 1 : 0));
        client.pointerFrame();
    }

    void wheel(boolean up) {
        Wnd target = focused();

        if (target == null || target.client.pointer == 0) {
            return;
        }

        Client client = target.client;
        client.enterPointer(target);

        int time = (int) SystemClock.uptimeMillis();

        // Слой Windows считает щелчки колеса, а не пройденное расстояние: обработчик
        // «прокрутилось столько-то» у него пустой, и одно это сообщение колесо не
        // включает. Поэтому говорим полностью: откуда прокрутка, на сколько и сколько
        // это щелчков.
        client.send(new Msg(client.pointer, 6).u(0));                    // источник — колесо
        client.send(new Msg(client.pointer, 4).u(time).u(0).f(up ? -10 : 10));
        client.send(new Msg(client.pointer, 8).u(0).i(up ? -1 : 1));     // щелчков
        client.pointerFrame();
    }

    /** Клавиша по имени с панели управления. */
    boolean key(String name, boolean down) {
        int code = ExKeymap.code(name);

        if (code < 0) {
            return false;
        }

        int bit = ExKeymap.modifierBit(name);

        if (bit == LOCK_BIT) {
            if (down) {
                locked ^= LOCK_BIT;
            }
        } else if (bit != 0) {
            modifiers = down ? (modifiers | bit) : (modifiers & ~bit);
        }

        Wnd target = focused();

        if (target == null || target.client.keyboard == 0) {
            return false;
        }

        Client client = target.client;
        client.enterKeyboard(target);

        if (bit != 0) {
            client.send(new Msg(client.keyboard, 4)
                    .u(++serial).u(modifiers).u(0).u(locked).u(0));
        }

        // Номер клавиши в протоколе — тот же, что в ядре: без наших восьми.
        client.send(new Msg(client.keyboard, 3)
                .u(++serial).u((int) SystemClock.uptimeMillis())
                .u(code - 8).u(down ? 1 : 0));

        return true;
    }

    /** Верхнее окно с картинкой — оно и получает ввод. */
    private Wnd focused() {
        synchronized (stack) {
            for (int i = stack.size() - 1; i >= 0; i--) {
                Wnd window = stack.get(i);

                if (window.bitmap != null && window.drawable() && window.client.alive) {
                    return window;
                }
            }
        }

        return null;
    }

    // ------------------------------------------------------------------
    // Окно
    // ------------------------------------------------------------------

    /** Окно игры: кадр, место и всё, что о нём известно. */
    private final class Wnd {

        final Client client;
        final int surfaceId;

        int xdgSurface;
        int toplevel;
        int popup;

        int x;
        int y;

        /** Родитель для подповерхности: её место считается от него. */
        Wnd parent;

        /** Поверхность мыши: её рисует не игра, а мы, и окном она не является. */
        boolean cursor;

        String title;

        Bitmap bitmap;

        /** Кадр целиком в виде чисел — чтобы не выделять его заново на каждый кадр. */
        int[] pixels;
        final Rect composeTarget = new Rect();
        final Rect paintTarget = new Rect();

        /** Первый большой кадр уже записан в журнал — второй раз не повторяем. */
        boolean reported;

        /**
         * Кусок кадра и размер показа — то, что просит wp_viewport.
         *
         * Слой Windows переиспользует один кусок памяти под разные размеры окна и
         * рассчитывает, что лишнее по правому и нижнему краю обрежется. Без этого по краям
         * едет мусор от предыдущего кадра.
         */
        int srcX;
        int srcY;
        int srcW;
        int srcH;
        int dstW;
        int dstH;

        boolean configured;
        boolean primary;

        Buf pending;
        boolean pendingSet;

        /** Окно ли это вообще: без роли рисовать нечего и ввод отдавать некому. */
        boolean drawable() {
            return !cursor && (xdgSurface != 0 || parent != null);
        }

        /** Место на экране игры: подповерхность стоит от родителя, а не от угла экрана. */
        int originX() {
            int sum = 0;
            Wnd at = this;

            for (int guard = 0; at != null && guard < 8; guard++) {
                sum += at.x;
                at = at.parent;
            }

            return sum;
        }

        int originY() {
            int sum = 0;
            Wnd at = this;

            for (int guard = 0; at != null && guard < 8; guard++) {
                sum += at.y;
                at = at.parent;
            }

            return sum;
        }

        int showW() {
            return dstW > 0 ? dstW : (bitmap == null ? 0 : bitmap.getWidth());
        }

        int showH() {
            return dstH > 0 ? dstH : (bitmap == null ? 0 : bitmap.getHeight());
        }

        final List<Integer> frames = new ArrayList<>();

        Wnd(Client client, int surfaceId) {
            this.client = client;
            this.surfaceId = surfaceId;
        }
    }

    /** Кусок общей памяти, в который игра рисует. */
    private static final class Pool {

        FileInputStream stream;
        FileChannel channel;
        MappedByteBuffer map;
        int size;

        /**
         * Сколько ещё держатся за эту память.
         *
         * Обычная привычка игры — попросить память, нарезать из неё кадры и тут же
         * сказать «память больше не нужна»: по правилам кадр держит её сам. Мы раньше
         * снимали отображение сразу — и кадры оставались без памяти, то есть экран
         * оставался чёрным при формально исправном разговоре.
         */
        int refs = 1;

        void remap(int size) {
            try {
                this.size = size;
                map = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
                map.order(ByteOrder.LITTLE_ENDIAN);
            } catch (Exception broken) {
                map = null;
            }
        }

        /** Один держатель отпустил память. Последний — снимает отображение. */
        void release() {
            if (refs > 0) {
                refs--;
            }

            if (refs <= 0) {
                close();
            }
        }

        void close() {
            try {
                refs = 0;
                map = null;

                if (stream != null) {
                    stream.close();
                    stream = null;
                }
            } catch (Exception ignored) {
                // Память освободит сборщик — держать её дальше незачем.
            }
        }
    }

    /** Один кадр внутри общей памяти. */
    private static final class Buf {

        Pool pool;
        int offset;
        int width;
        int height;
        int stride;
        int format;
        int id;
        private MappedByteBuffer mapped;
        private IntBuffer words;

        IntBuffer words(MappedByteBuffer source) {
            // A pool can grow and be remapped; never read a view of the previous mapping.
            if (mapped != source || words == null) {
                mapped = source;
                // Compile with a modern JDK, but bind the descriptors supported on
                // old Android: covariant MappedByteBuffer.duplicate() is absent there.
                ByteBuffer bytes = ((ByteBuffer) source).duplicate();
                ((java.nio.Buffer) bytes).clear(); bytes.order(ByteOrder.LITTLE_ENDIAN);
                words = bytes.asIntBuffer();
            }
            ((java.nio.Buffer) words).clear();
            return words;
        }

        /**
         * Кадр уничтожен игрой.
         *
         * Номер уничтоженного объекта игра почти сразу выдаёт другому, и «кадр свободен»,
         * посланное вдогонку, уезжает на чужой объект. Для клиентской части протокола это
         * непоправимая ошибка: она рвёт связь целиком.
         */
        boolean dead;
    }

    /** Объект протокола: у каждого свой номер и своё имя. */
    private static final class Res {

        int id;
        String iface;
        int version;

        Wnd window;
        Pool pool;
        Buf buffer;

        int anchorX;
        int anchorY;
        int anchorW;
        int anchorH;
        int sizeW;
        int sizeH;
    }

    // ------------------------------------------------------------------
    // Клиент
    // ------------------------------------------------------------------

    private final class Client implements Runnable {

        private final LocalSocket socket;
        private final InputStream in;
        private final OutputStream out;

        // Список объектов правит поток связи с игрой, а читают его и поток разметки
        // (закрытие экрана, ответ о размере окна). Обычный список от этого рассыпается на
        // ходу, поэтому здесь тот, что рассчитан на два потока сразу.
        private final Map<Integer, Res> objects = new ConcurrentHashMap<>();
        private final ArrayDeque<FileDescriptor> inbox = new ArrayDeque<>();

        volatile boolean alive = true;

        int pointer;
        int keyboard;

        /** Объекты сдвигов и запирания мыши, если игра их попросила. */
        int relativePointer;
        int lockedPointer;
        int confinedPointer;

        private Wnd pointerIn;
        private Wnd keyboardIn;
        private long lastFrameCallback;

        Client(LocalSocket socket) throws Exception {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();

            Res display = new Res();
            display.id = 1;
            display.iface = "wl_display";
            display.version = 1;
            objects.put(1, display);
        }

        @Override
        public void run() {
            byte[] head = new byte[8];

            try {
                while (alive) {
                    if (!readFully(head, 0, 8)) {
                        break;
                    }

                    ByteBuffer header = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
                    int id = header.getInt();
                    int word = header.getInt();
                    int opcode = word & 0xFFFF;
                    int size = (word >>> 16) & 0xFFFF;

                    if (size < 8 || size > 1 << 20) {
                        say("экран: сообщение странной длины " + size);
                        break;
                    }

                    byte[] body = new byte[size - 8];

                    if (!readFully(body, 0, body.length)) {
                        break;
                    }

                    ByteBuffer args = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                    Res target = objects.get(id);

                    if (target == null) {
                        continue;
                    }

                    try {
                        dispatch(target, opcode, args);
                    } catch (Exception broken) {
                        say("экран: " + target.iface + " №" + opcode + " — " + broken);
                    }
                }
            } catch (Exception ex) {
                if (alive) {
                    say("экран: связь с игрой оборвалась (" + ex + ")");
                }
            } finally {
                close();
            }
        }

        /**
         * Читает ровно столько, сколько просили, и по дороге подбирает описатели файлов.
         *
         * Описатели приезжают не в теле сообщения, а сбоку от него — так устроена передача
         * общей памяти между программами. Забрать их надо сразу после чтения, иначе
         * следующее чтение их затрёт, и кадр окажется без памяти.
         */
        private boolean readFully(byte[] target, int offset, int length) throws Exception {
            int done = 0;

            while (done < length) {
                int got = in.read(target, offset + done, length - done);

                FileDescriptor[] arrived = socket.getAncillaryFileDescriptors();

                if (arrived != null) {
                    for (FileDescriptor fd : arrived) {
                        if (fd != null) {
                            inbox.add(fd);
                        }
                    }
                }

                if (got <= 0) {
                    return false;
                }

                done += got;
            }

            return true;
        }

        synchronized void send(Msg message) {
            if (!alive) {
                return;
            }

            try {
                out.write(message.bytes());
                out.flush();
            } catch (Exception broken) {
                alive = false;
            }
        }

        /** Сообщение с описателем файла — так уезжает раскладка клавиатуры. */
        synchronized void sendWithFd(Msg message, FileDescriptor fd) {
            if (!alive) {
                return;
            }

            try {
                socket.setFileDescriptorsForSend(new FileDescriptor[] { fd });
                out.write(message.bytes());
                out.flush();
                socket.setFileDescriptorsForSend(null);
            } catch (Exception broken) {
                alive = false;
            }
        }

        void close() {
            if (!alive) {
                return;
            }

            alive = false;

            synchronized (stack) {
                for (int i = stack.size() - 1; i >= 0; i--) {
                    if (stack.get(i).client == this) {
                        stack.remove(i);
                    }
                }
            }

            for (Res res : objects.values()) {
                if (res.pool != null) {
                    res.pool.close();
                }
            }

            try {
                socket.close();
            } catch (Exception ignored) {
                // Уже закрывается — второй раз не хуже.
            }

            synchronized (clients) {
                clients.remove(this);
            }

            changed();
        }

        private Res make(int id, String iface, int version) {
            Res res = new Res();
            res.id = id;
            res.iface = iface;
            res.version = version;
            objects.put(id, res);
            return res;
        }

        private void drop(Res res) {
            objects.remove(res.id);

            // Номер объекта возвращается игре: без этого её собственный учёт объектов
            // рано или поздно упирается в «этот номер уже занят» и она падает.
            if (res.id < 0xFF000000) {
                send(new Msg(1, 1).u(res.id));
            }
        }

        void pointerFrame() {
            if (pointer != 0) {
                send(new Msg(pointer, 5));
            }
        }

        void enterPointer(Wnd window) {
            if (pointer == 0 || pointerIn == window) {
                return;
            }

            if (pointerIn != null) {
                send(new Msg(pointer, 1).u(++serial).u(pointerIn.surfaceId));
            }

            pointerIn = window;
            send(new Msg(pointer, 0).u(++serial).u(window.surfaceId)
                    .f(pointerX - window.originX()).f(pointerY - window.originY()));
        }

        void enterKeyboard(Wnd window) {
            if (keyboard == 0 || keyboardIn == window) {
                return;
            }

            if (keyboardIn != null) {
                send(new Msg(keyboard, 2).u(++serial).u(keyboardIn.surfaceId));
            }

            keyboardIn = window;
            send(new Msg(keyboard, 1).u(++serial).u(window.surfaceId).arr(new byte[0]));
            send(new Msg(keyboard, 4).u(++serial).u(modifiers).u(0).u(locked).u(0));
        }

        // --------------------------------------------------------------
        // Разбор запросов
        // --------------------------------------------------------------

        private void dispatch(Res res, int op, ByteBuffer args) throws Exception {
            switch (res.iface) {
                case "wl_display":
                    display(op, args);
                    return;
                case "wl_registry":
                    if (op == 0) {
                        bind(args);
                    }
                    return;
                case "wl_compositor":
                    compositor(op, args);
                    return;
                case "wl_shm":
                    shm(op, args);
                    return;
                case "wl_shm_pool":
                    shmPool(res, op, args);
                    return;
                case "wl_buffer":
                    if (op == 0) {
                        buffer(res);
                    }
                    return;
                case "wl_surface":
                    surface(res, op, args);
                    return;
                case "wl_region":
                    if (op == 0) {
                        drop(res);
                    }
                    return;
                case "wl_seat":
                    seat(op, args);
                    return;
                case "wl_pointer":
                    if (op == 0) {
                        setCursor(args);
                    } else if (op == 1) {
                        pointer = 0;
                        drop(res);
                    }
                    return;
                case "wl_keyboard":
                    if (op == 0) {
                        keyboard = 0;
                        drop(res);
                    }
                    return;
                case "wl_output":
                    if (op == 0) {
                        drop(res);
                    }
                    return;
                case "xdg_wm_base":
                    wmBase(res, op, args);
                    return;
                case "xdg_positioner":
                    positioner(res, op, args);
                    return;
                case "xdg_surface":
                    xdgSurface(res, op, args);
                    return;
                case "xdg_toplevel":
                    toplevel(res, op, args);
                    return;
                case "xdg_popup":
                    if (op == 0) {
                        drop(res);
                    }
                    return;
                case "wp_viewporter":
                    if (op == 0) {
                        drop(res);
                    } else if (op == 1) {
                        int id = args.getInt();
                        Res surface = objects.get(args.getInt());
                        Res holder = make(id, "wp_viewport", 1);

                        if (surface != null) {
                            holder.window = surface.window;
                        }
                    }
                    return;
                case "wp_viewport":
                    viewport(res, op, args);
                    return;
                case "wl_subcompositor":
                    subcompositor(res, op, args);
                    return;
                case "wl_subsurface":
                    subsurface(res, op, args);
                    return;
                case "zwp_relative_pointer_manager_v1":
                    if (op == 0) {
                        drop(res);
                    } else if (op == 1) {
                        relativePointer = args.getInt();
                        make(relativePointer, "zwp_relative_pointer_v1", 1);
                    }
                    return;
                case "zwp_relative_pointer_v1":
                    if (op == 0) {
                        relativePointer = 0;
                        drop(res);
                    }
                    return;
                case "zwp_pointer_constraints_v1":
                    constraints(res, op, args);
                    return;
                case "zwp_locked_pointer_v1":
                    if (op == 0) {
                        lockedPointer = 0;
                        drop(res);
                    }
                    // set_cursor_position_hint и set_region принимаем молча.
                    return;
                case "zwp_confined_pointer_v1":
                    if (op == 0) {
                        confinedPointer = 0;
                        drop(res);
                    }
                    return;
                default:
                    return;
            }
        }

        /**
         * Запирание и ограничение указателя.
         *
         * Игра от первого лица просит запереть указатель — и с этого момента читает
         * только сдвиги. Отвечаем «заперто» сразу: другого указателя у нас нет, и спорить
         * не о чем. Ограничение окном — то же самое: указатель и так не выходит за экран.
         */
        private void constraints(Res res, int op, ByteBuffer args) {
            if (op == 0) {
                drop(res);
                return;
            }

            if (op == 1) {
                int id = args.getInt();
                make(id, "zwp_locked_pointer_v1", 1);
                lockedPointer = id;
                send(new Msg(id, 0));                    // locked
                say("игра заперла указатель — смотрим по сторонам сдвигами");
                return;
            }

            if (op == 2) {
                int id = args.getInt();
                make(id, "zwp_confined_pointer_v1", 1);
                confinedPointer = id;
                send(new Msg(id, 0));                    // confined
            }
        }

        private void display(int op, ByteBuffer args) {
            if (op == 0) {
                // sync: игра спрашивает «всё ли ты услышал» — отвечаем сразу же.
                int id = args.getInt();
                send(new Msg(id, 0).u(++serial));
                send(new Msg(1, 1).u(id));
                return;
            }

            if (op == 1) {
                int id = args.getInt();
                make(id, "wl_registry", 1);

                for (Global global : GLOBALS) {
                    send(new Msg(id, 0).u(global.name).s(global.iface).u(global.version));
                }
            }
        }

        private void bind(ByteBuffer args) {
            int name = args.getInt();
            String iface = string(args);
            int version = args.getInt();
            int id = args.getInt();

            Global known = null;

            for (Global global : GLOBALS) {
                if (global.iface.equals(iface)) {
                    known = global;
                    break;
                }
            }

            if (known == null) {
                say("экран: у нас не спрашивали про " + iface);
            }

            // Разговариваем по младшему из двух изводов: игра не должна получить от нас
            // событие, которого в её изводе ещё нет.
            int agreed = known == null ? version : Math.min(known.version, version);
            make(id, iface, agreed);

            if ("wl_shm".equals(iface)) {
                // Мы принимаем кадры двух видов: с прозрачностью и без неё. Больше и не
                // нужно — всё остальное игра умеет привести к этим двум сама.
                //
                // Оба вида называются ОДНИМ И ТЕМ ЖЕ событием «вот такой вид я умею»
                // (номер 0), просто сказанным дважды. Раньше второй уезжал под номером 1 —
                // события с таким номером у общей памяти нет вовсе, и клиентская часть
                // протокола на неизвестном номере рвёт связь целиком, без объяснений.
                // Снаружи это выглядело как «игра подключилась и тут же отвалилась».
                send(new Msg(id, 0).u(0));            // с прозрачностью
                send(new Msg(id, 0).u(1));            // без неё
                return;
            }

            if ("wl_seat".equals(iface)) {
                send(new Msg(id, 0).u(3));            // мышь и клавиатура

                // Имя сиденья появилось только во втором изводе: в первом такого события
                // нет, и оно оборвало бы связь так же, как лишний вид памяти выше.
                if (agreed >= 2) {
                    send(new Msg(id, 1).s("eX"));
                }

                return;
            }

            if ("wl_output".equals(iface)) {
                send(new Msg(id, 0).i(0).i(0)
                        .i(screenW / 4).i(screenH / 4)
                        .i(0).s("eXepc").s("eX").i(0));
                send(new Msg(id, 1).u(3).i(screenW).i(screenH).i(60000));

                // Масштаб и «это всё» — тоже со второго извода.
                if (agreed >= 2) {
                    send(new Msg(id, 3).i(1));
                    send(new Msg(id, 2));
                }
            }
        }

        private void compositor(int op, ByteBuffer args) {
            if (op == 0) {
                int id = args.getInt();
                Res res = make(id, "wl_surface", 4);
                res.window = new Wnd(this, id);
                return;
            }

            if (op == 1) {
                make(args.getInt(), "wl_region", 1);
            }
        }

        private void shm(int op, ByteBuffer args) {
            if (op != 0) {
                return;
            }

            int id = args.getInt();
            int size = args.getInt();
            FileDescriptor fd = inbox.poll();

            Res res = make(id, "wl_shm_pool", 1);

            if (fd == null) {
                say("экран: игра просит память, но не дала её.");
                return;
            }

            Pool pool = new Pool();
            pool.stream = new FileInputStream(fd);
            pool.channel = pool.stream.getChannel();
            pool.remap(size);

            res.pool = pool;
        }

        private void shmPool(Res res, int op, ByteBuffer args) {
            if (op == 0) {
                int id = args.getInt();

                Buf buf = new Buf();
                buf.pool = res.pool;
                buf.offset = args.getInt();
                buf.width = args.getInt();
                buf.height = args.getInt();
                buf.stride = args.getInt();
                buf.format = args.getInt();
                buf.id = id;

                if (buf.pool != null) {
                    buf.pool.refs++;
                }

                Res holder = make(id, "wl_buffer", 1);
                holder.buffer = buf;
                return;
            }

            if (op == 1) {
                // «Память больше не нужна» — но кадры, нарезанные из неё, ещё живы и
                // держат её сами. Снимем отображение, когда уйдёт последний.
                if (res.pool != null) {
                    res.pool.release();
                }

                drop(res);
                return;
            }

            if (op == 2 && res.pool != null) {
                res.pool.remap(args.getInt());
            }
        }

        /** Кадр уничтожен: отпускаем память и запоминаем, что посылать сюда больше нечего. */
        private void buffer(Res res) {
            Buf buf = res.buffer;

            if (buf != null) {
                buf.dead = true;
                buf.mapped = null; buf.words = null;

                if (buf.pool != null) {
                    buf.pool.release();
                }

                synchronized (stack) {
                    for (Wnd window : stack) {
                        if (window.pending == buf) {
                            window.pending = null;
                        }
                    }
                }
            }

            drop(res);
        }

        /**
         * Игра дала свою картинку курсора.
         *
         * Рисовать её мы не будем — на телефоне курсора нет, палец сам себе указатель. Но
         * поверхность из этого запроса надо пометить: она обычная, у неё нет роли окна, и
         * без метки она попадала бы в общий список окон и рисовалась в левом верхнем углу
         * поверх игры, забирая себе ещё и весь ввод.
         */
        private void setCursor(ByteBuffer args) {
            args.getInt();
            Res surface = objects.get(args.getInt());

            if (surface == null || surface.window == null) {
                return;
            }

            surface.window.cursor = true;

            synchronized (stack) {
                stack.remove(surface.window);
            }
        }

        /** Кусок кадра и размер показа. */
        private void viewport(Res res, int op, ByteBuffer args) {
            if (op == 0) {
                if (res.window != null) {
                    res.window.srcW = 0;
                    res.window.srcH = 0;
                    res.window.dstW = 0;
                    res.window.dstH = 0;
                }

                drop(res);
                return;
            }

            if (res.window == null) {
                return;
            }

            if (op == 1) {
                // Дробные числа протокола: целая часть и 8 бит после запятой.
                res.window.srcX = args.getInt() >> 8;
                res.window.srcY = args.getInt() >> 8;
                res.window.srcW = args.getInt() >> 8;
                res.window.srcH = args.getInt() >> 8;
                return;
            }

            if (op == 2) {
                res.window.dstW = args.getInt();
                res.window.dstH = args.getInt();
            }
        }

        /** Подповерхности: кусок окна, который живёт своей жизнью поверх родителя. */
        private void subcompositor(Res res, int op, ByteBuffer args) {
            if (op == 0) {
                drop(res);
                return;
            }

            if (op != 1) {
                return;
            }

            int id = args.getInt();
            Res surface = objects.get(args.getInt());
            Res parent = objects.get(args.getInt());

            Res holder = make(id, "wl_subsurface", 1);

            if (surface != null && surface.window != null) {
                holder.window = surface.window;

                if (parent != null && parent.window != null
                        && parent.window != surface.window) {
                    surface.window.parent = parent.window;
                }
            }
        }

        private void subsurface(Res res, int op, ByteBuffer args) {
            if (op == 0) {
                if (res.window != null) {
                    res.window.parent = null;

                    synchronized (stack) {
                        stack.remove(res.window);
                    }
                }

                drop(res);
                changed();
                return;
            }

            if (op == 1 && res.window != null) {
                res.window.x = args.getInt();
                res.window.y = args.getInt();
                changed();
            }

            // place_above, place_below, set_sync, set_desync: порядок у нас определяется
            // порядком показа, а рисуем мы всегда по последнему кадру.
        }

        private void surface(Res res, int op, ByteBuffer args) {
            Wnd window = res.window;

            switch (op) {
                case 0:
                    synchronized (stack) {
                        stack.remove(window);
                    }

                    drop(res);
                    changed();
                    return;

                case 1: {
                    int bufferId = args.getInt();
                    Res holder = objects.get(bufferId);
                    window.pending = holder == null ? null : holder.buffer;
                    window.pendingSet = true;
                    return;
                }

                case 3: {
                    int id = args.getInt();
                    make(id, "wl_callback", 1);

                    synchronized (window.frames) {
                        window.frames.add(id);
                    }

                    return;
                }

                case 6:
                    commit(window);
                    return;

                default:
                    // damage, регионы, поворот, масштаб — принимаем и не спорим: кадр мы
                    // всё равно переносим целиком, а поворотов у нас не бывает.
                    return;
            }
        }

        /**
         * Кадр приехал.
         *
         * Здесь всё содержимое общей памяти переносится в картинку телефона. Копия нужна:
         * сразу после неё память возвращается игре, и та рисует туда следующий кадр, пока
         * мы показываем этот. Без копии игра переписывала бы кадр прямо во время показа —
         * картинка рвалась бы пополам.
         */
        private void commit(Wnd window) {
            if (window.xdgSurface != 0 && !window.configured) {
                configure(window);
                frames(window);
                return;
            }

            Buf buf = window.pending;

            if (window.pendingSet && buf == null) {
                window.bitmap = null;
                window.pendingSet = false;

                synchronized (stack) {
                    stack.remove(window);
                }

                frames(window);
                changed();
                return;
            }

            window.pendingSet = false;

            // Кадр отдаётся ровно один раз. Дальше игра часто присылает подтверждение без
            // нового кадра — и раньше мы копировали тот же кусок памяти второй раз и второй
            // раз говорили «кадр свободен». Если игра к тому времени кадр уничтожила, это
            // слово уезжало на чужой объект и связь рвалась целиком.
            window.pending = null;

            boolean receivedPixels = buf != null && !buf.dead
                    && buf.pool != null && buf.pool.map != null;
            if (receivedPixels) {
                copy(window, buf);

                if (!buf.dead) {
                    send(new Msg(buf.id, 0));
                }
            }

            frames(window);
            // A commit can acknowledge a frame callback without attaching pixels.
            // Redrawing the whole phone screen for those empty Wine commits burns
            // CPU and GPU time while the game is still rendering its next frame.
            if (receivedPixels) changed();
        }

        /**
         * «Рисуй дальше» — ответ на просьбу сообщить, когда кадр показан.
         *
         * Отвечать надо всегда, даже когда показывать было нечего: игра, которая
         * придерживает отрисовку до этого ответа, иначе встаёт навсегда.
         */
        private void frames(Wnd window) {
            List<Integer> ready;

            synchronized (window.frames) {
                if (window.frames.isEmpty()) {
                    return;
                }

                ready = new ArrayList<>(window.frames);
                window.frames.clear();
            }

            // Wayland clients pace rendering from these callbacks. Without pacing,
            // Wine can render as fast as the CPU allows even though the phone only
            // displays 60 frames per second, heating the device and starving apps.
            long rest = UltraPolicy.waitNanos(System.nanoTime(), lastFrameCallback, AndroidUltra.frameBudgetNanos());
            if (rest > 0) {
                try { Thread.sleep(rest / 1_000_000L, (int) (rest % 1_000_000L)); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
            }
            lastFrameCallback = System.nanoTime();

            for (int id : ready) {
                send(new Msg(id, 0).u((int) SystemClock.uptimeMillis()));
                send(new Msg(1, 1).u(id));
            }
        }

        private void copy(Wnd window, Buf buf) {
            // Берём не весь кусок памяти, а тот прямоугольник, который назвала игра.
            // Слой Windows держит один кусок под окно любого размера и рассчитывает, что
            // лишнее справа и снизу мы отрежем сами; без этого по краям едет мусор от
            // прошлого кадра.
            int fromX = window.srcW > 0 ? Math.max(0, window.srcX) : 0;
            int fromY = window.srcH > 0 ? Math.max(0, window.srcY) : 0;

            int width = window.srcW > 0 ? window.srcW : buf.width;
            int height = window.srcH > 0 ? window.srcH : buf.height;

            width = Math.min(width, buf.width - fromX);
            height = Math.min(height, buf.height - fromY);

            // A corrupt or unusually large first window must not allocate hundreds
            // of megabytes and force Android to kill this app or a VPN service.
            if (width <= 0 || height <= 0 || width > 2560 || height > 1440
                    || (long) width * height > 2560L * 1440) {
                return;
            }

            MappedByteBuffer map = buf.pool.map;
            long firstByte = (long) buf.offset + (long) fromY * buf.stride + (long) fromX * 4;
            if ((long) (fromX + width) * 4 > buf.stride) return;
            if (!FrameCopyBounds.valid(width, height, firstByte, buf.stride, map.capacity())) return;

            Bitmap bitmap = window.bitmap;

            if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }

            // Кадр переносится целиком одним вызовом, а не построчно: семьсот вызовов
            // setPixels на кадр — это семьсот переходов в нативный код, и на них уходило
            // больше времени, чем на сам перенос. Теперь строки читаются в один массив,
            // а картинке он отдаётся один раз.
            int[] all = window.pixels;

            if (all == null || all.length != width * height) {
                all = new int[width * height];
                window.pixels = all;
            }

            // One cached view per protocol buffer, invalidated when its pool is remapped.
            IntBuffer words = buf.words(map);
            if (buf.stride == width * 4) {
                // Packed full-width buffers are the usual game path: one bulk transfer
                // replaces hundreds of per-row IntBuffer bounds/copy operations.
                ((java.nio.Buffer) words).position((int) (firstByte >>> 2));
                words.get(all, 0, all.length);
            } else for (int y = 0; y < height; y++) {
                int at = buf.offset + (y + fromY) * buf.stride + fromX * 4;

                if (at < 0 || (at & 3) != 0 || at + width * 4 > map.capacity()) {
                    return;
                }

                ((java.nio.Buffer) words).position(at >>> 2);
                words.get(all, y * width, width);
            }

            // Непрозрачный кадр приезжает с нулём вместо прозрачности — его надо
            // дописать, иначе игра окажется полностью прозрачной, то есть чёрной. А вот у
            // кадра с прозрачностью она настоящая, и затирать её нельзя.
            if (buf.format == 1) {
                for (int i = 0; i < all.length; i++) {
                    all[i] |= 0xFF000000;
                }
            }

            // compose()/paint() read the same bitmap under this lock. Publishing or
            // changing pixels outside it races the renderer when overlays redraw.
            synchronized (stack) {
                bitmap.setPixels(all, 0, width, 0, 0, width, height);
                window.bitmap = bitmap;
            }

            // Кадры самой игры — это окна, лежащие на рабочем столе, а не сам стол:
            // первое окно верхнего уровня у нас всегда стол Wine.
            if (window.parent != null || !window.primary) {
                gameFrames.incrementAndGet();
            }

            if (!window.reported && width >= 320) {
                window.reported = true;
                say("кадр " + width + "×" + height
                        + (buf.format == 1 ? " без прозрачности" : ""));
            }

            synchronized (stack) {
                // В список показа попадает только то, у чего есть роль окна: поверхность
                // курсора или ещё не получившая роли поверхность иначе рисуется в углу
                // поверх игры и забирает себе весь ввод.
                if (window.drawable() && !stack.contains(window)) {
                    stack.add(window);
                }
            }
        }

        private void seat(int op, ByteBuffer args) {
            if (op == 0) {
                pointer = args.getInt();
                make(pointer, "wl_pointer", 5);
                return;
            }

            if (op == 1) {
                keyboard = args.getInt();
                make(keyboard, "wl_keyboard", 5);

                try {
                    FileInputStream stream = new FileInputStream(keymapFile);

                    // Формат 1 — та самая раскладка текстом, которую мы собрали сами.
                    sendWithFd(new Msg(keyboard, 0).u(1).u(keymapSize), stream.getFD());
                    send(new Msg(keyboard, 5).i(25).i(400));
                    stream.close();
                } catch (Exception broken) {
                    say("экран: раскладка не уехала — " + broken);
                }

                return;
            }

            if (op == 2) {
                // Пальцы игре не показываем: она ждёт мышь, и мышью же мы ей и отвечаем.
                make(args.getInt(), "wl_touch", 5);
            }
        }

        private void wmBase(Res res, int op, ByteBuffer args) {
            if (op == 0) {
                drop(res);
                return;
            }

            if (op == 1) {
                make(args.getInt(), "xdg_positioner", 1);
                return;
            }

            if (op == 2) {
                int id = args.getInt();
                int surfaceId = args.getInt();
                Res surface = objects.get(surfaceId);

                Res holder = make(id, "xdg_surface", 1);

                if (surface != null && surface.window != null) {
                    holder.window = surface.window;
                    surface.window.xdgSurface = id;
                }
            }
        }

        private void positioner(Res res, int op, ByteBuffer args) {
            switch (op) {
                case 0:
                    drop(res);
                    return;
                case 1:
                    res.sizeW = args.getInt();
                    res.sizeH = args.getInt();
                    return;
                case 2:
                    res.anchorX = args.getInt();
                    res.anchorY = args.getInt();
                    res.anchorW = args.getInt();
                    res.anchorH = args.getInt();
                    return;
                default:
                    return;
            }
        }

        private void xdgSurface(Res res, int op, ByteBuffer args) {
            Wnd window = res.window;

            switch (op) {
                case 0:
                    drop(res);
                    return;

                case 1: {
                    int id = args.getInt();
                    Res holder = make(id, "xdg_toplevel", 1);
                    holder.window = window;

                    if (window != null) {
                        window.toplevel = id;
                        window.primary = firstWindow();
                    }

                    return;
                }

                case 2: {
                    int id = args.getInt();
                    args.getInt();
                    int positionerId = args.getInt();
                    Res where = objects.get(positionerId);

                    Res holder = make(id, "xdg_popup", 1);
                    holder.window = window;

                    if (window != null && where != null) {
                        window.popup = id;
                        window.x = Math.max(0, where.anchorX);
                        window.y = Math.max(0, where.anchorY + where.anchorH);
                        holder.sizeW = where.sizeW;
                        holder.sizeH = where.sizeH;
                    }

                    return;
                }

                default:
                    return;
            }
        }

        private boolean firstWindow() {
            synchronized (stack) {
                for (Wnd window : stack) {
                    if (window.toplevel != 0) {
                        return false;
                    }
                }
            }

            return true;
        }

        /**
         * Ответ игре о том, каким быть окну.
         *
         * Первое окно получает весь экран и признак «развёрнуто на весь экран» — игра на
         * телефоне не двигается по столу, ей нужен весь экран сразу. Остальные окна
         * (настройки, сообщения) размер выбирают сами, мы их только показываем по центру.
         */
        private void configure(Wnd window) {
            window.configured = true;

            if (window.toplevel != 0) {
                byte[] states;

                if (window.primary) {
                    states = new byte[] {
                            4, 0, 0, 0,   // окно активно
                            2, 0, 0, 0    // и занимает весь экран
                    };

                    send(new Msg(window.toplevel, 0).i(screenW).i(screenH).arr(states));
                } else {
                    states = new byte[] { 4, 0, 0, 0 };
                    send(new Msg(window.toplevel, 0).i(0).i(0).arr(states));
                }
            }

            if (window.popup != 0) {
                Res holder = objects.get(window.popup);
                int w = holder == null ? 0 : holder.sizeW;
                int h = holder == null ? 0 : holder.sizeH;
                send(new Msg(window.popup, 0).i(window.x).i(window.y).i(w).i(h));
            }

            send(new Msg(window.xdgSurface, 0).u(++serial));
        }

        private void toplevel(Res res, int op, ByteBuffer args) {
            if (op == 0) {
                if (res.window != null) {
                    synchronized (stack) {
                        stack.remove(res.window);
                    }
                }

                drop(res);
                changed();
                return;
            }

            if (op == 2 && res.window != null) {
                res.window.title = string(args);
                changed();
            }
        }
    }

    // ------------------------------------------------------------------
    // Мелочь протокола
    // ------------------------------------------------------------------

    private static String string(ByteBuffer args) {
        int length = args.getInt();

        if (length <= 0) {
            return "";
        }

        byte[] raw = new byte[length - 1];
        args.get(raw);
        args.get();

        int pad = (4 - (length & 3)) & 3;

        for (int i = 0; i < pad; i++) {
            args.get();
        }

        return new String(raw, StandardCharsets.UTF_8);
    }

    /** Одно исходящее сообщение: номер объекта, номер события, длина и аргументы. */
    private static final class Msg {

        private final ByteBuffer buffer = ByteBuffer.allocate(1 << 14)
                .order(ByteOrder.LITTLE_ENDIAN);

        private final int op;

        Msg(int id, int op) {
            this.op = op;
            buffer.putInt(id);
            buffer.putInt(0);
        }

        Msg u(int value) {
            buffer.putInt(value);
            return this;
        }

        Msg i(int value) {
            buffer.putInt(value);
            return this;
        }

        /** Дробное число протокола: целая часть и 8 бит после запятой. */
        Msg f(double value) {
            buffer.putInt((int) Math.round(value * 256));
            return this;
        }

        Msg s(String value) {
            byte[] raw = value.getBytes(StandardCharsets.UTF_8);
            int length = raw.length + 1;
            buffer.putInt(length);
            buffer.put(raw);
            buffer.put((byte) 0);

            int pad = (4 - (length & 3)) & 3;

            for (int i = 0; i < pad; i++) {
                buffer.put((byte) 0);
            }

            return this;
        }

        Msg arr(byte[] value) {
            buffer.putInt(value.length);
            buffer.put(value);

            int pad = (4 - (value.length & 3)) & 3;

            for (int i = 0; i < pad; i++) {
                buffer.put((byte) 0);
            }

            return this;
        }

        byte[] bytes() {
            int size = buffer.position();
            buffer.putInt(4, (size << 16) | op);

            byte[] out = new byte[size];
            ((java.nio.Buffer) buffer).rewind();
            buffer.get(out);
            return out;
        }
    }

    /** Что экран умеет — этот список игра спрашивает первым делом. */
    private static final class Global {

        final int name;
        final String iface;
        final int version;

        Global(int name, String iface, int version) {
            this.name = name;
            this.iface = iface;
            this.version = version;
        }
    }

    /**
     * Что экран умеет.
     *
     * Список не выдуман: слой Windows проверяет его целиком и, не найдя хотя бы одного
     * имени, молча отказывается от экрана вовсе. Так и было: без wl_subcompositor драйвер
     * экрана обрывался на первом же ответе, и в журнале не появлялось ни строчки — ни
     * ошибки, ни картинки. Ни одно имя отсюда убирать нельзя.
     */
    private static final Global[] GLOBALS = {
            new Global(1, "wl_compositor", 4),
            new Global(2, "wl_shm", 1),
            new Global(3, "wl_seat", 5),
            new Global(4, "wl_output", 2),
            new Global(5, "xdg_wm_base", 2),
            new Global(6, "wp_viewporter", 1),
            new Global(7, "wl_subcompositor", 1),
            new Global(8, "zwp_relative_pointer_manager_v1", 1),
            new Global(9, "zwp_pointer_constraints_v1", 1)
    };
}
