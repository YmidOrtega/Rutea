package me.ymid.rutea.app.web;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.json.JsonMapper;

/**
 * Difunde los eventos de la operación a todos los clientes conectados por WebSocket (R08: seguimiento de la
 * recolección). Los clientes no envían nada por aquí: las averías llegan por {@code POST /api/breakdowns}.
 */
@Component
public class EventsHub extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final JsonMapper json;

    public EventsHub(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public int clients() {
        return sessions.size();
    }

    public void broadcast(Object event) {
        TextMessage message = new TextMessage(json.writeValueAsString(event));
        for (WebSocketSession session : sessions) {
            try {
                // Una sesión no admite envíos simultáneos desde varios hilos.
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(message);
                    }
                }
            } catch (IOException e) {
                sessions.remove(session);
            }
        }
    }
}
