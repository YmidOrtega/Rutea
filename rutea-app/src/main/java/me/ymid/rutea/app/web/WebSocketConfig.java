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
        // El frontend llega por el proxy de Vite (mismo origen) o directo desde localhost:5173.
        registry.addHandler(hub, "/ws/events").setAllowedOrigins("http://localhost:5173", "http://localhost:8080");
    }
}
