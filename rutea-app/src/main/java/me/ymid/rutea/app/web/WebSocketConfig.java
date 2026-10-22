package me.ymid.rutea.app.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final EventsHub hub;

    public WebSocketConfig(EventsHub hub) {
        this.hub = hub;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // El mapa llega servido por la misma app, por el proxy de Vite o directo desde localhost en cualquier puerto.
        registry.addHandler(hub, "/ws/events").setAllowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]");
    }
}
