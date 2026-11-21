package app.couchmouse.tv;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone connection: TLS on port 8770 (Tls), one UTF-8 text line per message, see
 * PROTOCOL.md. A phone must say hello with a valid key, or pair (QR or code), before any other
 * message is accepted. Runs inside CouchMouseService.
 */
final class Server extends Thread {
    static final int PORT = 8770;
    static final int VERSION = 1;
    private static final String TAG = "CouchMouse";

    /** Paired connections (socket -> key hash), so a forgotten phone is disconnected at once. */
    private static final Map<Socket, Conn> live = new HashMap<>();

    private final CouchMouseService svc;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ServerSocket server;

    Server(CouchMouseService svc) {
        super("CouchMouseServer");
        this.svc = svc;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            server = Tls.context().getServerSocketFactory().createServerSocket(PORT);
            Log.i(TAG, "listening on " + PORT);
            while (!isInterrupted()) {
                final Socket s = server.accept();
                new Conn(s).start();
            }
        } catch (Exception e) {
            if (server == null || !server.isClosed()) Log.w(TAG, "server", e);
        }
    }

    void close() {
        interrupt();
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        drop(null);
    }

    /** Disconnects the phone with this key hash, or every phone (null). Safe on any thread. */
    static void drop(String hash) {
        final List<Socket> close = new ArrayList<>();
        synchronized (live) {
            for (Map.Entry<Socket, Conn> e : live.entrySet()) {
                if (hash == null || hash.equals(e.getValue().phone)) close.add(e.getKey());
            }
        }
        if (close.isEmpty()) return;
        // Closing TLS sends a goodbye message over the network: never on the main thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (Socket s : close) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }, "CouchMouseDrop").start();
    }

    static boolean isConnected(String hash) {
        synchronized (live) {
            for (Conn c : live.values()) if (hash.equals(c.phone)) return true;
            return false;
        }
    }

    /** Sends a line to every paired phone (e.g. "kbd 1" when a text field gets focus). */
    static void broadcast(String line) {
        List<Conn> all;
        synchronized (live) {
            all = new ArrayList<>(live.values());
        }
        for (Conn c : all) c.sendQuietly(line);
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private final class Conn extends Thread {
        private final Socket s;
        private OutputStream out;
        volatile String phone;   // key hash once paired; until then only hello/pairing count
        private String name = "Phone";
        private boolean asked;   // the TV is showing a code for this connection

        Conn(Socket s) {
            super("CouchMouseConn");
            this.s = s;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                s.setTcpNoDelay(true);
                s.setSoTimeout(45000); // phones ping every 15 s
                out = s.getOutputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8), 8192);
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.length() > 65536) break;
                    if (phone != null) command(line);
                    else if (!greet(line)) break;
                }
            } catch (Exception e) {
                Log.i(TAG, "phone left: " + e.getMessage());
            } finally {
                if (asked && phone == null) Pairing.left();
                synchronized (live) {
                    live.remove(s);
                }
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }

        /** Before pairing. Returns false to hang up. */
        private boolean greet(String line) throws IOException {
            String[] a = line.split(" ", 4);
            switch (a[0]) {
                case "hello": { // hello <version> <key|-> <name>
                    if (a.length > 3) name = dec(a[3]);
                    String h = a.length > 2 && !a[2].equals("-") ? Pairing.check(svc, a[2]) : null;
                    if (h != null) paired(h);
                    else send("pair");
                    return true;
                }
                case "qr": { // qr <secret>
                    String r = Pairing.scanned(svc, a.length > 1 ? a[1] : "", name);
                    send(r);
                    if (r.startsWith("key ")) paired(Pairing.sha256(r.substring(4)));
                    return true;
                }
                case "code?": {
                    if (asked) return true;
                    String r = Pairing.request(svc, Phones.device(name));
                    asked = r.startsWith("code");
                    send(r);
                    return true;
                }
                case "code": { // code 1234
                    if (!asked || a.length < 2) return true;
                    String r = Pairing.submit(svc, a[1], Phones.device(name), name);
                    send(r);
                    if (r.startsWith("key ")) {
                        asked = false;
                        paired(Pairing.sha256(r.substring(4)));
                    }
                    return true;
                }
                case "ping":
                    send("pong");
                    return true;
                default:
                    return false;
            }
        }

        private void paired(String hash) throws IOException {
            phone = hash;
            synchronized (live) {
                live.put(s, this);
            }
            send("ok " + VERSION + " " + enc(Phones.tvName(svc)) + " " + Inputs.capabilities());
            if (Inputs.editing(svc)) send("kbd 1");
            Log.i(TAG, "phone connected: " + name);
        }

        /** After pairing: input for the TV. Cursor work runs on the main thread. */
        private void command(final String line) throws IOException {
            if (line.equals("ping")) {
                send("pong");
                return;
            }
            if (line.equals("apps")) { // icons are encoded here, off the main thread
                send("apps " + Inputs.apps(svc));
                return;
            }
            main.post(new Runnable() {
                @Override
                public void run() {
                    for (String m : line.split("\n")) {
                        if (m.isEmpty()) continue;
                        String reply = svc.handle(m);
                        if (reply != null) sendQuietly(reply);
                    }
                }
            });
        }

        void send(String line) throws IOException {
            byte[] b = (line + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (this) {
                out.write(b); // one write = one TLS record
                out.flush();
            }
        }

        /** From the main thread (or for broadcasts): written on a worker, failures ignored. */
        void sendQuietly(final String line) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        send(line);
                    } catch (Exception ignored) {
                    }
                }
            }, "CouchMouseSend").start();
        }
    }
}
