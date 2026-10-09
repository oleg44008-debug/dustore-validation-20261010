package ru.dustore.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/** Native controller materials shared by eXepad, eX and the web-game keyboard. */
final class PadChrome {
    private float textScale = 1f;
    PadChrome(Context context) {
        this();
        float value = context.getResources().getConfiguration().fontScale;
        if (!Float.isNaN(value) && !Float.isInfinite(value)) textScale = Math.max(.85f, Math.min(3f, value));
    }
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final RectF box = new RectF();
    private final Path path = new Path();
    private final Matrix transform = new Matrix();
    private final float[] corners = new float[8];
    private Shader backdrop;
    private int backdropWidth, backdropHeight;

    // Unit-space materials are allocated once. Moving a cap or rendering a streamed
    // frame only changes a local matrix; no gradient object is allocated per button.
    private final Shader shadow = new RadialGradient(0, .08f, 1.14f,
            new int[] { 0x70000000, 0x58000000, 0x00000000 },
            new float[] { 0f, .82f, 1f }, Shader.TileMode.CLAMP);
    private final Shader well = new RadialGradient(0, -.12f, 1.1f,
            new int[] { Ui.alpha(Ui.PAD_VOID, 0xB8), Ui.alpha(Ui.PAD_VOID, 0xD8),
                    Ui.alpha(Ui.LINE_STRONG, 0xA6), Ui.alpha(Ui.PAD_VOID, 0xBB) },
            new float[] { 0f, .70f, .88f, 1f }, Shader.TileMode.CLAMP);
    private final Shader face = new LinearGradient(0, -1f, 0, 1f,
            new int[] { Ui.alpha(Ui.blend(Ui.CARD_HOVER, Ui.TEXT, .08f), 0xE4),
                    Ui.alpha(Ui.CARD, 0xE8), Ui.alpha(Ui.PAD_VOID, 0xF0) },
            new float[] { 0f, .55f, 1f }, Shader.TileMode.CLAMP);
    private final Shader heldFace = new LinearGradient(-.4f, -1f, .6f, 1f,
            new int[] { Ui.blend(Ui.ACTION, Ui.TEXT, .22f), Ui.ACTION,
                    Ui.blend(Ui.ACTION, Ui.ACTION_INK, .20f) },
            new float[] { 0f, .58f, 1f }, Shader.TileMode.CLAMP);
    private final Shader capFace = new LinearGradient(-.4f, -1f, .5f, 1f,
            new int[] { Ui.blend(Ui.CARD_HOVER, Ui.ACCENT, .25f), Ui.CARD_HOVER, Ui.PAD_VOID },
            new float[] { 0f, .5f, 1f }, Shader.TileMode.CLAMP);
    private final Shader capHeld = new LinearGradient(-.5f, -1f, .5f, 1f,
            Ui.ACCENT_HOVER, Ui.ACCENT_PRESSED, Shader.TileMode.CLAMP);

    PadChrome() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    static boolean animationsEnabled(Context context) { return Motion.enabled(context); }

    void background(Canvas canvas, int width, int height) {
        if (width < 1 || height < 1) return;
        if (backdrop == null || backdropWidth != width || backdropHeight != height) {
            backdrop = new LinearGradient(0, 0, width * .82f, height,
                    new int[] { Ui.BACK_SOFT, Ui.PAD_VOID, Ui.blend(Ui.PAD_VOID, Ui.ACCENT, .045f) },
                    new float[] { 0f, .56f, 1f }, Shader.TileMode.CLAMP);
            backdropWidth = width; backdropHeight = height;
        }
        fill.setColor(0xFFFFFFFF); fill.setShader(backdrop);
        canvas.drawRect(0, 0, width, height, fill); clearFill();
    }

