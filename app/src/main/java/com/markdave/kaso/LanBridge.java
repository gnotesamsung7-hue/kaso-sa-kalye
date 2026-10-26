package com.markdave.kaso;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.net.wifi.WifiManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Lets the game host a Wi-Fi game on this phone, or find games hosted by other phones. */
public class LanBridge {
    static final int WS_PORT = 41777;
    static final int UDP_PORT = 41779;
    static final String TAG = "KASO|";

    private final Activity act;
    private final WebView web;
    private RelayServer server;
    private volatile boolean beaconOn = false;
    private volatile boolean listenOn = false;
    private volatile String hostName = "";
    private WifiManager.MulticastLock lock;

    LanBridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    /** Starts the game server. Returns this phone's Wi-Fi address, "" if unknown, or "ERR". */
    @JavascriptInterface
    public synchronized String startHost(String name) {
        stopHost();
        try {
            hostName = name == null ? "" : name;
            server = new RelayServer(new InetSocketAddress(WS_PORT));
            server.setReuseAddr(true);
            server.start();
            startBeacon();
            return localIp();
        } catch (Exception e) {
            return "ERR";
        }
    }

    @JavascriptInterface
    public synchronized void stopHost() {
        beaconOn = false;
        if (server != null) {
            try { server.stop(300); } catch (Exception ignored) { }
            server = null;
        }
    }

    @JavascriptInterface
    public String getIp() {
        return localIp();
    }

    @JavascriptInterface
    public synchronized void startDiscovery() {
        if (listenOn) return;
        listenOn = true;
        try {
            WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("kaso");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) { }
        Thread t = new Thread(this::listenLoop, "game-finder");
        t.setDaemon(true);
        t.start();
    }

    @JavascriptInterface
    public synchronized void stopDiscovery() {
        listenOn = false;
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) { }
    }

    /** Saves the printable PDF to Downloads and opens it. Returns "DOWNLOADS", "APP:<path>" or "ERR". */
    @JavascriptInterface
    public String savePdf() {
        String name = "Kaso-sa-Kalye-print-and-play.pdf";
        try (InputStream in = act.getAssets().open("www/kaso-print-and-play.pdf")) {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = act.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return "ERR";
                try (OutputStream out = act.getContentResolver().openOutputStream(uri)) {
                    if (out == null) return "ERR";
                    copy(in, out);
                }
                openPdf(uri);
                return "DOWNLOADS";
            } else {
                File dir = act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) return "ERR";
                File f = new File(dir, name);
                try (OutputStream out = new FileOutputStream(f)) {
                    copy(in, out);
                }
                return "APP:" + f.getAbsolutePath();
            }
        } catch (Exception e) {
            return "ERR";
        }
    }

    /** Copies text to the phone's clipboard. */
    @JavascriptInterface
    public boolean copyText(String text) {
        final String t = text == null ? "" : text;
        act.runOnUiThread(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Art prompt", t));
            } catch (Exception ignored) { }
        });
        return true;
    }

    private void openPdf(Uri uri) {
        act.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/pdf");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Exception ignored) { }
        });
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    void shutdown() {
        stopDiscovery();
        stopHost();
    }

    private void startBeacon() {
        beaconOn = true;
        Thread t = new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                while (beaconOn) {
                    byte[] d = (TAG + hostName).getBytes(StandardCharsets.UTF_8);
                    for (InetAddress bc : broadcastAddrs()) {
                        try { ds.send(new DatagramPacket(d, d.length, bc, UDP_PORT)); } catch (Exception ignored) { }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) { }
        }, "game-beacon");
        t.setDaemon(true);
        t.start();
    }

    private void listenLoop() {
        Map<String, String[]> rooms = new HashMap<>();
        try (DatagramSocket ds = new DatagramSocket(null)) {
            ds.setReuseAddress(true);
            ds.setBroadcast(true);
            ds.bind(new InetSocketAddress(UDP_PORT));
            ds.setSoTimeout(800);
            byte[] buf = new byte[512];
            long lastPush = 0;
            while (listenOn) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ds.receive(p);
                    String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (s.startsWith(TAG)) {
                        rooms.put(p.getAddress().getHostAddress(),
                                new String[]{s.substring(TAG.length()), String.valueOf(System.currentTimeMillis())});
                    }
                } catch (SocketTimeoutException ignored) { }
                long now = System.currentTimeMillis();
                if (now - lastPush > 1000) {
                    lastPush = now;
                    JSONArray arr = new JSONArray();
                    Iterator<Map.Entry<String, String[]>> it = rooms.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, String[]> e = it.next();
                        if (now - Long.parseLong(e.getValue()[1]) > 4000) { it.remove(); continue; }
                        JSONObject o = new JSONObject();
                        o.put("ip", e.getKey());
                        o.put("name", e.getValue()[0]);
                        arr.put(o);
                    }
                    final String js = "window.lanRooms&&window.lanRooms(" + arr.toString() + ")";
                    act.runOnUiThread(() -> web.evaluateJavascript(js, null));
                }
            }
        } catch (Exception ignored) { }
    }

    private static List<InetAddress> broadcastAddrs() {
        List<InetAddress> out = new ArrayList<>();
        try { out.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) { }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !out.contains(b)) out.add(b);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    static String localIp() {
        String best = "";
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap")) return ip;
                    if (best.isEmpty() && !n.startsWith("rmnet")) best = ip;
                }
            }
        } catch (Exception ignored) { }
        return best;
    }
}
