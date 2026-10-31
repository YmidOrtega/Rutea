package me.ymid.rutea.app.application;

import java.util.List;

/** Eventos de un turno: si {@code shift} no es el que conoce el cliente, empezó otro y debe olvidar lo que tenía. */
public record EventsPage(long shift, List<OperationEvent> events) {

    public EventsPage {
        events = List.copyOf(events);
    }
}
