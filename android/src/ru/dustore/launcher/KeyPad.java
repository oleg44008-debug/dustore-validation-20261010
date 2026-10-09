package ru.dustore.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * The keyboard a phone does not have.
 *
 * A game written for a PC waits for arrow keys and Z; a phone has a sheet of glass. This
 * draws the missing keys over the game and sends the real thing — a KeyboardEvent the page
 * cannot tell apart from a keyboard, because as far as the engine is concerned it is one.
 */
final class KeyPad extends View {

    interface Listener {
        void onKey(String code, int keyCode, boolean down);
    }

    static final class Key {
        String label;
        /** Знак вместо подписи: стрелки рисуются вектором, а не глифом из шрифта. */
        String icon;
        String code;
        int keyCode;
        float x;
        float y;
        float r;
        // Authored x/y/r stay unchanged. Painting and input share this resolved
        // geometry, rebuilt only when the profile or real content bounds change.
        float renderX, renderY, renderRadius, targetRadius;
        final Rect targetBounds = new Rect();
        int pointer = -1;
        float visualPress;
        int visualStyle, visualTone;
        String visualSymbol;
        boolean accessibilityPressed;
    }

    private final PadChrome chrome = new PadChrome(getContext());
    private long lastVisualTime;

    private final List<Key> keys = new ArrayList<>();
    private Listener listener;
    private PadAccessibility accessibility;
    private int[] virtualIds;
    // Android native pointer IDs are 0..31. Keep gesture ownership without a
    // collection allocation; releasing a key while sliding does not release its ID.
    private int ownedPointers;
    private final Key[] pointerTargets = new Key[32];
    private boolean geometryDirty = true, geometryReady, geometryResolving;
    private int geometryWidth, geometryHeight, geometryLeft, geometryTop, geometryRight, geometryBottom;
    private float geometryDensity;