    /** A recessed socket or a raised circular face, without changing its touch radius. */
    void disc(Canvas canvas, float cx, float cy, float r, float press, boolean base) {
        if (r <= 0f) return;
        press = clamp(press);
        shade(shadow, cx, cy + r * .025f * (1f - press), r, 255);
        canvas.drawCircle(cx, cy + r * .025f * (1f - press), r * 1.14f, fill);
        shade(base ? well : face, cx, cy, r, 255);
        canvas.drawCircle(cx, cy, r, fill);
        if (!base && press > .001f) {
            shade(heldFace, cx, cy, r, Math.round(255f * press));
            canvas.drawCircle(cx, cy, r * .982f, fill);
        }
        clearFill();
        line.setStrokeWidth(Math.max(1.3f, r * .026f));
        line.setColor(Ui.blend(Ui.alpha(Ui.LINE_STRONG, base ? 0xB0 : 0xEC),
                base ? Ui.ACCENT : Ui.ACTION, press));
        canvas.drawCircle(cx, cy, r - Math.max(.7f, r * .013f), line);
        line.setStrokeWidth(Math.max(.8f, r * .012f));
        line.setColor(Ui.alpha(Ui.TEXT, Math.round((base ? 40f : 62f) * (1f - press))));
        box.set(cx - r * .945f, cy - r * .945f, cx + r * .945f, cy + r * .945f);
        canvas.drawArc(box, 209f, 108f, false, line);
        if (!base) {
            line.setColor(Ui.alpha(Ui.PAD_VOID, Math.round(170f * (1f - press))));
            box.set(cx - r * .82f, cy - r * .82f, cx + r * .82f, cy + r * .82f);
            canvas.drawArc(box, 26f, 129f, false, line);
        }
        if (press > .01f) {
            line.setStrokeWidth(Math.max(1f, r * .018f));
            line.setColor(Ui.alpha(base ? Ui.ACCENT_HOVER : Ui.ACTION, Math.round(85f * press)));
            canvas.drawCircle(cx, cy, r * 1.055f, line);
        }
    }

    /** Beveled action discs, shoulder slabs and centre pills share the same material. */
    void button(Canvas canvas, float cx, float cy, float r, float press, int tone, int style) {
        if (style == 0) {
            disc(canvas, cx, cy, r, press, false);
            if (tone != Ui.PAD_INK && tone != Ui.TEXT) {
                line.setStrokeWidth(Math.max(1.2f, r * .018f));
                line.setColor(Ui.blend(Ui.alpha(tone, 0xB0), Ui.ACTION_INK, press));
                box.set(cx - r * .86f, cy - r * .86f, cx + r * .86f, cy + r * .86f);
                canvas.drawArc(box, 220f, 102f, false, line);
            }
            return;
        }
        float halfW = r * (style == 2 ? .85f : .60f), halfH = r * (style == 2 ? .50f : .77f);
        box.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        float corner = r * (style == 2 ? .49f : .24f);
        for (int i = 0; i < 8; i++) corners[i] = corner;
        if (style == 1) { corners[0] = corners[1] = r * .42f; corners[2] = corners[3] = r * .34f; }
        path.reset(); path.addRoundRect(box, corners, Path.Direction.CW);
        canvas.save();
        canvas.translate(0, r * .04f * (1f - press));
        fill.setColor(Ui.PAD_SHADOW); canvas.drawPath(path, fill);
        canvas.restore();
        shade(face, cx, cy, r, 255); canvas.drawPath(path, fill);
        if (press > .001f) { shade(heldFace, cx, cy, r, Math.round(255f * press)); canvas.drawPath(path, fill); }
        clearFill();
        line.setStrokeWidth(Math.max(1.2f, r * .023f));
        line.setColor(Ui.blend(Ui.alpha(Ui.LINE_STRONG, 0xEF), Ui.ACTION, press));
        canvas.drawPath(path, line);
        line.setStrokeWidth(Math.max(.8f, r * .014f));
        line.setColor(Ui.alpha(Ui.TEXT, Math.round(58f * (1f - press))));
        canvas.drawLine(cx - halfW + corner, cy - halfH + r * .03f,
                cx + halfW - corner, cy - halfH + r * .03f, line);
    }

