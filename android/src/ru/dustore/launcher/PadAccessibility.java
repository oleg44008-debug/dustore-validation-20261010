package ru.dustore.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

/** Native virtual controls for the two Canvas pads; input bindings stay in their owners. */
final class PadAccessibility extends AccessibilityNodeProvider {
    static final int NONE = Integer.MIN_VALUE;
    private static final int HOST = -1;
    private static final int HOLD = 0x01010001, RELEASE = 0x01010002;
    private static final String[] DIRECTIONS = { "", "вверх", "вниз", "влево", "вправо" };

    interface Model {
        int[] ids();
        String label(int id);
        boolean bounds(int id, Rect out);
        // Bounds are rectangular for the platform. Owners with circular controls
        // also resolve hover through their actual cached physical shape.
        default boolean hit(int id, float x, float y) { return true; }
        boolean held(int id);
        boolean change(int id, boolean down);
        void releaseAll();
        void focusChanged();
    }

    private final View view;
    private final Model model;
    private final AccessibilityManager manager;
    private final Rect scratch = new Rect();
    private final Rect focusBounds = new Rect();
    private final int[] location = new int[2];
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseArray<Runnable> taps = new SparseArray<>();
    private final Handler inputHandler = new Handler(Looper.getMainLooper());
    private int accessibilityFocus = NONE, keyboardFocus = NONE, hovered = NONE;
    private int keyboardHeld = NONE;

    PadAccessibility(View view, Model model) {
        this.view = view; this.model = model;
        manager = (AccessibilityManager)view.getContext().getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
        focusPaint.setStyle(Paint.Style.STROKE);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        view.setFocusable(true);
        // Virtual controller actions can request keyboard focus after touch use.
        // This applies only to the controller, without changing touch mode.
        view.setFocusableInTouchMode(true);
    }

    static String direction(int direction) { return DIRECTIONS[direction]; }

