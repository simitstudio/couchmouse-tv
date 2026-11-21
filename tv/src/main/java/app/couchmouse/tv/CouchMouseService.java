package app.couchmouse.tv;

import android.accessibilityservice.AccessibilityService;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * The TV side of CouchMouse. While the user has it turned on (Settings → Accessibility) it:
 *  - accepts paired phones on port 8770 (Server, TLS) and announces the TV on the network
 *    (Advertiser);
 *  - draws the mouse cursor and turns taps/scrolls into touch gestures (Pointer);
 *  - types phone keyboard text into the focused text box, launches apps, sends media keys,
 *    and tells phones when a text box gets focus ("kbd 1"), so their keyboard can open.
 * It acts only on commands from paired phones and never stores or sends screen content.
 */
public class CouchMouseService extends AccessibilityService {
    private static final String TAG = "CouchMouse";
    static volatile CouchMouseService instance;

    private Pointer pointer;
    private Server server;
    private Advertiser advertiser;
    private boolean editing;

    @Override
    protected void onServiceConnected() {
        instance = this;
        pointer = new Pointer(this);
        server = new Server(this);
        server.start();
        advertiser = new Advertiser(this);
        new Thread(new Runnable() { // the first run creates the TLS key: not on the main thread
            @Override
            public void run() {
                try {
                    Tls.context();
                    advertiser.start(Server.PORT);
                } catch (Exception e) {
                    Log.w(TAG, "tls", e);
                }
            }
        }, "CouchMouseStart").start();
        Log.i(TAG, "service on");
    }

    /** One command from a paired phone (main thread). Returns a reply line, or null. */
    String handle(String m) {
        int sp = m.indexOf(' ');
        String cmd = sp < 0 ? m : m.substring(0, sp);
        String arg = sp < 0 ? "" : m.substring(sp + 1);
        switch (cmd) {
            case "m": case "t": case "l": case "s": case "sh":
            case "b": case "h": case "r":
                pointer.handle(m);
                return null;
            case "v+":
                Inputs.volume(this, AudioManager.ADJUST_RAISE);
                return null;
            case "v-":
                Inputs.volume(this, AudioManager.ADJUST_LOWER);
                return null;
            case "vm":
                Inputs.volume(this, AudioManager.ADJUST_TOGGLE_MUTE);
                return null;
            case "text":
                return Inputs.type(this, Server.dec(arg)) ? null : "err text";
            case "bksp":
                Inputs.backspace(this);
                return null;
            case "enter":
                return Inputs.enter(this) ? null : "err enter";
            case "key":
                Inputs.dpad(this, arg);
                return null;
            case "launch":
                return Inputs.launch(this, arg) ? null : "err launch";
            case "media":
                Inputs.media(this, arg);
                return null;
            case "power":
                if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                return null;
            default:
                return null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        boolean now;
        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            AccessibilityNodeInfo n = e.getSource();
            now = n != null && n.isEditable();
        } else {
            now = Inputs.editing(this);
        }
        if (now != editing) {
            editing = now;
            Server.broadcast(now ? "kbd 1" : "kbd 0");
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (server != null) server.close();
        if (advertiser != null) advertiser.stop();
        Pairing.destroy();
        if (pointer != null) pointer.destroy();
        super.onDestroy();
    }
}