    KeyPad(Context context) {
        super(context);
        setContentDescription("Игровая клавиатура");
        accessibility = new PadAccessibility(this, new PadAccessibility.Model() {
            @Override public int[] ids() { return accessibilityIds(); }
            @Override public String label(int id) { return accessibilityLabel(id); }
            @Override public boolean bounds(int id, Rect out) { return accessibilityBounds(id, out); }
            @Override public boolean hit(int id, float x, float y) {
                return geometryReady && valid(id) && contains(keys.get(id - 1), x, y);
            }
            @Override public boolean held(int id) { return valid(id) && (keys.get(id - 1).pointer >= 0 || keys.get(id - 1).accessibilityPressed); }
            @Override public boolean change(int id, boolean down) {
                if (!valid(id)) return false;
                Key key = keys.get(id - 1);
                if (key.accessibilityPressed == down || (down && key.pointer >= 0)) return false;
                // Accessibility cannot acquire a key already held by touch. Its own
                // release must still clear its flag when touch joined an earlier hold;
                // the listener receives only the aggregate held-state edge.
                boolean before = key.pointer >= 0 || key.accessibilityPressed;
                key.accessibilityPressed = down;
                boolean after = key.pointer >= 0 || key.accessibilityPressed;
                if (before != after) fire(key, after);
                invalidate(); return true;
            }
            @Override public void releaseAll() { releaseAllInputs(); }
            @Override public void focusChanged() { invalidate(); }
        });
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Builds the layout for a kind of game. Which one to use is decided on the PC, where
     * the engine was identified — the phone is not asked to guess.
     */
    void profile(String name) {
        releaseAllKeys(true);
        keys.clear();
        virtualIds = null;
        geometryDirty = true;
        geometryReady = false;

        if ("none".equals(name)) {
            setVisibility(GONE);
            resolveGeometry();
            invalidate();
            return;
        }

        setVisibility(VISIBLE);

        // A cross on the left, under the thumb that is already there. Стрелки — вектором:
        // ▲ и ◀ из шрифта прошивки бывают разной ширины и сидят на разной базовой линии,
        // и крестовина из них получалась кривой.
        arrow("up", "ArrowUp", 38, 0.115f, 0.60f, 0.075f);
        arrow("down", "ArrowDown", 40, 0.115f, 0.86f, 0.075f);
        arrow("left", "ArrowLeft", 37, 0.045f, 0.73f, 0.075f);
        arrow("right", "ArrowRight", 39, 0.185f, 0.73f, 0.075f);

        if ("rpgmaker".equals(name)) {
            add("OK", "KeyZ", 90, 0.945f, 0.80f, 0.085f);
            add("Отмена", "KeyX", 88, 0.855f, 0.58f, 0.070f);
            add("Бег", "ShiftLeft", 16, 0.760f, 0.86f, 0.060f);
            add("Меню", "Escape", 27, 0.960f, 0.12f, 0.050f);
            resolveGeometry();
            invalidate();
            return;
        }

        if ("novel".equals(name)) {
            add("Дальше", "Enter", 13, 0.930f, 0.82f, 0.085f);
            add("Пропуск", "ControlLeft", 17, 0.820f, 0.60f, 0.065f);
            add("Меню", "Escape", 27, 0.960f, 0.12f, 0.050f);
            resolveGeometry();
            invalidate();
            return;
        }

        // Arcade and everything else: jump, hit, run.
        add("A", "Space", 32, 0.945f, 0.80f, 0.085f);
        add("B", "KeyZ", 90, 0.855f, 0.58f, 0.070f);
        add("C", "KeyX", 88, 0.760f, 0.86f, 0.060f);
        add("Меню", "Escape", 27, 0.960f, 0.12f, 0.050f);

        resolveGeometry();
        invalidate();
    }

    private void arrow(String icon, String code, int keyCode, float x, float y, float r) {
        add("", code, keyCode, x, y, r);
        keys.get(keys.size() - 1).icon = icon;
    }

    private void add(String label, String code, int keyCode, float x, float y, float r) {
        Key key = new Key();
        key.label = label;
        key.code = code;
        key.keyCode = keyCode;
        key.x = x;
        key.y = y;
        key.r = r;
        key.visualStyle = keyCode == 27 ? 2 : PadChrome.buttonStyle(label, null);
        key.visualTone = PadChrome.faceTone(label, null);
        key.visualSymbol = PadChrome.faceSymbol(label);

        keys.add(key);
        virtualIds = null;
        if (accessibility != null) accessibility.changed();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!geometryReady) return;
        long now = android.os.SystemClock.uptimeMillis();
        float dt = lastVisualTime == 0 ? 1f / 60f : Math.min(0.05f, Math.max(0.001f, (now - lastVisualTime) / 1000f));
        lastVisualTime = now;
        boolean full = Motion.full(getContext());
        float blend = PadChrome.animationsEnabled(getContext()) ? 1f - (float)Math.exp(-dt / (full ? .032f : .018f)) : 1f;
        boolean animating = false;
        for (Key key : keys) {
            float cx = key.renderX, cy = key.renderY, radius = key.renderRadius;
            float target = key.pointer >= 0 || key.accessibilityPressed ? 1f : 0f;
            key.visualPress += (target - key.visualPress) * blend;
            if (Math.abs(target - key.visualPress) < 0.002f) key.visualPress = target;
            animating |= target != key.visualPress;
            float faceRadius = radius * (1f - (full ? key.visualPress * .022f : 0f));
            float faceY = cy + (full ? radius * key.visualPress * .018f : 0f);
            chrome.button(canvas, cx, faceY, faceRadius, key.visualPress, key.visualTone, key.visualStyle);
            int ink = Ui.blend(key.visualTone, Ui.ACTION_INK, key.visualPress);
            if (key.icon != null) PadIcons.draw(canvas, key.icon, cx, faceY, radius * .52f, ink);
            else if (key.visualSymbol != null) PadIcons.draw(canvas, key.visualSymbol, cx, faceY, radius * .57f, ink);
            else chrome.label(canvas, key.label, cx, faceY, radius * 1.65f,
                    radius * (key.visualStyle == 2 ? .36f : key.label.length() > 2 ? .43f : .76f), ink);
        }
        if (accessibility != null) accessibility.drawFocus(canvas);
        if (animating && isShown()) postInvalidateOnAnimation();
    }
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (action == MotionEvent.ACTION_DOWN) {
                    // A new gesture cannot retain an interrupted old physical hold,
                    // including when the new DOWN belongs to the underlying game.
                    // Independent accessibility holds remain part of the net state.
                    clearPointerTargets();
                    reconcilePointerTargets();
                    invalidate();
                }
                int index = event.getActionIndex();
                int pointer = event.getPointerId(index);
                if (pointer < 0 || pointer > 31) return false;
                Key key = find(event.getX(index), event.getY(index));