    void stick(Canvas canvas, float cx, float cy, float r, float x, float y, float press) {
        disc(canvas, cx, cy, r, press * .24f, true);
        ring(canvas, cx, cy, r, press);
        float knobX = cx + x * r * .38f, knobY = cy + y * r * .38f;
        float cap = r * .46f;
        shade(shadow, knobX, knobY + r * .034f * (1f - press), cap, 255);
        canvas.drawCircle(knobX, knobY + r * .034f * (1f - press), cap * 1.14f, fill);
        shade(capFace, knobX, knobY, cap, 255); canvas.drawCircle(knobX, knobY, cap, fill);
        if (press > .001f) { shade(capHeld, knobX, knobY, cap, Math.round(255f * press)); canvas.drawCircle(knobX, knobY, cap * .982f, fill); }
        clearFill();
        line.setStrokeWidth(Math.max(1.3f, r * .019f));
        line.setColor(Ui.blend(Ui.LINE_STRONG, Ui.ACCENT_HOVER, press));
        canvas.drawCircle(knobX, knobY, cap * .973f, line);
        line.setStrokeWidth(Math.max(.9f, r * .012f));
        line.setColor(Ui.blend(Ui.alpha(Ui.TEXT, 0x50), Ui.ACCENT_INK, press * .6f));
        canvas.drawCircle(knobX, knobY, cap * .73f, line);
        box.set(knobX - cap * .82f, knobY - cap * .82f, knobX + cap * .82f, knobY + cap * .82f);
        line.setColor(Ui.alpha(Ui.TEXT, Math.round(83f * (1f - press))));
        canvas.drawArc(box, 215f, 95f, false, line);
        fill.setColor(Ui.blend(Ui.PAD_INK_SOFT, Ui.ACCENT_INK, press));
        canvas.drawCircle(knobX, knobY, Math.max(1.5f, r * .030f), fill);
    }

    private void ring(Canvas canvas, float cx, float cy, float r, float press) {
        line.setStrokeWidth(Math.max(1f, r * .011f));
        line.setColor(Ui.blend(Ui.alpha(Ui.LINE_STRONG, 0xB4), Ui.alpha(Ui.ACCENT, 0xA0), press));
        canvas.drawCircle(cx, cy, r * .76f, line);
        line.setColor(Ui.alpha(Ui.TEXT, 0x28));
        canvas.drawCircle(cx, cy, r * .69f, line);
        for (int i = 0; i < 24; i++) {
            double angle = i * Math.PI / 12.0;
            float cosine = (float)Math.cos(angle), sine = (float)Math.sin(angle);
            line.setColor(Ui.alpha(Ui.PAD_INK_SOFT, i % 6 == 0 ? 0x70 : 0x27));
            canvas.drawLine(cx + cosine * r * .82f, cy + sine * r * .82f,
                    cx + cosine * r * (i % 6 == 0 ? .90f : .86f), cy + sine * r * (i % 6 == 0 ? .90f : .86f), line);
        }
    }

