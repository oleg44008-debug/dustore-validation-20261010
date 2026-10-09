package ru.dustore.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;

/**
 * The glass: the game's picture underneath, the controls drawn on top of it, and the
 * thumbs that move them.
 *
 * Drawing happens on the network thread — the same one that has just decoded the frame —
 * rather than on the UI thread. A game at thirty frames a second would otherwise be
 * queueing thirty invalidate() calls a second behind whatever else the UI thread is doing,
 * and the controls would go sticky exactly when the action does.
 */
final class PadView extends SurfaceView implements SurfaceHolder.Callback {

    interface Listener {
        void onControl(int index, boolean down, int x, int y);

        void onLook(int dx, int dy);

        void onPointer(int perMilleX, int perMilleY, int button, int state);
    }

    static final class Control {
        int index;
        String kind = "button";
        String label = "";
        String hint = "";

        /** Имя знака с компьютера: «wheel», «fist», «pick»… Пусто — значит, только подпись. */
        String icon = "";

        float x;
        float y;
        float r;
        float w;
        float h;

        // ---- live state ----
        int pointerId = -1;
        float knobX;
        float knobY;
        boolean pressed;
        float visualPress, visualX, visualY;
        int visualStyle, visualTone;
        String visualSymbol;
        boolean accessibilityPressed;
        int accessibilityDirections;
    }

    private final PadChrome chrome = new PadChrome(getContext());
    private boolean previewOnly;
    private boolean animateControls, animationQueued;
    private long lastVisualTime;
    private final Runnable visualTick = new Runnable() {
        @Override public void run() {
            synchronized (PadView.this) {
                animationQueued = false;
                if (surfaceReady) render();
            }
        }
    };

    private final Rect source = new Rect();
    private final RectF destination = new RectF();

    private final List<Control> controls = new ArrayList<>();
    private Listener listener;
    private boolean pointerMode;
    private volatile boolean cameraMode;
    private volatile boolean forcePointer;
    private boolean lookPointerClicks;
    private long lastTouchDraw;
    private String status = "Подключаемся к компьютеру…";
    private String subtitle = "eXepad · игра идёт на компьютере";
    private volatile Bitmap frame;
    private volatile boolean surfaceReady;
    private boolean preferHardwareCanvas;
    private boolean hardwareCanvasUnavailable;

    private int lookPointer = -1;
    private float lookLastX;
    private float lookLastY;
    private int pointerLastX, pointerLastY;
    private boolean pointerHeld;
    private PadAccessibility accessibility;
    private int[] virtualIds;

