package com.markdave.kaso;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny message relay on the host phone. The host phone's game page connects
 * first and runs all the rules. Messages go to one player only, so nobody's
 * phone ever receives another player's secret cards.
 */
public class RelayServer extends WebSocketServer {
    private final Map<WebSocket, String> ids = new HashMap<>();
    private final Map<String, WebSocket> byId = new HashMap<>();
    private String hostId = null;
    private int counter = 0;

    public RelayServer(InetSocketAddress address) {
        super(address);
    }

    @Override
    public synchronized void onOpen(WebSocket conn, ClientHandshake handshake) {
        String id = String.format(Locale.US, "p%05d", counter++);
        if (hostId == null) hostId = id;
        ids.put(conn, id);
        byId.put(id, conn);
        try {
            JSONObject hi = new JSONObject();
            hi.put("t", "hi");
            hi.put("id", id);
            hi.put("host", hostId);
            conn.send(hi.toString());
        } catch (Exception ignored) { }
    }

    @Override
    public synchronized void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String id = ids.remove(conn);
        if (id == null) return;
        byId.remove(id);
        try {
            JSONObject m = new JSONObject();
            m.put("t", "bye");
            m.put("id", id);
            String s = m.toString();
            for (WebSocket c : ids.keySet()) if (c.isOpen()) c.send(s);
        } catch (Exception ignored) { }
    }

    @Override
    public synchronized void onMessage(WebSocket conn, String message) {
        String from = ids.get(conn);
        if (from == null || message.length() > 32000) return;
        try {
            JSONObject m = new JSONObject(message);
            if (!"m".equals(m.optString("t"))) return;
            String to = m.optString("to");
            JSONObject out = new JSONObject();
            out.put("t", "m");
            out.put("from", from);
            out.put("d", m.opt("d"));
            String s = out.toString();
            if ("*".equals(to)) {
                for (WebSocket c : ids.keySet()) if (c != conn && c.isOpen()) c.send(s);
            } else {
                WebSocket c = byId.get(to);
                if (c != null && c != conn && c.isOpen()) c.send(s);
            }
        } catch (Exception ignored) { }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) { }

    @Override
    public void onStart() {
        setConnectionLostTimeout(15);
    }
}