    void dpad(Canvas canvas, float cx, float cy, float r, float x, float y, float press) {
        canvas.save(); canvas.translate(cx, cy);
        path.reset();
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI / 8.0 + i * Math.PI / 4.0;
            float px = (float)Math.cos(angle) * r, py = (float)Math.sin(angle) * r;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        shade(well, 0, 0, r, 255); canvas.drawPath(path, fill); clearFill();
        line.setColor(Ui.blend(Ui.LINE_STRONG, Ui.ACCENT, press * .20f));
        line.setStrokeWidth(Math.max(1.3f, r * .023f)); canvas.drawPath(path, line);
        float arm = r * .265f, reach = r * .82f, bevel = r * .065f;
        path.reset();
        path.moveTo(-arm + bevel, -reach); path.lineTo(arm - bevel, -reach); path.lineTo(arm, -reach + bevel);
        path.lineTo(arm, -arm); path.lineTo(reach - bevel, -arm); path.lineTo(reach, -arm + bevel);
        path.lineTo(reach, arm - bevel); path.lineTo(reach - bevel, arm); path.lineTo(arm, arm);
        path.lineTo(arm, reach - bevel); path.lineTo(arm - bevel, reach); path.lineTo(-arm + bevel, reach);
        path.lineTo(-arm, reach - bevel); path.lineTo(-arm, arm); path.lineTo(-reach + bevel, arm);
        path.lineTo(-reach, arm - bevel); path.lineTo(-reach, -arm + bevel); path.lineTo(-reach + bevel, -arm);
        path.lineTo(-arm, -arm); path.lineTo(-arm, -reach + bevel); path.close();
        shade(face, 0, 0, r, 255); canvas.drawPath(path, fill); clearFill();
        line.setColor(Ui.alpha(Ui.LINE_STRONG, 0xEF)); line.setStrokeWidth(Math.max(1.2f, r * .018f));
        canvas.drawPath(path, line);
        for (int direction = 0; direction < 4; direction++) {
            canvas.save(); canvas.rotate(direction * 90f);
            boolean held = press > .1f && (direction == 0 ? y < -.26f : direction == 1 ? x > .26f : direction == 2 ? y > .26f : x < -.26f);
            if (held) {
                path.reset();
                path.moveTo(-arm + r * .025f, -arm);
                path.lineTo(-arm + r * .025f, -reach + bevel);
                path.lineTo(-arm + bevel, -reach + r * .025f);
                path.lineTo(arm - bevel, -reach + r * .025f);
                path.lineTo(arm - r * .025f, -reach + bevel);
                path.lineTo(arm - r * .025f, -arm);
                path.lineTo(0, -r * .16f); path.close();
                shade(heldFace, 0, -r * .53f, r * .40f, 255);
                canvas.drawPath(path, fill); clearFill();
            }
            line.setColor(held ? Ui.ACTION_INK : Ui.PAD_INK_SOFT);
            line.setStrokeWidth(Math.max(1.6f, r * .026f));
            path.reset(); path.moveTo(-r * .10f, -r * .55f); path.lineTo(0, -r * .65f); path.lineTo(r * .10f, -r * .55f);
            canvas.drawPath(path, line);
            if (!held) {
                line.setColor(Ui.alpha(Ui.TEXT, 0x24)); line.setStrokeWidth(Math.max(.8f, r * .01f));
                canvas.drawLine(-arm + bevel, -reach + r * .034f, arm - bevel, -reach + r * .034f, line);
            }
            canvas.restore();
        }
        fill.setColor(Ui.alpha(Ui.PAD_VOID, 0xA0)); canvas.drawCircle(0, 0, r * .10f, fill);
        line.setColor(Ui.alpha(Ui.LINE_STRONG, 0x8B)); line.setStrokeWidth(Math.max(.8f, r * .012f));
        canvas.drawCircle(0, 0, r * .10f, line);
        canvas.restore();
    }

    void wheel(Canvas canvas, float cx, float cy, float r, float rotation, float press) {
        disc(canvas, cx, cy, r, press * .20f, true);
        ring(canvas, cx, cy, r, press);
        canvas.save(); canvas.rotate(rotation * 38f, cx, cy);
        line.setStrokeWidth(Math.max(3f, r * .065f));
        line.setColor(Ui.blend(Ui.CARD_HOVER, Ui.ACCENT, press * .36f));
        canvas.drawCircle(cx, cy, r * .73f, line);
        line.setStrokeWidth(Math.max(1.3f, r * .018f));
        line.setColor(Ui.blend(Ui.PAD_INK_SOFT, Ui.ACCENT_HOVER, press));
        canvas.drawCircle(cx, cy, r * .764f, line);
        line.setStrokeWidth(Math.max(2f, r * .045f));
        canvas.drawLine(cx - r * .69f, cy - r * .10f, cx - r * .23f, cy + r * .08f, line);
        canvas.drawLine(cx + r * .69f, cy - r * .10f, cx + r * .23f, cy + r * .08f, line);
        canvas.drawLine(cx, cy + r * .70f, cx, cy + r * .23f, line);
        shade(capFace, cx, cy, r * .26f, 255); canvas.drawCircle(cx, cy, r * .26f, fill); clearFill();
        line.setStrokeWidth(Math.max(1f, r * .014f));
        line.setColor(Ui.blend(Ui.LINE_STRONG, Ui.ACCENT, press));
        canvas.drawCircle(cx, cy, r * .26f, line);
        fill.setColor(Ui.blend(Ui.PAD_INK_SOFT, Ui.ACCENT_HOVER, press));
        box.set(cx - r * .045f, cy - r * .80f, cx + r * .045f, cy - r * .69f);
        canvas.drawRoundRect(box, r * .018f, r * .018f, fill);
        canvas.restore();
    }

