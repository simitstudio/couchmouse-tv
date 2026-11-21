package app.couchmouse.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.media.AudioManager;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Mouse cursor driven by the phone touchpad (Server). Drawn as a small accessibility
 * overlay window (no extra permission), clicks/scrolls are injected as touch gestures.
 * Runs on the main thread; Server posts each message here.
 *
 * Messages: "m dx dy" move · "t" tap · "l" long press · "s dy" scroll · "sh dx" sideways
 *           scroll · "b" back · "h" home · "r" recent apps.
 */
final class Pointer {
    private static final String TAG = "Pointer";
    private static final long HIDE_MS = 6000;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler handler = new Handler();
    private final int w, h, size;
    private final View cursor;
    private final WindowManager.LayoutParams lp;
    private boolean shown;
    private float x, y, scroll, scrollX;

    private final Runnable hide = new Runnable() {
        @Override
        public void run() {
            if (shown) {
                wm.removeView(cursor);
                shown = false;
            }
        }
    };

    Pointer(AccessibilityService s) {
        svc = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        w = p.x;
        h = p.y;
        x = w / 2f;
        y = h / 2f;
        size = Math.round(40 * s.getResources().getDisplayMetrics().density);
        cursor = new Arrow(s);
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
    }

    void handle(String msg) {
        try {
            String[] a = msg.split(" ");
            switch (a[0]) {
                case "m":
                    x = clamp(x + Float.parseFloat(a[1]), w);
                    y = clamp(y + Float.parseFloat(a[2]), h);
                    show();
                    break;
                case "t":
                    show();
                    gesture(stroke(x, y, x, y, 40));
                    break;
                case "l":
                    show();
                    gesture(stroke(x, y, x, y, 800));
                    break;
                case "s":
                    show();
                    scroll += Float.parseFloat(a[1]);
                    if (Math.abs(scroll) >= 40) {
                        // Swipe in the finger's direction, starting at the cursor.
                        float d = Math.max(-h * 0.6f, Math.min(h * 0.6f, scroll * 2.5f));
                        float y0 = clamp(y - d / 2, h), y1 = clamp(y + d / 2, h);
                        gesture(stroke(x, y0, x, y1, 160));
                        scroll = 0;
                    }
                    break;
                case "sh":
                    show();
                    scrollX += Float.parseFloat(a[1]);
                    if (Math.abs(scrollX) >= 40) {
                        float d = Math.max(-w * 0.6f, Math.min(w * 0.6f, scrollX * 2.5f));
                        float x0 = clamp(x - d / 2, w), x1 = clamp(x + d / 2, w);
                        gesture(stroke(x0, y, x1, y, 160));
                        scrollX = 0;
                    }
                    break;
                case "b":
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    break;
                case "h":
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                    break;
                case "r":
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                    break;
                case "v+":
                case "v-":
                    ((AudioManager) svc.getSystemService(Context.AUDIO_SERVICE)).adjustStreamVolume(
                            AudioManager.STREAM_MUSIC, a[0].equals("v+") ? AudioManager.ADJUST_RAISE
                                    : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                    break;
                default:
            }
        } catch (Exception e) {
            Log.w(TAG, "bad message " + msg, e);
        }
    }

    private static float clamp(float v, int max) {
        return Math.max(0, Math.min(max - 1, v));
    }

    private GestureDescription stroke(float x0, float y0, float x1, float y1, long ms) {
        Path p = new Path();
        p.moveTo(x0, y0);
        if (x0 != x1 || y0 != y1) p.lineTo(x1, y1);
        return new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build();
    }

    private void gesture(GestureDescription g) {
        if (!svc.dispatchGesture(g, null, null)) Log.w(TAG, "gesture refused");
    }

    private void show() {
        lp.x = Math.round(x);
        lp.y = Math.round(y);
        try {
            if (shown) {
                wm.updateViewLayout(cursor, lp);
            } else {
                wm.addView(cursor, lp);
                shown = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "overlay", e);
        }
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, HIDE_MS);
    }

    void destroy() {
        handler.removeCallbacks(hide);
        hide.run();
    }

    /** Classic arrow pointer; the tip is the window's top-left corner (the click point). */
    private static final class Arrow extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Arrow(Context c) {
            super(c);
            fill.setColor(0xFFFFFFFF);
            edge.setColor(0xFF000000);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth() / 24f;
            edge.setStrokeWidth(1.6f * s);
            path.reset();
            path.moveTo(1 * s, 1 * s);
            path.lineTo(1 * s, 19 * s);
            path.lineTo(6 * s, 14.5f * s);
            path.lineTo(9.5f * s, 22 * s);
            path.lineTo(13 * s, 20.5f * s);
            path.lineTo(9.5f * s, 13.5f * s);
            path.lineTo(16 * s, 13.5f * s);
            path.close();
            c.drawPath(path, edge);
            c.drawPath(path, fill);
        }
    }
}
