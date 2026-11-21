package app.couchmouse.tv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The TV's setup screen. Before the service is on: Google Play's prominent disclosure for the
 * accessibility permission and a button to the settings page. After: a QR code to scan with the
 * phone app (address, certificate fingerprint, one-time secret), and the paired phones (OK
 * forgets one). Refreshes itself, so it updates when the service turns on or a phone pairs.
 */
public class MainActivity extends Activity {
    private static final int BG = 0xFF0B1220, PANEL = 0x14FFFFFF, ACCENT = 0xFF7C6CFF, DIM = 0xFF94A3B8;

    private final Handler handler = new Handler();
    private LinearLayout left, right;
    private String shown;
    private String note;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!state().equals(shown)) fill();
            handler.postDelayed(this, 2000);
        }
    };

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{BG, 0xFF17143A, 0xFF0F172A}));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(64), dp(40), dp(64), dp(32));
        ScrollView scroll = new ScrollView(this);
        left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(left);
        row.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1f));
        right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -1);
        rp.leftMargin = dp(48);
        row.addView(right, rp);
        setContentView(row);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Pairing.cardBottomLeft = true; // keep the code card off the QR code
        fill();
        handler.postDelayed(poll, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Pairing.cardBottomLeft = false;
        handler.removeCallbacks(poll);
    }

    private boolean on() {
        return CouchMouseService.instance != null;
    }

    private String state() {
        return on() + "|" + Pairing.sp(this).getString(Pairing.PHONES, "[]") + "|" + (on() ? Pairing.qrSecret() : "")
                + "|" + address();
    }

    private void fill() {
        View f = getCurrentFocus();
        Object focusTag = f != null ? f.getTag() : null;
        shown = state();
        left.removeAllViews();
        right.removeAllViews();

        TextView title = text(left, "CouchMouse TV", 34, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text(left, "A real mouse cursor for this TV, from your phone.", 16, DIM).setPadding(0, dp(2), 0, dp(18));

        if (!on()) {
            section("Allow CouchMouse to control this TV");
            text(left, getString(R.string.service_description), 16, 0xFFE2E8F0).setLineSpacing(0, 1.25f);
            text(left, "In the list that opens, select CouchMouse TV and turn it on.", 15, DIM)
                    .setPadding(0, dp(10), 0, dp(4));
            if (Build.VERSION.SDK_INT >= 33) {
                text(left, "If the TV says “Restricted setting”: open Settings → Apps → CouchMouse TV, "
                        + "choose “Allow restricted settings”, then try again.", 14, DIM);
            }
            button("agree", "Agree and turn on", true, new Runnable() {
                @Override
                public void run() {
                    openAccessibilitySettings();
                }
            });
            button("later", "Not now", false, new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        } else {
            section("Connect your phone");
            String ip = address();
            text(left, "1.  Open the CouchMouse app on your phone.\n2.  Scan the code on the right, or choose “"
                    + Phones.tvName(this) + "” in the app.", 17, 0xFFE2E8F0).setLineSpacing(0, 1.35f);
            text(left, ip == null ? "This TV isn't connected to a network." :
                    "Phone and TV must be on the same Wi-Fi.  TV address: " + ip, 14, DIM).setPadding(0, dp(8), 0, 0);
            if (ip != null) qr(ip);

            List<Pairing.Phone> phones = Pairing.phones(this);
            section("Paired phones");
            if (phones.isEmpty()) text(left, "None yet.", 15, DIM);
            long now = System.currentTimeMillis();
            for (final Pairing.Phone p : phones) {
                String used = Server.isConnected(p.hash) ? "connected now"
                        : now - p.used < 60000 ? "used just now"
                        : "used " + DateUtils.getRelativeTimeSpanString(p.used, now, DateUtils.MINUTE_IN_MILLIS);
                row(p.hash, p.name, used + "     OK to forget", new Runnable() {
                    @Override
                    public void run() {
                        Pairing.forget(MainActivity.this, p.hash);
                        note = p.name + " forgotten. It needs a new code to connect again.";
                        fill();
                    }
                });
            }
            if (note != null) text(left, note, 14, DIM).setPadding(0, dp(8), 0, 0);
        }

        View target = focusTag != null ? left.findViewWithTag(focusTag) : null;
        for (int i = 0; target == null && i < left.getChildCount(); i++) {
            if (left.getChildAt(i).isFocusable()) target = left.getChildAt(i);
        }
        if (target != null) target.requestFocus();
    }

    private void qr(String ip) {
        try {
            String fp = Tls.fingerprint();
            String url = "couchmouse://pair?h=" + ip + "&p=" + Server.PORT + "&f=" + fp + "&s=" + Pairing.qrSecret()
                    + "&n=" + Server.enc(Phones.tvName(this));
            if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                android.util.Log.d("CouchMouse", "qr " + url); // test builds only
            }
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(Qr.make(url, dp(300)));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(16));
            iv.setBackground(bg);
            iv.setPadding(dp(10), dp(10), dp(10), dp(10));
            right.addView(iv, new LinearLayout.LayoutParams(dp(320), dp(320)));
            TextView t = text(right, "Scan with CouchMouse", 15, DIM);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(10), 0, 0);
        } catch (Exception e) {
            text(right, "QR code unavailable", 14, DIM);
        }
    }

    private void openAccessibilitySettings() {
        ComponentName me = new ComponentName(this, CouchMouseService.class);
        Intent[] tries = {
                Build.VERSION.SDK_INT >= 30 ? new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                        .putExtra(Intent.EXTRA_COMPONENT_NAME, me.flattenToString()) : null,
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS)};
        for (Intent i : tries) {
            if (i == null) continue;
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
            }
        }
    }

    /** The TV's address on the home network, or null. */
    static String address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---- small view helpers ----

    private TextView text(LinearLayout parent, String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        parent.addView(t);
        return t;
    }

    private void section(String s) {
        TextView t = text(left, s.toUpperCase(Locale.ENGLISH), 13, 0xFF8B93B8);
        t.setLetterSpacing(0.12f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(8));
    }

    private void button(String tag, String label, boolean primary, final Runnable run) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTag(tag);
        t.setTextSize(17);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(24), dp(12), dp(24), dp(12));
        t.setFocusable(true);
        t.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        final int rest = primary ? 0xFF4C3FD9 : PANEL;
        bg.setColor(rest);
        t.setBackground(bg);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                bg.setColor(focused ? ACCENT : rest);
            }
        });
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(12);
        left.addView(t, lp);
    }

    private void row(String tag, String label, String value, final Runnable run) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), dp(10), dp(18), dp(10));
        r.setTag(tag);
        r.setFocusable(true);
        r.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(PANEL);
        r.setBackground(bg);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFFE2E8F0);
        l.setTextSize(16);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(DIM);
        v.setTextSize(14);
        r.addView(v);
        r.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                bg.setColor(focused ? ACCENT : PANEL);
                v.setTextColor(focused ? 0xFFEDE9FE : DIM);
            }
        });
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                run.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        left.addView(r, lp);
    }
}