    PadView(Context context) {
        super(context);
        getHolder().addCallback(this);
        setFocusable(true);
        setContentDescription("Игровой контроллер");
        accessibility = new PadAccessibility(this, new PadAccessibility.Model() {
            @Override public int[] ids() { return accessibilityIds(); }
            @Override public String label(int id) { return accessibilityLabel(id); }
            @Override public boolean bounds(int id, Rect out) { return accessibilityBounds(id, out); }
            @Override public boolean held(int id) { return accessibilityHeld(id); }
            @Override public boolean change(int id, boolean down) { return accessibilityChange(id, down); }
            @Override public void releaseAll() { releaseAllInputs(); }
            @Override public void focusChanged() { redraw(); }
        });
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    synchronized void setControls(List<Control> list, boolean pointer) {
        releaseAllInputs();
        removeCallbacks(visualTick);
        animationQueued = false;
        animateControls = false;
        lastVisualTime = 0;
        controls.clear();
        controls.addAll(list);
        virtualIds = null;
        for (Control control : controls) {
            control.visualStyle = PadChrome.buttonStyle(control.label, control.icon);
            control.visualTone = PadChrome.faceTone(control.label, control.icon);
            control.visualSymbol = PadChrome.faceSymbol(control.label);
        }
        pointerMode = pointer;
        if (accessibility != null) accessibility.changed();
    }

    /** Captured mouse: a drag looks around; only the explicit LKM button clicks. */
    void setCameraMode(boolean captured) { cameraMode = captured; }

    void setForcePointer(boolean enabled) { forcePointer = enabled; }

    synchronized void setStatus(String value) {
        status = value;
        setContentDescription("Игровой контроллер. " + value);
    }

    synchronized void setSubtitle(String value) { subtitle = value; }

    /** eX scales a live game every frame; let the GPU draw it when Android supports it. */
    void useHardwareCanvas() {
        preferHardwareCanvas = true;
    }

    /**
     * Ставит шарик стика извне — этим руль поворачивается от наклона телефона.
     *
     * Рисовать положение обязательно: без обратной связи руль от гироскопа ощущается как
     * «машина сама едет», потому что глазами поворот не подтверждён.
     */
    synchronized void setKnob(int index, float x, float y) {
        for (Control control : controls) {
            if (control.index == index) {
                control.knobX = Math.max(-1f, Math.min(1f, x));
                control.knobY = Math.max(-1f, Math.min(1f, y));
                break;
            }
        }
    }

    /** Replaces the picture and redraws everything. Called from the network thread. */
    synchronized void showFrame(Bitmap bitmap) {
        frame = bitmap;
        render();
    }

    synchronized void redraw() {
        render();
    }

    /**
     * Картинка раскладки для экрана выбора: те же кнопки, тем же кодом, только мельче и
     * без пальцев. Человек выбирает раскладку глазами, а не по названию.
     */
    static Bitmap preview(Context context, List<Control> layout, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Ui.PAD_VOID);

        // Keep the authored landscape proportions in every picker tile. Draw the
        // vectors into the final bitmap so larger and high-density previews stay
        // sharp rather than enlarging a small intermediate phone bitmap.
        PadView painter = new PadView(context);
        painter.setControls(layout, false);
        painter.previewOnly = true;
        float scale = Math.min(width / 780f, height / 360f);
        float drawnWidth = 780f * scale;
        float drawnHeight = 360f * scale;
        canvas.save();
        canvas.translate((width - drawnWidth) / 2f, (height - drawnHeight) / 2f);
        canvas.scale(scale, scale);
        painter.chrome.background(canvas, 780, 360);
        painter.drawControls(canvas, 780, 360);
        canvas.restore();

        return bitmap;
    }

    // -------------------------------------------------------------------- drawing --

    private synchronized void render() {
        if (!surfaceReady) {
            return;
        }

        SurfaceHolder holder = getHolder();
        Canvas canvas = null;
        if (preferHardwareCanvas && !hardwareCanvasUnavailable
                && android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                canvas = holder.lockHardwareCanvas();
            } catch (RuntimeException unavailable) {
                hardwareCanvasUnavailable = true;
            }
        }
        if (canvas == null) {
            try { canvas = holder.lockCanvas(); }
            catch (IllegalStateException unavailable) { return; }
        }
        if (canvas == null) {
            return;
        }