                if (key == null) {
                    // Nothing of ours under the finger: the game itself should get it.
                    return false;
                }

                ownedPointers |= 1 << pointer;
                pointerTargets[pointer] = key;
                reconcilePointerTargets();
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int pointer = event.getPointerId(event.getActionIndex());
                if (action == MotionEvent.ACTION_UP) {
                    clearPointerTargets();
                } else if (pointer >= 0 && pointer <= 31) {
                    ownedPointers &= ~(1 << pointer);
                    pointerTargets[pointer] = null;
                }
                reconcilePointerTargets();

                invalidate();
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                // Cancellation belongs to the whole gesture, not its action index.
                // Every synthetic key must be released when Android abandons it.
                releaseAllKeys(false);
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                // Sliding from one arrow to the next is how people actually use a d-pad.
                // Update every owned finger before resolving key states. Two fingers
                // may swap keys or share one key without transient key-up/down edges.
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int pointer = event.getPointerId(i);
                    if (holds(pointer)) pointerTargets[pointer] = find(event.getX(i), event.getY(i));
                }
                reconcilePointerTargets();

                invalidate();
                return true;
            }
        }

        return false;
    }

    private void clearPointerTargets() {
        ownedPointers = 0;
        // View's constructor can invoke visibility callbacks before our fields exist.
        if (pointerTargets != null) {
            for (int i = 0; i < pointerTargets.length; i++) pointerTargets[i] = null;
        }
    }

    private void reconcilePointerTargets() {
        for (int k = 0; k < keys.size(); k++) {
            Key key = keys.get(k);
            boolean before = key.pointer >= 0 || key.accessibilityPressed;
            int representative = -1;
            for (int pointer = 0; pointer < pointerTargets.length; pointer++) {
                if (holds(pointer) && pointerTargets[pointer] == key) { representative = pointer; break; }
            }
            key.pointer = representative;
            boolean after = representative >= 0 || key.accessibilityPressed;
            if (before != after) fire(key, after);
        }
    }

    private void releaseAllKeys(boolean settleVisuals) {
        clearPointerTargets();
        // Visibility can change while View's constructor is still initializing.
        if (keys == null) return;
        for (int i = 0; i < keys.size(); i++) {
            Key key = keys.get(i);
            if (key.pointer >= 0 || key.accessibilityPressed) {
                key.pointer = -1;
                key.accessibilityPressed = false;
                fire(key, false);
            }
            if (settleVisuals) key.visualPress = 0f;
        }
        if (settleVisuals) lastVisualTime = 0;
        if (accessibility != null) accessibility.reset();
    }

    void releaseAllInputs() { releaseAllKeys(true); invalidate(); }
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

    private boolean valid(int id) { return id > 0 && id <= keys.size(); }
    private int[] accessibilityIds() {
        if (virtualIds == null) {
            virtualIds = new int[keys.size()];
            for (int i = 0; i < keys.size(); i++) virtualIds[i] = i + 1;
        }
        return virtualIds;
    }
    private String accessibilityLabel(int id) {
        if (!valid(id)) return "Игровая клавиатура";
        Key key = keys.get(id - 1);
        if (key.icon != null && !key.icon.isEmpty()) {
            switch (key.icon) {
                case "up": return "Вверх, стрелка вверх";
                case "down": return "Вниз, стрелка вниз";
                case "left": return "Влево, стрелка влево";
                case "right": return "Вправо, стрелка вправо";
            }
        }
        String binding = "Space".equals(key.code) ? "пробел" : "Escape".equals(key.code) ? "Escape"
            : key.code.startsWith("Key") ? key.code.substring(3) : key.code;
        return key.label.equalsIgnoreCase(binding) ? key.label : key.label + ", " + binding;
    }
    private boolean accessibilityBounds(int id, Rect out) {
        if (!geometryReady || !valid(id)) return false;
        out.set(keys.get(id - 1).targetBounds);
        return !out.isEmpty();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        resolveGeometry();
    }

    @Override public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
        resolveGeometry();
    }

    @Override public void setPaddingRelative(int start, int top, int end, int bottom) {
        super.setPaddingRelative(start, top, end, bottom);
        resolveGeometry();
    }

    @Override public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        resolveGeometry();
    }

    @Override protected void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        resolveGeometry();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        if (visibility != VISIBLE) releaseAllKeys(true);
        super.onVisibilityChanged(changedView, visibility);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        if (visibility != VISIBLE) releaseAllKeys(true);
        super.onWindowVisibilityChanged(visibility);
    }

    @Override protected void onDetachedFromWindow() {
        releaseAllKeys(true);
        super.onDetachedFromWindow();
    }

    private boolean holds(int pointer) {
        // A pointer that started on one of ours keeps belonging to the pad even after it
        // has left every circle. A blank DOWN may still be routed here by a parent
        // with an existing touch target; it never acquires ownership on MOVE.
        return pointer >= 0 && pointer <= 31 && (ownedPointers & (1 << pointer)) != 0;
    }

    private void fire(Key key, boolean down) {
        if (listener != null) {
            listener.onKey(key.code, key.keyCode, down);
        }
    }

    private Key find(float x, float y) {
        if (!geometryReady) return null;
        for (int i = 0; i < keys.size(); i++) {
            Key key = keys.get(i);
            if (contains(key, x, y)) return key;
        }
        return null;
    }

    private static boolean contains(Key key, float x, float y) {
        float dx = x - key.renderX, dy = y - key.renderY;
        return dx * dx + dy * dy <= key.targetRadius * key.targetRadius;
    }

    private void resolveGeometry() {
        // View construction may call padding/size overrides before our fields exist.
        if (keys == null || geometryResolving) return;
        // Android resolves relative padding through onRtlPropertiesChanged. Guard
        // before every View getter so that callback cannot reenter this calculation.
        geometryResolving = true;
        try { resolveGeometryCore(); }
        finally { geometryResolving = false; }
    }

    private void resolveGeometryCore() {
        int width = getWidth(), height = getHeight();
        int left = Math.max(0, getPaddingLeft()), top = Math.max(0, getPaddingTop());
        int right = Math.max(left, width - Math.max(0, getPaddingRight()));
        int bottom = Math.max(top, height - Math.max(0, getPaddingBottom()));
        float density = getResources().getDisplayMetrics().density;
        if (!(density > 0f) || Float.isInfinite(density)) density = 1f;
        if (!geometryDirty && geometryWidth == width && geometryHeight == height
                && geometryLeft == left && geometryTop == top && geometryRight == right
                && geometryBottom == bottom && geometryDensity == density) return;

        // Never reinterpret a held finger or virtual hold after a layout adoption.
        releaseAllKeys(true);
        geometryDirty = false; geometryReady = false;
        geometryWidth = width; geometryHeight = height;
        geometryLeft = left; geometryTop = top; geometryRight = right; geometryBottom = bottom;
        geometryDensity = density;
        float contentWidth = right - left, contentHeight = bottom - top;
        if (keys.isEmpty() || contentWidth <= 0f || contentHeight <= 0f) {
            if (accessibility != null) accessibility.changed();
            return;
        }

        float minimum = 24f * density, gap = 8f * density, margin = 8f * density;
        float axis = Math.min(contentWidth, contentHeight);
        for (int i = 0; i < keys.size(); i++) {
            Key key = keys.get(i);
            key.targetRadius = Math.max(minimum, key.r * axis * 1.15f);
            key.renderRadius = key.targetRadius / 1.15f;
            float extent = Math.max(key.targetRadius, key.renderRadius * 1.17f);
            key.renderX = fit(left + key.x * contentWidth, left + extent, right - extent);
            key.renderY = fit(top + key.y * contentHeight, top + extent, bottom - extent);
        }
        // Keep a working authored landscape layout. The compact8dp separation
        // template is used only when the actual shared circles would overlap.
        if (!geometryFits(0f, 0f)) {
            // Preserve the authored action positions when only the arrows needed
            // more room. Reflow an action group only when its constraints fail.
            placeDirections(gap, margin);
            if (!geometryFits(gap, margin)) placeActions(gap, margin, false);
            if (!geometryFits(gap, margin)) placeActions(gap, margin, true);
            if (!geometryFits(gap, margin)) {
                // A fixed minimum-size candidate, not an iterative fit solver.
                for (int i = 0; i < keys.size(); i++) {
                    keys.get(i).targetRadius = minimum;
                    keys.get(i).renderRadius = minimum / 1.15f;
                }
                placeGroups(gap, margin, false);
                if (!geometryFits(gap, margin)) placeGroups(gap, margin, true);
                if (!geometryFits(gap, margin)) {
                    // Below the supported minimum footprint even48dp circles cannot
                    // fit. Keep all bindings visible and bounds truthful, without
                    // claiming an impossible48dp target outside the actual view.
                    float scale = Math.min(contentWidth / (259.2f * density), contentHeight / (143.2f * density));
                    scale = Math.min(1f, scale);
                    for (int i = 0; i < keys.size(); i++) {
                        keys.get(i).targetRadius = minimum * scale;
                        keys.get(i).renderRadius = minimum * scale / 1.15f;
                    }
                    placeGroups(gap * scale, margin * scale, true);
                }
            }
        }
        for (int i = 0; i < keys.size(); i++) {
            Key key = keys.get(i);
            key.targetBounds.set(Math.max(left, (int)Math.floor(key.renderX - key.targetRadius)),
                    Math.max(top, (int)Math.floor(key.renderY - key.targetRadius)),
                    Math.min(right, (int)Math.ceil(key.renderX + key.targetRadius)),
                    Math.min(bottom, (int)Math.ceil(key.renderY + key.targetRadius)));
        }
        geometryReady = true;
        if (accessibility != null) accessibility.changed();
        invalidate();
    }

    private boolean geometryFits(float gap, float margin) {
        for (int i = 0; i < keys.size(); i++) {
            Key key = keys.get(i);
            if (key.renderX - key.targetRadius < geometryLeft + margin - .01f
                    || key.renderX + key.targetRadius > geometryRight - margin + .01f
                    || key.renderY - key.targetRadius < geometryTop + margin - .01f
                    || key.renderY + key.targetRadius > geometryBottom - margin + .01f) return false;
            for (int j = 0; j < i; j++) {
                Key other = keys.get(j);
                float dx = key.renderX - other.renderX, dy = key.renderY - other.renderY;
                float distance = key.targetRadius + other.targetRadius + gap;
                if (dx * dx + dy * dy < distance * distance - .01f) return false;
            }
        }
        return true;
    }

    private void placeGroups(float gap, float margin, boolean compactMenu) {
        placeDirections(gap, margin);
        placeActions(gap, margin, compactMenu);
    }

    private void placeDirections(float gap, float margin) {
        // These are the existing fixed keyboard roles, not inferred remote mappings.
        // Four arrows form a diamond; actual action order/bindings never change.
        if (keys.size() < 7) return;
        float directionRadius = keys.get(0).targetRadius;
        float step = (2f * directionRadius + gap) * .7071067812f;
        float centerX = fit(geometryLeft + (geometryRight - geometryLeft) * .115f,
                geometryLeft + margin + directionRadius + step, geometryRight - margin - directionRadius - step);
        float centerY = fit(geometryTop + (geometryBottom - geometryTop) * .73f,
                geometryTop + margin + directionRadius + step, geometryBottom - margin - directionRadius - step);
        keys.get(0).renderX = centerX; keys.get(0).renderY = centerY - step;
        keys.get(1).renderX = centerX; keys.get(1).renderY = centerY + step;
        keys.get(2).renderX = centerX - step; keys.get(2).renderY = centerY;
        keys.get(3).renderX = centerX + step; keys.get(3).renderY = centerY;
    }

    private void placeActions(float gap, float margin, boolean compactMenu) {
        if (keys.size() < 7) return;
        Key primary = keys.get(4), secondary = keys.get(5), menu = keys.get(keys.size() - 1);
        Key tertiary = keys.size() == 8 ? keys.get(6) : null;
        float thirdRadius = tertiary == null ? 0f : tertiary.targetRadius;
        float horizontal = Math.max(primary.targetRadius + thirdRadius, secondary.targetRadius + menu.targetRadius) + gap;
        float vertical = Math.max(primary.targetRadius + secondary.targetRadius, thirdRadius + menu.targetRadius) + gap;
        float rightX = geometryRight - margin - Math.max(primary.targetRadius, secondary.targetRadius);
        float bottomY = geometryBottom - margin - Math.max(primary.targetRadius, thirdRadius);
        primary.renderX = rightX; primary.renderY = bottomY;
        secondary.renderX = rightX; secondary.renderY = bottomY - vertical;
        if (tertiary != null) { tertiary.renderX = rightX - horizontal; tertiary.renderY = bottomY; }
        if (compactMenu) { menu.renderX = rightX - horizontal; menu.renderY = bottomY - vertical; }
    }

    private static float fit(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
