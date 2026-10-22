package me.ymid.rutea.app.web;

/**
 * Llega al conectarse por WebSocket y cada vez que empieza un turno nuevo. Con {@code shift} y {@code seq} el cliente
 * sabe si le faltan eventos (los pide con {@code GET /api/events?after=}) o si debe olvidar los que tiene.
 *
 * @param seq el último evento del turno; 0 si todavía no hay ninguno
 */
public record ShiftMessage(String type, long shift, int seq) {

    public ShiftMessage(long shift, int seq) {
        this("shift", shift, seq);
    }
}
