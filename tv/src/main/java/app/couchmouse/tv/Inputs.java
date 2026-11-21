package app.couchmouse.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Everything except the cursor: typing, apps, media keys, arrow-key focus, volume, standby. */
final class Inputs {
    private Inputs() {
    }

    static String capabilities() {
        String c = "cursor,scroll,text,apps,media,volume,dpad,back,home,recents";
        return Build.VERSION.SDK_INT >= 28 ? c + ",power" : c;
    }

    // ---- Typing into the focused text box ----

    private static AccessibilityNodeInfo field(AccessibilityService s) {
        try {
            AccessibilityNodeInfo n = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            return n != null && n.isEditable() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean editing(AccessibilityService s) {
        return field(s) != null;
    }

    private static String current(AccessibilityNodeInfo n) {
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText()) return "";
        CharSequence t = n.getText();
        return t == null ? "" : t.toString();
    }

    /** Inserts text at the cursor (replacing any selection). */
    static boolean type(AccessibilityService s, String add) {
        AccessibilityNodeInfo n = field(s);
        if (n == null) return false;
        String text = current(n);
        int a = n.getTextSelectionStart(), b = n.getTextSelectionEnd();
        if (a < 0 || a > text.length() || b < a || b > text.length()) a = b = text.length();
        return set(n, text.substring(0, a) + add + text.substring(b), a + add.length());
    }

    /** Deletes the selection, or the character before the cursor. */
    static boolean backspace(AccessibilityService s) {
        AccessibilityNodeInfo n = field(s);
        if (n == null) return false;
        String text = current(n);
        int a = n.getTextSelectionStart(), b = n.getTextSelectionEnd();
        if (a < 0 || a > text.length() || b < a || b > text.length()) a = b = text.length();
        if (a == b) {
            if (a == 0) return true;
            a = text.offsetByCodePoints(a, -1);
        }
        return set(n, text.substring(0, a) + text.substring(b), a);
    }

    /** The keyboard's Search/Go/Done key, where the TV supports it (Android 11+). */
    static boolean enter(AccessibilityService s) {
        AccessibilityNodeInfo n = field(s);
        if (n == null || Build.VERSION.SDK_INT < 30) return false;
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
    }

    private static boolean set(AccessibilityNodeInfo n, String text, int cursor) {
        Bundle t = new Bundle();
        t.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, t);
        Bundle sel = new Bundle();
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor);
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor);
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        return ok;
    }

    // ---- Arrow keys without key injection: move the focus like the remote would ----

    static boolean dpad(AccessibilityService s, String dir) {
        AccessibilityNodeInfo f = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if ("ok".equals(dir)) {
            for (AccessibilityNodeInfo n = f; n != null; n = n.getParent()) {
                if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            return false;
        }
        int d = "up".equals(dir) ? View.FOCUS_UP : "down".equals(dir) ? View.FOCUS_DOWN
                : "left".equals(dir) ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
        if (f == null) return focusFirst(s.getRootInActiveWindow());
        AccessibilityNodeInfo next = f.focusSearch(d);
        return next != null && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    private static boolean focusFirst(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isFocusable() && n.isVisibleToUser()) return n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        for (int i = 0; i < n.getChildCount(); i++) if (focusFirst(n.getChild(i))) return true;
        return false;
    }

    // ---- Apps ----

    /** Apps on the TV's home screen as JSON: [{p: package, n: name, b: banner or i: icon (WebP, base64)}]. */
    static String apps(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> found = new ArrayList<>();
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        Set<String> seen = new HashSet<>();
        final List<JSONObject> list = new ArrayList<>();
        for (ResolveInfo r : found) {
            String p = r.activityInfo.packageName;
            if (p.equals(c.getPackageName()) || !seen.add(p)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("p", p);
                o.put("n", String.valueOf(r.loadLabel(pm)));
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                Drawable banner = ai.loadBanner(pm);
                if (banner == null) banner = r.activityInfo.loadBanner(pm);
                if (banner != null) o.put("b", encode(banner, 192, 108, true));
                else o.put("i", encode(r.loadIcon(pm), 96, 96, false));
                list.add(o);
            } catch (Exception ignored) {
            }
        }
        final Collator col = Collator.getInstance();
        Collections.sort(list, (x, y) -> col.compare(x.optString("n"), y.optString("n")));
        return new JSONArray(list).toString();
    }

    /** WebP keeps the list small (icons keep their transparency); banners get a dark backdrop. */
    @SuppressWarnings("deprecation")
    private static String encode(Drawable d, int w, int h, boolean banner) {
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        if (banner) cv.drawColor(0xFF1F2937);
        d.setBounds(0, 0, w, h);
        d.draw(cv);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP, 80, out);
        bm.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    static boolean launch(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        c.startActivity(i);
        return true;
    }

    // ---- Media and volume ----

    static boolean media(Context c, String what) {
        int code;
        switch (what) {
            case "play": code = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "pause": code = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "playpause": code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case "next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "prev": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "stop": code = KeyEvent.KEYCODE_MEDIA_STOP; break;
            case "ff": code = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD; break;
            case "rew": code = KeyEvent.KEYCODE_MEDIA_REWIND; break;
            default: return false;
        }
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
        return true;
    }

    static void volume(Context c, int direction) {
        ((AudioManager) c.getSystemService(Context.AUDIO_SERVICE)).adjustStreamVolume(
                AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }
}