    static int faceTone(String label, String icon) {
        String value = label == null ? "" : label.trim();
        if ("A".equalsIgnoreCase(value) || "jump".equals(icon) || "ok".equals(icon) || "△".equals(value) || "triangle".equals(icon)) return 0xFF88D9A1;
        if ("B".equalsIgnoreCase(value) || "fire".equals(icon) || "brake".equals(icon) || "cancel".equals(icon) || "○".equals(value) || "circle".equals(icon)) return 0xFFF18B95;
        if ("X".equalsIgnoreCase(value) || "aim".equals(icon) || "mouseR".equals(icon) || "×".equals(value) || "cross".equals(icon)) return 0xFF81BCED;
        if ("Y".equalsIgnoreCase(value) || "gas".equals(icon) || "fist".equals(icon) || "fist2".equals(icon)) return 0xFFE7CF81;
        if ("□".equals(value) || "square".equals(icon)) return 0xFFCAB5EF;
        return Ui.PAD_INK;
    }

    static int buttonStyle(String label, String icon) {
        String value = label == null ? "" : label.trim().toUpperCase(java.util.Locale.ROOT);
        if ("L1".equals(value) || "L2".equals(value) || "R1".equals(value) || "R2".equals(value)
                || "LB".equals(value) || "LT".equals(value) || "RB".equals(value) || "RT".equals(value)
                || "gas".equals(icon) || "brake".equals(icon)) return 1;
        if ("menu".equals(icon) || "ESC".equals(value) || "START".equals(value) || "SELECT".equals(value)
                || "PAUSE".equals(value) || "BACK".equals(value) || "VIEW".equals(value) || "OPTIONS".equals(value) || "MENU".equals(value)) return 2;
        return 0;
    }

    static String faceSymbol(String label) {
        if ("△".equals(label) || "▲".equals(label) || "Δ".equals(label)) return "triangle";
        if ("□".equals(label) || "■".equals(label) || "▢".equals(label)) return "square";
        if ("○".equals(label) || "◯".equals(label) || "〇".equals(label)) return "circle";
        if ("×".equals(label) || "✕".equals(label)) return "cross";
        return null;
    }

    /** Centres real font metrics and preserves every label's own content. */
    void label(Canvas canvas, String value, float cx, float cy, float maxWidth, float size, int color) {
        if (value == null || value.isEmpty() || size < 3f || maxWidth <= 0f) return;
        float minimum = size * .74f;
        size *= textScale;
        text.setTextSize(size);
        float measured = text.measureText(value);
        if (measured > maxWidth) text.setTextSize(Math.max(minimum, size * Math.max(1f, maxWidth - 1f) / measured));
        String shown = value;
        if (text.measureText(shown) > maxWidth) {
            int count = text.breakText(shown, true, Math.max(0f, maxWidth - text.measureText("…")), null);
            shown = shown.substring(0, count) + "…";
        }
        text.setColor(color); text.getFontMetrics(metrics);
        float baseline = cy - (metrics.ascent + metrics.descent) * .5f;
        text.setShadowLayer(Math.max(.7f, size * .045f), 0, size * .035f, 0x68000000);
        canvas.drawText(shown, cx, baseline, text);
        text.clearShadowLayer();
    }

    private void shade(Shader shader, float cx, float cy, float scale, int opacity) {
        transform.setScale(scale, scale); transform.postTranslate(cx, cy);
        shader.setLocalMatrix(transform);
        fill.setColor(0xFFFFFFFF); fill.setShader(shader); fill.setAlpha(opacity);
    }

    private void clearFill() { fill.setShader(null); fill.setAlpha(255); }
    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
}

