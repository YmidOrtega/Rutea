package me.ymid.rutea.app.application;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Lo que pasa en la operación, como lo reciben todos los clientes por WebSocket (R08). {@code type} dice qué
 * evento es; {@code seq} es su orden en la sesión del servidor, así un cliente nunca cuenta dos veces el mismo.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BreakdownEvent.class, name = "breakdown"),
    @JsonSubTypes.Type(value = OverflowEvent.class, name = "overflow"),
    @JsonSubTypes.Type(value = ClosureEvent.class, name = "closure")
})
public sealed interface OperationEvent permits BreakdownEvent, OverflowEvent, ClosureEvent {

    int seq();

    int area();

    long atSeconds();
}
