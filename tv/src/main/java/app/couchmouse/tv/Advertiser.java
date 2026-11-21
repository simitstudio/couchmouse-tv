package app.couchmouse.tv;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

/**
 * Announces the TV on the home network as "_couchmouse._tcp" (Bonjour / DNS-SD), so the phone
 * app lists it without typing an address. TXT record: v = protocol version, fp = the first 16
 * hex characters of the certificate fingerprint (lets the phone match a TV it already knows).
 */
final class Advertiser {
    static final String TYPE = "_couchmouse._tcp";
    private static final String TAG = "CouchMouse";

    private final Context ctx;
    private NsdManager nsd;
    private NsdManager.RegistrationListener listener;
    private WifiManager.MulticastLock lock;

    Advertiser(Context c) {
        ctx = c;
    }

    void start(int port) {
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("couchmouse");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(Phones.tvName(ctx));
            info.setServiceType(TYPE);
            info.setPort(port);
            info.setAttribute("v", String.valueOf(Server.VERSION));
            info.setAttribute("fp", Tls.fingerprint().substring(0, 16));
            listener = new NsdManager.RegistrationListener() {
                @Override
                public void onServiceRegistered(NsdServiceInfo i) {
                    Log.i(TAG, "announced as " + i.getServiceName());
                }

                @Override
                public void onRegistrationFailed(NsdServiceInfo i, int err) {
                    Log.w(TAG, "announce failed " + err);
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo i) {
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo i, int err) {
                }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Exception e) {
            Log.w(TAG, "announce", e);
        }
    }

    void stop() {
        try {
            if (nsd != null && listener != null) nsd.unregisterService(listener);
        } catch (Exception ignored) {
        }
        if (lock != null && lock.isHeld()) lock.release();
    }
}
