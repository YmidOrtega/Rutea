package me.ymid.rutea.app.web;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.json.JsonMapper;

/**
 * Difunde los eventos de la operación a todos los clientes conectados por WebSocket (R08: seguimiento de la
 * recolección). Los clientes no envían nada por aquí: las averías llegan por {@code POST /api/breakdowns}. Al
 * conectarse, cada cliente recibe el turno en curso ({@link ShiftMessage}) para pedir lo que le falte.
 */
@Component
public class EventsHub extends TextWebSocketHandler {

    /**
     * Un cliente que no recibe en este tiempo, o que acumula más que esto sin recibir, se desconecta: así no frena la
     * telemetría de los demás. Al reconectarse recibe el turno y se pone al día.
     */
    static final int SEND_TIME_LIMIT_MILLIS = 5_000;
    static final int BUFFER_LIMIT_BYTES = 1 << 20;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final OperationsService operations;
    private final JsonMapper json;

    public EventsHub(OperationsService operations, JsonMapper json) {
        this.operations = operations;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession concurrent = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS,
                BUFFER_LIMIT_BYTES);
        sessions.put(session.getId(), concurrent);
        if (operations.ready()) {
            send(concurrent, new TextMessage(json.writeValueAsString(operations.shiftMessage())));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    public int clients() {
        return sessions.size();
    }

    public void broadcast(Object event) {
        TextMessage message = new TextMessage(json.writeValueAsString(event));
        sessions.values().forEach(session -> send(session, message));
    }

    private void send(WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        } catch (IOException | RuntimeException e) {
            // El decorador cierra la sesión si excede los límites; el cliente reconecta y se pone al día.
            sessions.remove(session.getId());
        }
    }
}