        try {
            canvas.drawColor(Ui.PAD_VOID);

            if (frame != null && !frame.isRecycled()) {
                fit(canvas.getWidth(), canvas.getHeight(), frame.getWidth(), frame.getHeight());
                source.set(0, 0, frame.getWidth(), frame.getHeight());
                canvas.drawBitmap(frame, source, destination, null);
            } else {
                chrome.background(canvas, canvas.getWidth(), canvas.getHeight());
                // Картинки здесь не бывает — eXepad не показывает игру, он ею управляет.
                // Поэтому подпись уходит наверх: середина экрана нужна большим пальцам.
                chrome.label(canvas, status, canvas.getWidth() / 2f, canvas.getHeight() * 0.17f,
                        canvas.getWidth() * 0.62f, canvas.getHeight() * 0.040f, Ui.PAD_INK);
                chrome.label(canvas, subtitle, canvas.getWidth() / 2f, canvas.getHeight() * 0.225f,
                        canvas.getWidth() * 0.62f, canvas.getHeight() * 0.026f, Ui.PAD_INK_SOFT);
            }
            drawControls(canvas);
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas);
            } catch (IllegalStateException ignored) {
                // The surface can go away between the lock and the post when the user
                // leaves the app; there is nothing to put right.
            }
        }
    }

    private void fit(int viewWidth, int viewHeight, int imageWidth, int imageHeight) {
        float scale = Math.min(viewWidth / (float) imageWidth, viewHeight / (float) imageHeight);
        float width = imageWidth * scale;
        float height = imageHeight * scale;
        float left = (viewWidth - width) / 2f;
        float top = (viewHeight - height) / 2f;

        destination.set(left, top, left + width, top + height);
    }

    private void drawControls(Canvas canvas) {
        drawControls(canvas, canvas.getWidth(), canvas.getHeight());
    }

    private void drawControls(Canvas canvas, int width, int height) {
        long now = android.os.SystemClock.uptimeMillis();
        float dt = lastVisualTime == 0 ? 1f / 60f : Math.min(0.05f, Math.max(0.001f, (now - lastVisualTime) / 1000f));
        lastVisualTime = now;
        boolean animations = PadChrome.animationsEnabled(getContext());
        boolean full = Motion.full(getContext());
        float pressBlend = animations ? 1f - (float)Math.exp(-dt / (full ? .032f : .018f)) : 1f;
        float stickBlend = full ? 1f - (float)Math.exp(-dt / .016f) : 1f;
        animateControls = false;
        chrome.label(canvas, "eXepc", width * 0.75f, height * 0.047f,
                width * 0.14f, Math.max(10f, height * 0.027f), Ui.alpha(Ui.PAD_INK, 0xC8));

        for (Control control : controls) {
            if ("look".equals(control.kind)) continue;
            float cx = control.x * width, cy = control.y * height, radius = control.r * height;
            if (radius < 1f) continue;
            boolean stick = "stick".equals(control.kind), pad = "dpad".equals(control.kind);
            boolean wheel = "wheel".equals(control.icon);
            boolean held = control.pressed || control.pointerId >= 0 || (wheel && Math.abs(control.knobX) > 0.02f);
            float target = held ? 1f : 0f;
            float press, knobX, knobY;
            if (previewOnly) {
                press = target; knobX = control.knobX; knobY = control.knobY;
            } else {
                control.visualPress = approach(control.visualPress, target, pressBlend);
                control.visualX = approach(control.visualX, control.knobX, stickBlend);
                control.visualY = approach(control.visualY, control.knobY, stickBlend);
                press = control.visualPress; knobX = control.visualX; knobY = control.visualY;
                animateControls |= press != target || knobX != control.knobX || knobY != control.knobY;
            }
            int ink = Ui.blend(Ui.PAD_INK, Ui.PAD_ICON_HELD, press);
            if (wheel) {
                chrome.wheel(canvas, cx, cy, radius, knobX, press);
            } else if (pad) {
                // Direction colours use the actual input immediately; smoothing only moves the stick cap.
                chrome.dpad(canvas, cx, cy, radius, control.knobX, control.knobY, held ? 1f : 0f);
            } else if (stick) {
                chrome.stick(canvas, cx, cy, radius, knobX, knobY, press);
            } else {
                // Colour and native depth communicate state; the circular hit region
                // and every authored mapping remain exactly as supplied by the layout.
                float faceRadius = radius * (1f - (full ? press * .022f : 0f));
                float faceY = cy + (full ? radius * press * .018f : 0f);
                ink = Ui.blend(control.visualTone, Ui.ACTION_INK, press);
                chrome.button(canvas, cx, faceY, faceRadius, press, control.visualTone, control.visualStyle);
                if (control.visualStyle == 2 && PadIcons.known(control.icon)) {
                    if (previewOnly) PadIcons.draw(canvas, control.icon, cx, faceY, radius * .34f, ink);
                    else {
                        PadIcons.draw(canvas, control.icon, cx - radius * .48f, faceY, radius * .23f, ink);
                        chrome.label(canvas, control.label, cx + radius * .23f, faceY,
                                radius * .88f, radius * .30f, ink);
                    }
                } else if (control.visualStyle == 1 && PadIcons.known(control.icon)) {
                    PadIcons.draw(canvas, control.icon, cx, faceY - (previewOnly ? 0f : radius * .16f),
                            radius * .39f, ink);
                    if (!previewOnly) chrome.label(canvas, control.label, cx, faceY + radius * .47f,
                            radius * 1.05f, radius * .27f, ink);
                } else if (control.visualSymbol != null) {
                    PadIcons.draw(canvas, control.visualSymbol, cx, faceY, radius * .57f, ink);
                } else if (PadIcons.known(control.icon)) {
                    boolean caption = !previewOnly && control.label != null && !control.label.isEmpty();
                    PadIcons.draw(canvas, control.icon, cx, faceY - (caption ? radius * .19f : 0f), radius * (caption ? .49f : .58f), ink);
                    if (caption) chrome.label(canvas, control.label, cx, faceY + radius * .52f,
                            radius * 1.57f, radius * .27f, Ui.blend(Ui.PAD_INK_SOFT, Ui.ACTION_INK, press));
                } else {
                    boolean hint = !previewOnly && control.hint != null && !control.hint.isEmpty() && !control.hint.equals(control.label);
                    float textSize = control.label != null && control.label.length() > 3 ? .43f : .73f;
                    if (control.visualStyle == 1) textSize = Math.min(textSize, .53f);
                    if (control.visualStyle == 2) { textSize = Math.min(textSize, .44f); hint = false; }
                    chrome.label(canvas, control.label, cx, faceY - (hint ? radius * .13f : 0f),
                            radius * (control.visualStyle == 1 ? 1.04f : 1.62f), radius * textSize, ink);
                    if (hint) chrome.label(canvas, control.hint, cx, faceY + radius * .48f,
                            radius * (control.visualStyle == 1 ? 1.05f : 1.45f), radius * .25f, Ui.blend(Ui.PAD_INK_SOFT, Ui.ACTION_INK, press));
                }
            }
            if ((stick || pad || wheel) && !previewOnly)
                chrome.label(canvas, control.label, cx, Math.max(height * 0.055f, cy - radius - radius * 0.15f),
                        radius * 2f, radius * 0.23f, Ui.PAD_INK);
        }
        if (!previewOnly && accessibility != null) accessibility.drawFocus(canvas);
        if (animateControls && !previewOnly && surfaceReady && isShown() && !animationQueued) {
            animationQueued = true;
            postOnAnimation(visualTick);
        }
    }

    private static float approach(float value, float target, float amount) {
        float next = value + (target - value) * amount;
        return Math.abs(target - next) < 0.002f ? target : next;
    }
    // --------------------------------------------------------------------- touch --

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        boolean visualChanged = false;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int pointer = event.getActionIndex();
                visualChanged = press(event.getPointerId(pointer),
                        event.getX(pointer), event.getY(pointer));
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    visualChanged |= move(event.getPointerId(i),
                            event.getX(i), event.getY(i));
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            {
                int pointer = event.getActionIndex();
                visualChanged = release(event.getPointerId(pointer),
                        event.getX(pointer), event.getY(pointer));
                break;
            }

            case MotionEvent.ACTION_CANCEL: {
                // Android cancels every finger, not only the action index. Otherwise a
                // virtual movement key can remain held after a gesture interruption.
                releaseAllInputs();
                visualChanged = true;
                break;
            }
        }

        if (visualChanged) redrawTouch(action != MotionEvent.ACTION_MOVE);
        return true;
    }

    private void redrawTouch(boolean edge) {
        // Show press/release immediately even if the stream stalls. Stick movement can
        // ride the next video frame; repainting every motion event competes with decoding.
        if (!edge && preferHardwareCanvas && frame != null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (edge || now - lastTouchDraw >= 33) {
            // An idle controller's first press has a fresh frame budget; time before
            // the touch belongs to no animation. Input still goes out synchronously.
            if (now - lastVisualTime > 180) lastVisualTime = now - 16;
            lastTouchDraw = now;
            redraw();
        }
    }

    private synchronized boolean press(int pointerId, float x, float y) {
        Control hit = findControl(x, y);

        if (hit == null) {
            if (lookPointer >= 0) return false;
            // Nothing under the finger but the picture: in pointer mode that is a click on
            // the game, in camera mode it is the start of a look.
            lookPointerClicks = (pointerMode || forcePointer) && !cameraMode;
            if (lookPointerClicks) {
                lookPointer = pointerId;
                sendPointer(x, y, 0, 1);
            } else {
                lookPointer = pointerId;
                lookLastX = x;
                lookLastY = y;
            }

            return false;
        }

        if ("button".equals(hit.kind)) {
            if (hit.pointerId >= 0 || hit.accessibilityPressed) return false;
            hit.pressed = true;
            hit.pointerId = pointerId;

            if (listener != null) {
                listener.onControl(hit.index, true, 0, 0);
            }

            return true;
        }

        if (hit.pointerId >= 0 || hit.accessibilityDirections != 0) return false;
        hit.pointerId = pointerId;
        moveStick(hit, x, y);
        return true;
    }

    private synchronized boolean move(int pointerId, float x, float y) {
        if (pointerId == lookPointer) {
            if (lookPointerClicks) {
                sendPointer(x, y, 0, 0);
            } else if (listener != null) {
                listener.onLook(Math.round(x - lookLastX), Math.round(y - lookLastY));
                lookLastX = x;
                lookLastY = y;
            }

            return false;
        }

        for (Control control : controls) {
            if (control.pointerId == pointerId && !"button".equals(control.kind)) {
                moveStick(control, x, y);
                return true;
            }
        }
        return false;
    }

    private synchronized boolean release(int pointerId, float x, float y) {
        if (pointerId == lookPointer) {
            lookPointer = -1;

            if (lookPointerClicks) {
                sendPointer(x, y, 0, 2);
            }
            lookPointerClicks = false;
            return false;
        }

        boolean changed = false;
        for (Control control : controls) {
            if (control.pointerId != pointerId) {
                continue;
            }

            control.pointerId = -1;
            changed = true;

            if ("button".equals(control.kind)) {
                control.pressed = false;

                if (listener != null) {
                    listener.onControl(control.index, false, 0, 0);
                }
            } else {
                control.knobX = 0;
                control.knobY = 0;

                if (listener != null) {
                    listener.onControl(control.index, false, 0, 0);
                }
            }
        }
        return changed;
    }

    private void moveStick(Control control, float x, float y) {
        int width = getWidth();
        int height = getHeight();

        float cx = control.x * width;
        float cy = control.y * height;
        float radius = control.r * height;

        float dx = (x - cx) / radius;
        float dy = (y - cy) / radius;

        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length > 1f) {
            dx /= length;
            dy /= length;
        }

        control.knobX = dx;
        control.knobY = dy;

        if (listener != null) {
            listener.onControl(control.index, true, Math.round(dx * 1000f), Math.round(dy * 1000f));
        }
    }

    private void sendPointer(float x, float y, int button, int state) {
        if (listener == null || destination.width() <= 0 || destination.height() <= 0) {
            return;
        }

        float relativeX = (x - destination.left) / destination.width();
        float relativeY = (y - destination.top) / destination.height();

        relativeX = Math.max(0f, Math.min(1f, relativeX));
        relativeY = Math.max(0f, Math.min(1f, relativeY));

        listener.onPointer(Math.round(relativeX * 1000f), Math.round(relativeY * 1000f),
                button, state);
        pointerLastX = Math.round(relativeX * 1000f);
        pointerLastY = Math.round(relativeY * 1000f);
        if (state == 1) pointerHeld = true;
        else if (state == 2) pointerHeld = false;
    }

    /** Releases every source before pause, focus loss, surface loss or client shutdown. */
    synchronized void releaseAllInputs() {
        if (controls == null) return;
        if (accessibility != null) accessibility.reset();
        removeCallbacks(visualTick);
        animationQueued = animateControls = false;
        lastVisualTime = 0;
        for (Control control : controls) {
            boolean held = control.pointerId >= 0 || control.pressed || control.accessibilityPressed
                    || control.accessibilityDirections != 0 || control.knobX != 0 || control.knobY != 0;
            control.pointerId = -1; control.pressed = control.accessibilityPressed = false;
            control.accessibilityDirections = 0;
            control.knobX = control.knobY = control.visualX = control.visualY = control.visualPress = 0;
            if (held && listener != null && !"look".equals(control.kind)) listener.onControl(control.index, false, 0, 0);
        }
        if (pointerHeld && listener != null) listener.onPointer(pointerLastX, pointerLastY, 0, 2);
        pointerHeld = lookPointerClicks = false;
        lookPointer = -1; lookLastX = lookLastY = 0;
        if (surfaceReady && isShown()) render();
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return accessibility; }
    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        return accessibility != null && accessibility.hover(event) || super.dispatchHoverEvent(event);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return accessibility != null && accessibility.key(event.getKeyCode(), event) || super.dispatchKeyEvent(event);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        if (!focused) releaseAllInputs();
        super.onWindowFocusChanged(focused);
    }
    @Override protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        if (!focused) releaseAllInputs();
        super.onFocusChanged(focused, direction, previous);
    }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        if (visibility != VISIBLE) releaseAllInputs();
        super.onVisibilityChanged(changedView, visibility);
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        if (visibility != VISIBLE) releaseAllInputs();
        super.onWindowVisibilityChanged(visibility);
    }

    private synchronized int[] accessibilityIds() {
        if (virtualIds != null) return virtualIds;
        int count = 0;
        for (Control control : controls) if (!"look".equals(control.kind))
            count += "button".equals(control.kind) ? 1 : "wheel".equals(control.icon) ? 2 : 4;
        virtualIds = new int[count];
        int at = 0;
        for (int i = 0; i < controls.size(); i++) {
            Control control = controls.get(i);
            if ("look".equals(control.kind)) continue;
            if ("button".equals(control.kind)) virtualIds[at++] = (i + 1) * 8;
            else for (int direction = "wheel".equals(control.icon) ? 3 : 1; direction <= 4; direction++)
                virtualIds[at++] = (i + 1) * 8 + direction;
        }
        return virtualIds;
    }

    private Control accessibilityControl(int id) {
        int slot = id / 8 - 1, direction = id % 8;
        if (slot < 0 || slot >= controls.size() || direction < 0 || direction > 4) return null;
        Control control = controls.get(slot);
        if ("look".equals(control.kind) || ("button".equals(control.kind) ? direction != 0 : direction == 0)
                || "wheel".equals(control.icon) && direction < 3) return null;
        return control;
    }

    private synchronized String accessibilityLabel(int id) {
        Control control = accessibilityControl(id);
        if (control == null) return "Игровой контроллер";
        String label = control.label == null || control.label.isEmpty() ? control.icon : control.label;
        if (label == null || label.isEmpty()) label = "Игровая кнопка " + (control.index + 1);
        if ("△".equals(label) || "Δ".equals(label)) label = "Треугольник";
        else if ("○".equals(label) || "◯".equals(label)) label = "Круг";
        else if ("□".equals(label) || "▢".equals(label)) label = "Квадрат";
        else if ("×".equals(label) || "✕".equals(label)) label = "Крест";
        int direction = id % 8;
        if (direction > 0) return label + ", " + PadAccessibility.direction(direction);
        return control.hint != null && !control.hint.isEmpty() && !control.hint.equals(label)
                ? label + ", " + control.hint : label;
    }

    private synchronized boolean accessibilityBounds(int id, Rect out) {
        Control control = accessibilityControl(id);
        if (control == null || getWidth() <= 0 || getHeight() <= 0) return false;
        float cx = control.x * getWidth(), cy = control.y * getHeight(), r = control.r * getHeight();
        if (id % 8 == 0) r = Math.max(r, getResources().getDisplayMetrics().density * 24f);
        float left = cx - r, top = cy - r, right = cx + r, bottom = cy + r;
        switch (id % 8) {
            case 1: left = cx - r * .42f; right = cx + r * .42f; bottom = cy - r * .12f; break;
            case 2: left = cx - r * .42f; right = cx + r * .42f; top = cy + r * .12f; break;
            case 3: top = cy - r * .42f; bottom = cy + r * .42f; right = cx - r * .12f; break;
            case 4: top = cy - r * .42f; bottom = cy + r * .42f; left = cx + r * .12f; break;
        }
        out.set(Math.max(0, (int)Math.floor(left)), Math.max(0, (int)Math.floor(top)),
                Math.min(getWidth(), (int)Math.ceil(right)), Math.min(getHeight(), (int)Math.ceil(bottom)));
        return !out.isEmpty();
    }

    private synchronized boolean accessibilityHeld(int id) {
        Control control = accessibilityControl(id);
        if (control == null) return false;
        int direction = id % 8;
        return direction == 0 ? control.pressed
            : (control.accessibilityDirections & 1 << direction) != 0;
    }

    private synchronized boolean accessibilityChange(int id, boolean down) {
        Control control = accessibilityControl(id);
        if (control == null || control.pointerId >= 0) return false;
        int direction = id % 8;
        if (direction == 0) {
            if (control.accessibilityPressed == down) return false;
            control.pressed = control.accessibilityPressed = down;
        } else {
            int mask = 1 << direction;
            if (((control.accessibilityDirections & mask) != 0) == down) return false;
            control.accessibilityDirections = down ? control.accessibilityDirections | mask : control.accessibilityDirections & ~mask;
            int directions = control.accessibilityDirections;
            control.knobX = ((directions & 16) != 0 ? 1 : 0) - ((directions & 8) != 0 ? 1 : 0);
            control.knobY = ((directions & 4) != 0 ? 1 : 0) - ((directions & 2) != 0 ? 1 : 0);
            float length = (float)Math.sqrt(control.knobX * control.knobX + control.knobY * control.knobY);
            if (length > 1) { control.knobX /= length; control.knobY /= length; }
        }
        boolean active = direction == 0 ? down : control.accessibilityDirections != 0;
        if (listener != null) listener.onControl(control.index, active,
                Math.round(control.knobX * 1000), Math.round(control.knobY * 1000));
        redrawTouch(true);
        return true;
    }

    private Control findControl(float x, float y) {
        int width = getWidth();
        int height = getHeight();

        // Buttons and sticks first: a look area covers half the screen, so testing in
        // declaration order would swallow every button sitting inside it.
        for (int i = controls.size() - 1; i >= 0; i--) {
            Control control = controls.get(i);
            if ("look".equals(control.kind)) {
                continue;
            }

            float cx = control.x * width;
            float cy = control.y * height;
            float radius = control.r * height;

            // A little larger than it looks: thumbs are not precise, and a button that
            // needs aiming at is a button that gets missed mid-fight.
            float touchRadius = radius * 1.18f;
            float dx = x - cx;
            float dy = y - cy;

            if (dx * dx + dy * dy <= touchRadius * touchRadius) {
                return control;
            }
        }

        return null;
    }

    // ------------------------------------------------------------------- surface --

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        redraw();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        redraw();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        releaseAllInputs();
        removeCallbacks(visualTick);
        animationQueued = false;
        animateControls = false;
        lastVisualTime = 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAllInputs();
        removeCallbacks(visualTick);
        animationQueued = false;
        lastVisualTime = 0;
        super.onDetachedFromWindow();
    }
}
