package dev.tvcallalert;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import java.util.Locale;
import java.util.Random;

/**
 * The full-screen call card, drawn entirely in code as an anime-style cut-in: impact flash,
 * radial speed lines, a diagonal band that slashes across the screen, and the caller's name
 * slamming in. All sizes are in units of 1/1080 of the view height.
 */
final class AnimeCardView extends View {
    private static final int DIM = 0xFF0B0B18;
    private static final int ACCENT = 0xFFFF2E63;
    private static final int ACCENT_DARK = 0xFFC2184A;
    private static final int CYAN = 0xFF08D9D6;
    private static final int YELLOW = 0xFFFFD400;
    private static final int INK = 0xFF0A0A0F;
    private static final int WHITE = 0xFFFFFFFF;

    private static final long KEY_GUARD_MILLIS = 800;
    private static final long BAND_START = 100;
    private static final long BAND_MILLIS = 280;
    private static final long NAME_START = 300;
    private static final long NAME_MILLIS = 180;
    private static final long IMPACT = NAME_START + NAME_MILLIS;
    private static final long SHAKE_MILLIS = 280;
    private static final long SUB_START = 520;
    private static final long SUB_MILLIS = 260;
    private static final long RING_PERIOD = 2000;
    private static final long RING_ON = 900;
    private static final int SPEED_LINES = 54;
    private static final float BAND_TILT_DEGREES = -6f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint portraitPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Random random = new Random();
    private final Matrix matrix = new Matrix();

    private final String tag;
    private final String isCalling;
    private final String hint;
    private final Bitmap portrait;
    private String name;
    private long startedAt;
    private Runnable onDismiss;

