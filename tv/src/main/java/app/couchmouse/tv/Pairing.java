package app.couchmouse.tv;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pairing, so only phones you approve can control the TV. Two ways:
 *
 *  - QR code: the TV's setup screen shows a QR code holding the TV's address, its certificate
 *    fingerprint and a one-time secret (valid 10 minutes, single use). The phone scans it and
 *    sends "qr <secret>".
 *  - Code: the phone sends "code?"; the TV shows a 4-digit code in a card at the top right, over
 *    any app or input, for 2 minutes; the phone sends "code 1234".
 *
 * Either way the TV answers "key K": a random key the phone keeps (iOS Keychain / Android
 * encrypted storage) and sends with "hello" on every later connection. Only a SHA-256 of each
 * key is stored here (pref "phones", JSON). Wrong codes: after 3 the TV shows a new code; after
 * 10 within 10 minutes pairing pauses until those 10 minutes are up.
 *
 * Replies (sent by Server): "code" / "code new" (a code / a new code is on the TV) · "bad N"
 * (wrong, N tries left for this code; 0 = a new code is shown) · "wait S" (paused for S seconds)
 * · "key K" (paired). See PROTOCOL.md.
 */
final class Pairing {
    private static final String TAG = "Pairing";
    static final String PHONES = "phones";
    private static final long QR_MS = 10 * 60 * 1000L;
    private static final long CODE_MS = 2 * 60 * 1000L;
    private static final int TRIES_PER_CODE = 3;
    private static final int MAX_FAILS = 10;
    private static final long FAIL_WINDOW_MS = 10 * 60 * 1000L;
    private static final int MAX_PHONES = 10;

    private static final SecureRandom random = new SecureRandom();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static String qr;       // one-time secret in the QR code on the setup screen
    private static long qrUntil;
    private static String code;     // code shown on the TV, null when none
    private static long codeUntil;
    private static int codeTries;
    private static int waiting;     // open connections waiting for the code to be typed
    private static int fails;
    private static long failsSince;

    private Pairing() {
    }

    /** A paired phone (or browser on it). */
    static final class Phone {
        String hash, name;
        long paired, used;
    }

