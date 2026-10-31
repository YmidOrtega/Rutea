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
 * Difunde los eventos de cada operación en vivo a los clientes que la miran por WebSocket, en
 * {@code /ws/live/{localidad}/{franja}} (R08: seguimiento de la recolección). Los clientes no envían nada por aquí:
 * las averías llegan por {@code POST}. Al conectarse, cada cliente recibe el turno en curso ({@link ShiftMessage})
 * para pedir lo que le falte.
 */
@Component
public class EventsHub extends TextWebSocketHandler {

    /**
     * Un cliente que no recibe en este tiempo, o que acumula más que esto sin recibir, se desconecta: así no frena la
     * telemetría de los demás. Al reconectarse recibe el turno y se pone al día.
     */
    static final int SEND_TIME_LIMIT_MILLIS = 5_000;
    static final int BUFFER_LIMIT_BYTES = 1 << 20;

    private record Client(LiveKey key, WebSocketSession session) {
    }

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final OperationsService operations;
    private final JsonMapper json;

    public EventsHub(OperationsService operations, JsonMapper json) {
        this.operations = operations;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        LiveKey key;
        try {
            key = key(session);
        } catch (IllegalArgumentException e) {
            session.close(CloseStatus.BAD_DATA.withReason(e.getMessage()));
            return;
        }
        Client client = new Client(key, new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS,
                BUFFER_LIMIT_BYTES));
        clients.put(session.getId(), client);
        operations.ready(key).ifPresent(live ->
                send(session.getId(), client.session(), new TextMessage(json.writeValueAsString(live.shiftMessage()))));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    public int clients() {
        return clients.size();
    }

    public void broadcast(LiveKey key, Object event) {
        TextMessage message = new TextMessage(json.writeValueAsString(event));
        clients.forEach((id, client) -> {
            if (client.key().equals(key)) {
                send(id, client.session(), message);
            }
        });
    }

    /** {@code /ws/live/11/LMVNoche}: las dos últimas partes de la ruta. */
    private static LiveKey key(WebSocketSession session) {
        String[] parts = session.getUri() == null ? new String[0] : session.getUri().getPath().split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("la ruta es /ws/live/{localidad}/{franja}");
        }
        try {
            return LiveKey.of(Integer.parseInt(parts[parts.length - 2]), parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("la localidad va por su número: " + parts[parts.length - 2]);
        }
    }

    private void send(String id, WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        } catch (IOException | RuntimeException e) {
            // El decorador cierra la sesión si excede los límites; el cliente reconecta y se pone al día.
            clients.remove(id);
        }
    }
}