    @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
        if (id == HOST) {
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(view);
            view.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.view.ViewGroup");
            for (int child : model.ids()) info.addChild(view, child);
            return info;
        }
        if (!model.bounds(id, scratch)) return null;
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.setSource(view, id); info.setParent(view);
        info.setPackageName(view.getContext().getPackageName());
        info.setClassName("android.widget.Button");
        info.setContentDescription(model.label(id) + (Build.VERSION.SDK_INT < 30 && model.held(id) ? ", удерживается" : ""));
        info.setEnabled(view.isEnabled()); info.setFocusable(true); info.setClickable(true);
        info.setLongClickable(true);
        info.setSelected(model.held(id));
        if (Build.VERSION.SDK_INT >= 30) info.setStateDescription(model.held(id) ? "Удерживается" : "Отпущено");
        info.setFocused(keyboardFocus == id); info.setAccessibilityFocused(accessibilityFocus == id);
        info.setBoundsInParent(scratch);
        view.getLocationOnScreen(location);
        scratch.offset(location[0], location[1]);
        info.setBoundsInScreen(scratch);
        info.setVisibleToUser(view.isShown() && view.getWindowVisibility() == View.VISIBLE
                && view.getWidth() > 0 && view.getHeight() > 0);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,
                model.held(id) ? "Отпустить" : "Удерживать"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(model.held(id) ? RELEASE : HOLD,
                model.held(id) ? "Отпустить" : "Удерживать"));
        info.addAction(keyboardFocus == id ? AccessibilityNodeInfo.ACTION_CLEAR_FOCUS : AccessibilityNodeInfo.ACTION_FOCUS);
        info.addAction(accessibilityFocus == id ? AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                : AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        return info;
    }

    @Override public AccessibilityNodeInfo findFocus(int focus) {
        int id = focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY ? accessibilityFocus : keyboardFocus;
        return id == NONE ? null : createAccessibilityNodeInfo(id);
    }

    @Override public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String text, int root) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        if (text == null) return result;
        String wanted = text.toLowerCase(java.util.Locale.ROOT);
        for (int id : model.ids())
            if ((root == HOST || root == id) && model.label(id).toLowerCase(java.util.Locale.ROOT).contains(wanted))
                result.add(createAccessibilityNodeInfo(id));
        return result;
    }

    @Override public boolean performAction(int id, int action, Bundle arguments) {
        if (id == HOST) return view.performAccessibilityAction(action, arguments);
        if (!model.bounds(id, scratch) || !view.isEnabled() || view.getVisibility() != View.VISIBLE) return false;
        switch (action) {
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                if (accessibilityFocus == id) return false;
                if (accessibilityFocus != NONE) send(accessibilityFocus, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                accessibilityFocus = id;
                send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED); model.focusChanged(); return true;
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                if (accessibilityFocus != id) return false;
                accessibilityFocus = NONE;
                send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED); model.focusChanged(); return true;
            case AccessibilityNodeInfo.ACTION_FOCUS:
                // A blocked or unavailable host cannot grant real input focus.
                // Keep its virtual focus unchanged instead of announcing a phantom.
                if ((!view.isFocused() && !view.requestFocus()) || !view.isFocused()) return false;
                setKeyboardFocus(id); return true;
            case AccessibilityNodeInfo.ACTION_CLEAR_FOCUS:
                if (keyboardFocus != id) return false;
                releaseKeyboard(); keyboardFocus = NONE; model.focusChanged(); return true;
            case AccessibilityNodeInfo.ACTION_CLICK:
                // A spoken click is finite even when visual animations are disabled.
                if (model.held(id)) return true;
                if (!model.change(id, true)) return false;
                Runnable release = () -> {
                    taps.remove(id); model.change(id, false); stateChanged(id);
                };
                taps.put(id, release); inputHandler.postDelayed(release, 80);
                send(id, AccessibilityEvent.TYPE_VIEW_CLICKED); stateChanged(id); return true;
            case AccessibilityNodeInfo.ACTION_LONG_CLICK:
                cancelTap(id);
                boolean toggled = model.change(id, !model.held(id));
                if (toggled) { send(id, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED); stateChanged(id); }
                return toggled;
            case HOLD: case RELEASE:
                cancelTap(id);
                boolean changed = model.change(id, action == HOLD);
                if (changed) stateChanged(id);
                return changed;
            default: return false;
        }
    }

    private void setKeyboardFocus(int id) {
        if (keyboardFocus == id) return;
        releaseKeyboard(); keyboardFocus = id;
        send(id, AccessibilityEvent.TYPE_VIEW_FOCUSED); model.focusChanged();
    }

    boolean hover(MotionEvent event) {
        if (manager == null || !manager.isEnabled() || !manager.isTouchExplorationEnabled()) return false;
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_HOVER_ENTER && action != MotionEvent.ACTION_HOVER_MOVE
                && action != MotionEvent.ACTION_HOVER_EXIT) return false;
        int hit = action == MotionEvent.ACTION_HOVER_EXIT ? NONE : at(event.getX(), event.getY());
        if (hit != hovered) {
            int previous = hovered; hovered = hit;
            if (hit != NONE) send(hit, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
            if (previous != NONE) send(previous, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
        }
        return hit != NONE;
    }

    private int at(float x, float y) {
        int[] ids = model.ids();
        for (int i = ids.length - 1; i >= 0; i--)
            if (model.bounds(ids[i], scratch) && scratch.contains((int)x, (int)y)
                    && model.hit(ids[i], x, y)) return ids[i];
        return NONE;
    }

    boolean key(int keyCode, KeyEvent event) {
        if (!view.isEnabled()) return false;
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.getAction() == KeyEvent.ACTION_DOWN
                && (keyboardFocus != NONE || accessibilityFocus != NONE || taps.size() > 0)) {
            model.releaseAll(); model.focusChanged();
            // The containing activity still receives Escape for its usual menu/back path.
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_TAB && event.getAction() == KeyEvent.ACTION_DOWN) {
            int[] ids = model.ids();
            if (ids.length == 0) return false;
            int position = -1;
            for (int i = 0; i < ids.length; i++) if (ids[i] == keyboardFocus) position = i;
            int next = position < 0 ? event.isShiftPressed() ? ids.length - 1 : 0
                : position + (event.isShiftPressed() ? -1 : 1);
            // Let Android move focus out of the controller at the two edges.
            if (next < 0 || next >= ids.length) {
                releaseKeyboard(); keyboardFocus = NONE; model.focusChanged(); return false;
            }
            setKeyboardFocus(ids[next]); return true;
        }
        if (keyboardFocus == NONE) return false;
        if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (!model.held(keyboardFocus) && model.change(keyboardFocus, true)) keyboardHeld = keyboardFocus;
                stateChanged(keyboardFocus);
            } else if (event.getAction() == KeyEvent.ACTION_UP) releaseKeyboard();
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT
                && event.getAction() == KeyEvent.ACTION_DOWN) return moveFocus(keyCode);
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.getAction() == KeyEvent.ACTION_DOWN) {
            releaseKeyboard(); keyboardFocus = NONE; model.focusChanged(); return false;
        }
        return false;
    }

    private boolean moveFocus(int keyCode) {
        if (!model.bounds(keyboardFocus, scratch)) return false;
        float x = scratch.exactCenterX(), y = scratch.exactCenterY(), best = Float.MAX_VALUE;
        int target = NONE;
        for (int id : model.ids()) {
            if (id == keyboardFocus || !model.bounds(id, scratch)) continue;
            float dx = scratch.exactCenterX() - x, dy = scratch.exactCenterY() - y;
            float forward = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -dx : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? dx
                : keyCode == KeyEvent.KEYCODE_DPAD_UP ? -dy : dy;
            if (forward <= 0) continue;
            float side = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? dy : dx;
            float score = forward * forward + side * side * 4;
            if (score < best) { best = score; target = id; }
        }
        if (target == NONE) return false;
        setKeyboardFocus(target); return true;
    }

    private void releaseKeyboard() {
        if (keyboardHeld == NONE) return;
        int id = keyboardHeld; keyboardHeld = NONE;
        model.change(id, false); stateChanged(id);
    }

    private void cancelTap(int id) {
        Runnable pending = taps.get(id);
        if (pending != null) { inputHandler.removeCallbacks(pending); taps.remove(id); }
    }

    /** The owner releases actual input; this cancels delayed work and focus ownership. */
    void reset() {
        for (int i = 0; i < taps.size(); i++) inputHandler.removeCallbacks(taps.valueAt(i));
        taps.clear(); keyboardHeld = NONE; hovered = NONE;
        if (accessibilityFocus != NONE) send(accessibilityFocus, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
        accessibilityFocus = keyboardFocus = NONE;
    }

    void changed() { send(HOST, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); }
    void stateChanged(int id) { send(id, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); }

    private void send(int id, int type) {
        if (manager == null || !manager.isEnabled()) return;
        ViewParent parent = view.getParent();
        if (parent == null) return;
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setSource(view, id); event.setPackageName(view.getContext().getPackageName());
        event.setClassName(id == HOST ? "android.view.ViewGroup" : "android.widget.Button");
        event.setEnabled(view.isEnabled());
        if (id != HOST) event.setContentDescription(model.label(id));
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED);
        parent.requestSendAccessibilityEvent(view, event);
    }

    void drawFocus(Canvas canvas) {
        int id = accessibilityFocus != NONE ? accessibilityFocus : keyboardFocus;
        if (id == NONE || !model.bounds(id, focusBounds)) return;
        float density = view.getResources().getDisplayMetrics().density;
        float inset = Math.max(2f, density * 2f);
        focusPaint.setStrokeWidth(Math.max(2f, density * 2f));
        focusPaint.setColor(Ui.PAD_RIM_HELD);
        canvas.drawRoundRect(focusBounds.left + inset, focusBounds.top + inset,
                focusBounds.right - inset, focusBounds.bottom - inset, density * 8f, density * 8f, focusPaint);
    }
}
