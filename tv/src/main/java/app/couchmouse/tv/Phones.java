package app.couchmouse.tv;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/** Display names: this TV's, and a short device word for the pairing card. */
final class Phones {
    private Phones() {
    }

    /** The name set in the TV's settings ("Living Room TV"), else the model. */
    static String tvName(Context c) {
        String n = null;
        try {
            n = Settings.Global.getString(c.getContentResolver(), "device_name");
        } catch (Exception ignored) {
        }
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        return n == null || n.trim().isEmpty() ? "Android TV" : n.trim();
    }

    /** "Type this code on your …" — the phone's own name when it's short, else "phone". */
    static String device(String phoneName) {
        if (phoneName == null) return "phone";
        String n = phoneName.trim();
        return n.isEmpty() || n.length() > 28 ? "phone" : n;
    }
}