    static synchronized List<Phone> phones(Context c) {
        List<Phone> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(PHONES, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Phone p = new Phone();
                p.hash = o.getString("h");
                p.name = o.optString("n", "Phone");
                p.paired = o.optLong("p");
                p.used = o.optLong("u");
                out.add(p);
            }
        } catch (Exception e) {
            Log.w(TAG, "phones", e);
        }
        return out;
    }

    private static void save(Context c, List<Phone> list) {
        JSONArray a = new JSONArray();
        try {
            for (Phone p : list) {
                a.put(new JSONObject().put("h", p.hash).put("n", p.name).put("p", p.paired).put("u", p.used));
            }
        } catch (Exception e) {
            Log.w(TAG, "save", e);
        }
        sp(c).edit().putString(PHONES, a.toString()).apply();
    }

    /** The key's hash if it belongs to a paired phone (and notes that it was used), else null. */
    static synchronized String check(Context c, String key) {
        if (key == null || key.length() < 16) return null;
        String h = sha256(key);
        List<Phone> list = phones(c);
        for (Phone p : list) {
            if (p.hash.equals(h)) {
                p.used = System.currentTimeMillis();
                save(c, list);
                return h;
            }
        }
        return null;
    }

    /** Forgets one phone (by hash), or all of them (null), and disconnects it straight away. */
    static synchronized void forget(Context c, String hash) {
        List<Phone> list = phones(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (hash == null || list.get(i).hash.equals(hash)) list.remove(i);
        }
        save(c, list);
        Server.drop(hash);
    }

    static android.content.SharedPreferences sp(Context c) {
        return c.getSharedPreferences("couchmouse", Context.MODE_PRIVATE);
    }

    /** The secret for the setup screen's QR code (a new one once used or after 10 minutes). */
    static synchronized String qrSecret() {
        if (qr == null || System.currentTimeMillis() > qrUntil) {
            byte[] b = new byte[16];
            random.nextBytes(b);
            qr = hex(b);
            qrUntil = System.currentTimeMillis() + QR_MS;
        }
        return qr;
    }

    /** The phone scanned the QR code. Returns the reply: "key K", or "bad qr". */
    static synchronized String scanned(Context c, String secret, String name) {
        if (qr == null || System.currentTimeMillis() > qrUntil || !java.security.MessageDigest.isEqual(
                qr.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                String.valueOf(secret).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return "bad qr";
        }
        qr = null; // single use: the setup screen shows a fresh one
        return "key " + addPhone(c, name);
    }

    private static String addPhone(Context c, String name) {
        long now = System.currentTimeMillis();
        byte[] k = new byte[16];
        random.nextBytes(k);
        String key = hex(k);
        List<Phone> list = phones(c);
        Phone p = new Phone();
        p.hash = sha256(key);
        p.name = name;
        p.paired = p.used = now;
        list.add(p);
        while (list.size() > MAX_PHONES) { // drop the one unused for longest
            int old = 0;
            for (int i = 1; i < list.size(); i++) if (list.get(i).used < list.get(old).used) old = i;
            list.remove(old);
        }
        save(c, list);
        show(c, c.getString(R.string.card_paired_title), "\u2713", c.getString(R.string.card_paired_body, name), 4000);
        return key;
    }

    /** A phone without a valid key asked for a code: show it on the TV. Returns the reply. */
    static synchronized String request(Context c, String device) {
        long paused = pausedFor();
        if (paused > 0) return "wait " + (paused / 1000 + 1);
        waiting++;
        if (code == null || System.currentTimeMillis() > codeUntil) newCode();
        showCode(c, device);
        return "code";
    }

    /** The phone typed a code. Returns the reply; "token K" means it is now paired. */
    static synchronized String submit(Context c, String typed, String device, String name) {
        long now = System.currentTimeMillis();
        long paused = pausedFor();
        if (paused > 0) return "wait " + (paused / 1000 + 1);
        if (code == null || now > codeUntil) {
            newCode();
            showCode(c, device);
            return "code new";
        }
        if (code.equals(typed)) {
            code = null;
            waiting = Math.max(0, waiting - 1);
            return "key " + addPhone(c, name);
        }
        if (now - failsSince > FAIL_WINDOW_MS) {
            fails = 0;
            failsSince = now;
        }
        fails++;
        codeTries++;
        if (fails >= MAX_FAILS) {
            code = null;
            long min = (pausedFor() + 59999) / 60000;
            show(c, c.getString(R.string.card_paused_title), "!", c.getString(R.string.card_paused_body, (int) min), 10000);
            return "wait " + (pausedFor() / 1000 + 1);
        }
        if (codeTries >= TRIES_PER_CODE) {
            newCode();
            showCode(c, device);
            return "bad 0";
        }
        return "bad " + (TRIES_PER_CODE - codeTries);
    }

    /** A connection that was waiting for the code closed. */
    static synchronized void left() {
        waiting = Math.max(0, waiting - 1);
        // Nobody is looking at the code any more; it stays valid until it expires, and shows
        // again if the phone reconnects (e.g. the browser went to the background).
        if (waiting == 0 && code != null) main.post(hideCard);
    }

    private static long pausedFor() {
        if (fails < MAX_FAILS) return 0;
        long left = failsSince + FAIL_WINDOW_MS - System.currentTimeMillis();
        if (left > 0) return left;
        fails = 0;
        return 0;
    }

    private static void newCode() {
        code = String.format(Locale.ENGLISH, "%04d", random.nextInt(10000));
        codeUntil = System.currentTimeMillis() + CODE_MS;
        codeTries = 0;
    }

    static String sha256(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ENGLISH, "%02x", x));
        return sb.toString();
    }

    // ---- The card on the TV (accessibility overlay: above any app or input, never takes focus) ----

    private static View card;
    private static TextView title, big, sub;
    private static WindowManager cardWm;
    private static boolean shown;
    /** Set while the setup screen is open: its QR code is on the right. */
    static volatile boolean cardBottomLeft;

    private static final Runnable hideCard = new Runnable() {
        @Override
        public void run() {
            if (shown) {
                try {
                    cardWm.removeView(card);
                } catch (Exception ignored) {
                }
                shown = false;
            }
        }
    };

    private static void showCode(Context c, String device) {
        String s = code.charAt(0) + " " + code.charAt(1) + " " + code.charAt(2) + " " + code.charAt(3);
        show(c, c.getString(R.string.card_pair_title), s, c.getString(R.string.card_pair_body, device),
                codeUntil - System.currentTimeMillis());
    }

    /** c must be the accessibility service (KeyService) for the overlay window type. */
    private static void show(final Context c, final String t, final String b, final String s, final long ms) {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (card == null) build(c);
                    title.setText(t);
                    big.setText(b);
                    sub.setText(s);
                    if (!shown) {
                        cardWm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                        cardWm.addView(card, params(c));
                        shown = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "card", e);
                }
                main.removeCallbacks(hideCard);
                main.postDelayed(hideCard, Math.max(1000, ms));
            }
        });
    }

    /** KeyService is going away (main thread). */
    static void destroy() {
        main.removeCallbacks(hideCard);
        hideCard.run();
        card = null;
    }

    private static int px(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    private static void build(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(px(c, 36), px(c, 20), px(c, 36), px(c, 22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2162033);
        bg.setCornerRadius(px(c, 20));
        bg.setStroke(px(c, 2), 0xFF60A5FA);
        box.setBackground(bg);
        title = text(c, 17, 0xFFE2E8F0, Typeface.DEFAULT);
        big = text(c, 58, 0xFFFFFFFF, Typeface.DEFAULT_BOLD);
        sub = text(c, 15, 0xFF94A3B8, Typeface.DEFAULT);
        box.addView(title);
        box.addView(big);
        box.addView(sub);
        card = box;
    }

    private static TextView text(Context c, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static WindowManager.LayoutParams params(Context c) {
        // Fixed width: a WRAP_CONTENT window is first measured at Android's dialog width, which
        // wraps the bottom line at different places from one message to the next.
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                px(c, 320), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = cardBottomLeft ? Gravity.BOTTOM | Gravity.LEFT : Gravity.TOP | Gravity.RIGHT;
        lp.x = lp.y = px(c, 40);
        return lp;
    }
}
