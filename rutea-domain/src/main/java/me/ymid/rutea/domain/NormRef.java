package me.ymid.rutea.domain;

import java.net.URI;
import java.util.Objects;

/**
 * Referencia normativa de una regla modelada en Rutea. Cada {@code NormRef} tiene su fila en
 * {@code docs/normativa.md} con el mismo {@code id}.
 *
 * @param id           identificador de la regla en {@code docs/normativa.md} (p. ej. {@code R02})
 * @param norm         norma que la establece (p. ej. "Decreto 1077 de 2015")
 * @param article      artículo, parágrafo o numeral exacto
 * @param source       texto compilado donde se verificó, o la fuente secundaria si está pendiente
 * @param verification si se contrastó con el texto oficial
 */
public record NormRef(String id, String norm, String article, URI source, Verification verification) {

    public NormRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(norm, "norm");
        Objects.requireNonNull(article, "article");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(verification, "verification");
    }

    /** Cita corta para logs, UI y mensajes: "R03 · Decreto 1077 de 2015, art. 2.3.2.2.2.3.32, parágrafo". */
    public String citation() {
        String citation = id + " · " + norm + ", " + article;
        return verification == Verification.VERIFICADA ? citation : citation + " (" + verification + ")";
    }
}
