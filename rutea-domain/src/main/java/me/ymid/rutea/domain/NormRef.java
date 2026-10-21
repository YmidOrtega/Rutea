package me.ymid.rutea.domain;

import java.net.URI;
import java.util.Objects;

/** Cada NormRef tiene su fila en docs/normativa.md con el mismo id. */
public record NormRef(String id, String norm, String article, URI source, Verification verification) {

    public NormRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(norm, "norm");
        Objects.requireNonNull(article, "article");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(verification, "verification");
    }

    public String citation() {
        String citation = id + " · " + norm + ", " + article;
        return verification == Verification.VERIFICADA ? citation : citation + " (" + verification + ")";
    }
}