    AnimeCardView(Context context, String name, Bitmap portrait) {
        super(context);
        this.tag = context.getString(R.string.card_tag);
        this.isCalling = context.getString(R.string.card_is_calling);
        this.hint = context.getString(R.string.card_hint);
        this.portrait = portrait;
        setFocusable(true);
        setFocusableInTouchMode(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
        text.setTextAlign(Paint.Align.CENTER);
        text.setStrokeJoin(Paint.Join.ROUND);
        if (portrait != null) {
            portraitPaint.setShader(new BitmapShader(portrait, Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP));
        }
        restart(name);
    }

    /** Replays the entrance, optionally for a different caller. */
    void restart(String newName) {
        name = newName.toUpperCase(Locale.getDefault());
        startedAt = SystemClock.uptimeMillis();
        setContentDescription(newName + " " + isCalling);
        invalidate();
    }

    void setOnDismiss(Runnable onDismiss) {
        this.onDismiss = onDismiss;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean closes = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BACK
                || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BUTTON_A
                || code == KeyEvent.KEYCODE_BUTTON_B;
        if (!closes) {
            return super.dispatchKeyEvent(event);
        }
        // Ignore a press that was already on its way to the show when the card appeared.
        boolean armed = SystemClock.uptimeMillis() - startedAt >= KEY_GUARD_MILLIS;
        if (armed && event.getAction() == KeyEvent.ACTION_UP && onDismiss != null) {
            onDismiss.run();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (onDismiss != null && SystemClock.uptimeMillis() - startedAt >= KEY_GUARD_MILLIS) {
            onDismiss.run();
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        final long t = SystemClock.uptimeMillis() - startedAt;
        final float u = h / 1080f;
        final float cx = w / 2f;
        final float cy = h / 2f;

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(DIM);
        fill.setAlpha((int) (235 * clamp01(t / 150f)));
        canvas.drawRect(0, 0, w, h, fill);

        canvas.save();
        if (t >= IMPACT && t < IMPACT + SHAKE_MILLIS) {
            float amp = 16 * u * (1 - (t - IMPACT) / (float) SHAKE_MILLIS);
            canvas.translate((float) Math.sin(t * 0.09) * amp, (float) Math.cos(t * 0.11) * amp);
        }
        drawSpeedLines(canvas, t, w, h, cx, cy);

        canvas.save();
        canvas.rotate(BAND_TILT_DEGREES, cx, cy);
        float bandIn = overshoot(clamp01((t - BAND_START) / (float) BAND_MILLIS));
        if (t >= BAND_START) {
            drawBand(canvas, w, cy, u, bandIn);
            float iconX = w * 0.17f - (1 - bandIn) * w;
            drawCallerIcon(canvas, t, iconX, cy, u);
            drawName(canvas, t, w, cy, u);
        }
        canvas.restore();

        drawTag(canvas, t, u);
        drawHint(canvas, t, cx, h, u);
        canvas.restore();

        drawFlash(canvas, t, w, h);
        postInvalidateOnAnimation();
    }

    private void drawSpeedLines(Canvas canvas, long t, float w, float h, float cx, float cy) {
        if (t < 120) {
            return;
        }
        // A new random set every 70 ms gives the hand-drawn flicker of animation frames.
        random.setSeed(t / 70);
        float reach = (float) Math.hypot(w, h);
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < SPEED_LINES; i++) {
            double angle = (i + random.nextFloat()) * (2 * Math.PI / SPEED_LINES);
            float inner = h * (0.30f + random.nextFloat() * 0.22f);
            double half = 0.004 + random.nextFloat() * 0.016;
            fill.setColor(WHITE);
            fill.setAlpha(40 + random.nextInt(90));
            path.rewind();
            path.moveTo(cx + (float) Math.cos(angle) * inner, cy + (float) Math.sin(angle) * inner);
            path.lineTo(cx + (float) Math.cos(angle - half) * reach,
                    cy + (float) Math.sin(angle - half) * reach);
            path.lineTo(cx + (float) Math.cos(angle + half) * reach,
                    cy + (float) Math.sin(angle + half) * reach);
            path.close();
            canvas.drawPath(path, fill);
        }
    }

    private void drawBand(Canvas canvas, float w, float cy, float u, float in) {
        float half = 190 * u;
        float left = -w * 0.3f - (1 - in) * w * 1.6f;
        float right = left + w * 1.6f;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(INK);
        canvas.drawRect(left, cy - half - 22 * u, right, cy + half + 22 * u, fill);
        fill.setColor(ACCENT);
        canvas.drawRect(left, cy - half, right, cy + half, fill);

        // Halftone dots that grow toward the right edge, like printed manga shading.
        fill.setColor(ACCENT_DARK);
        float step = 30 * u;
        float from = w * 0.45f;
        int row = 0;
        for (float y = cy - half + step / 2; y < cy + half; y += step, row++) {
            float offset = (row % 2) * step / 2;
            for (float x = from + offset; x < right; x += step) {
                float grow = clamp01((x - from) / (w * 0.75f));
                canvas.drawCircle(x, y, step * 0.48f * grow, fill);
            }
        }
        fill.setColor(WHITE);
        canvas.drawRect(left, cy - half - 10 * u, right, cy - half - 4 * u, fill);
        fill.setColor(YELLOW);
        canvas.drawRect(left, cy + half + 4 * u, right, cy + half + 10 * u, fill);
    }

    private void drawCallerIcon(Canvas canvas, long t, float x, float y, float u) {
        float r = 150 * u;
        long phase = t % RING_PERIOD;
        boolean ringing = t > IMPACT && phase < RING_ON;
        float wobble = ringing ? (float) Math.sin(t / 28.0) * 11f : 0f;

        canvas.save();
        canvas.rotate(wobble, x, y);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(INK);
        canvas.drawCircle(x + 10 * u, y + 10 * u, r, fill);
        fill.setColor(WHITE);
        canvas.drawCircle(x, y, r, fill);
        if (portrait != null) {
            float side = Math.min(portrait.getWidth(), portrait.getHeight());
            float scale = (2 * (r - 10 * u)) / side;
            matrix.reset();
            matrix.setTranslate(-portrait.getWidth() / 2f, -portrait.getHeight() / 2f);
            matrix.postScale(scale, scale);
            matrix.postTranslate(x, y);
            portraitPaint.getShader().setLocalMatrix(matrix);
            canvas.drawCircle(x, y, r - 10 * u, portraitPaint);
        } else {
            drawHandset(canvas, x, y, u);
        }
        stroke.setColor(INK);
        stroke.setStrokeWidth(12 * u);
        canvas.drawCircle(x, y, r, stroke);
        canvas.restore();

        if (ringing) {
            // Sound-effect arcs that pop outward with each ring.
            float pulse = (phase % 450) / 450f;
            stroke.setColor(YELLOW);
            for (int i = 0; i < 3; i++) {
                float rr = r + (36 + i * 34 + pulse * 26) * u;
                stroke.setStrokeWidth((14 - i * 3) * u);
                stroke.setAlpha((int) (255 * (1 - pulse * 0.7f)));
                rect.set(x - rr, y - rr, x + rr, y + rr);
                canvas.drawArc(rect, -62, 44, false, stroke);
                canvas.drawArc(rect, 118, 44, false, stroke);
            }
            stroke.setAlpha(255);
        }
    }

    private void drawHandset(Canvas canvas, float x, float y, float u) {
        canvas.save();
        canvas.rotate(-35, x, y);
        canvas.scale(1.15f, 1.15f, x, y);
        canvas.translate(0, -20 * u);
        stroke.setColor(INK);
        stroke.setStrokeWidth(34 * u);
        rect.set(x - 78 * u, y - 52 * u, x + 78 * u, y + 104 * u);
        canvas.drawArc(rect, 205, 130, false, stroke);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(INK);
        rect.set(x - 104 * u, y - 14 * u, x - 44 * u, y + 62 * u);
        canvas.drawRoundRect(rect, 22 * u, 22 * u, fill);
        rect.set(x + 44 * u, y - 14 * u, x + 104 * u, y + 62 * u);
        canvas.drawRoundRect(rect, 22 * u, 22 * u, fill);
        canvas.restore();
    }

    private void drawName(Canvas canvas, long t, float w, float cy, float u) {
        if (t < NAME_START) {
            return;
        }
        float p = clamp01((t - NAME_START) / (float) NAME_MILLIS);
        float scale = 3f - 2f * (1 - (1 - p) * (1 - p));
        if (t > IMPACT) {
            long phase = t % RING_PERIOD;
            scale = 1f + (phase < RING_ON ? 0.03f * (float) Math.abs(Math.sin(phase / 75.0)) : 0f);
        }
        float x = w * 0.60f;
        float y = cy + 10 * u;
        float maxWidth = w * 0.62f;
        float size = 230 * u;
        text.setTextSkewX(-0.12f);
        text.setTextSize(size);
        float measured = text.measureText(name);
        if (measured > maxWidth) {
            size *= maxWidth / measured;
            text.setTextSize(size);
        }
        int alpha = (int) (255 * clamp01(p * 2));

        canvas.save();
        canvas.scale(scale, scale, x, y - size * 0.3f);
        text.setStyle(Paint.Style.FILL);
        text.setColor(CYAN);
        text.setAlpha(alpha);
        canvas.drawText(name, x + 12 * u, y + 12 * u, text);
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeWidth(26 * u);
        text.setColor(INK);
        text.setAlpha(alpha);
        canvas.drawText(name, x, y, text);
        text.setStyle(Paint.Style.FILL);
        text.setColor(WHITE);
        text.setAlpha(alpha);
        canvas.drawText(name, x, y, text);
        canvas.restore();

        if (t < SUB_START) {
            return;
        }
        float s = decelerate(clamp01((t - SUB_START) / (float) SUB_MILLIS));
        float sx = x + (1 - s) * w * 0.8f;
        float sy = y + 128 * u;
        text.setTextSize(98 * u);
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeWidth(20 * u);
        text.setColor(INK);
        canvas.drawText(isCalling, sx, sy, text);
        text.setStyle(Paint.Style.FILL);
        text.setColor(YELLOW);
        canvas.drawText(isCalling, sx, sy, text);
    }

    private void drawTag(Canvas canvas, long t, float u) {
        if (t < SUB_START) {
            return;
        }
        float s = decelerate(clamp01((t - SUB_START) / (float) SUB_MILLIS));
        text.setTextSkewX(-0.12f);
        text.setTextSize(46 * u);
        float tw = text.measureText(tag);
        float left = 90 * u - (1 - s) * (tw + 200 * u);
        float top = 84 * u;
        float bottom = top + 78 * u;
        float right = left + tw + 80 * u;
        float slant = 22 * u;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(INK);
        drawSlantedBox(canvas, left + 8 * u, top + 8 * u, right + 8 * u, bottom + 8 * u, slant);
        fill.setColor(YELLOW);
        drawSlantedBox(canvas, left, top, right, bottom, slant);
        text.setStyle(Paint.Style.FILL);
        text.setColor(INK);
        canvas.drawText(tag, (left + right) / 2f, bottom - 22 * u, text);
    }

    private void drawSlantedBox(Canvas canvas, float left, float top, float right, float bottom,
            float slant) {
        path.rewind();
        path.moveTo(left + slant, top);
        path.lineTo(right + slant, top);
        path.lineTo(right, bottom);
        path.lineTo(left, bottom);
        path.close();
        canvas.drawPath(path, fill);
    }

    private void drawHint(Canvas canvas, long t, float cx, float h, float u) {
        if (t < KEY_GUARD_MILLIS) {
            return;
        }
        float blink = 0.65f + 0.35f * (float) Math.abs(Math.sin(t / 520.0));
        text.setTextSkewX(0f);
        text.setTextSize(50 * u);
        float y = h - 92 * u;
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeWidth(12 * u);
        text.setColor(INK);
        text.setAlpha((int) (255 * blink));
        canvas.drawText(hint, cx, y, text);
        text.setStyle(Paint.Style.FILL);
        text.setColor(WHITE);
        text.setAlpha((int) (255 * blink));
        canvas.drawText(hint, cx, y, text);
    }

    private void drawFlash(Canvas canvas, long t, float w, float h) {
        float alpha = 0f;
        if (t < 160) {
            alpha = 1f - t / 160f;
        } else if (t >= IMPACT && t < IMPACT + 150) {
            alpha = 0.75f * (1f - (t - IMPACT) / 150f);
        } else if (t > IMPACT + RING_PERIOD) {
            long phase = t % RING_PERIOD;
            if (phase < 110) {
                alpha = 0.16f * (1f - phase / 110f);
            }
        }
        if (alpha > 0f) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(WHITE);
            fill.setAlpha((int) (255 * alpha));
            canvas.drawRect(0, 0, w, h, fill);
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private static float decelerate(float p) {
        return 1f - (1f - p) * (1f - p);
    }

    /** Eases to 1 with a small overshoot, so the band snaps into place. */
    private static float overshoot(float p) {
        float q = p - 1f;
        return q * q * (2.7f * q + 1.7f) + 1f;
    }
}
