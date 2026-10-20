package me.ymid.rutea.geo.osm;

import java.io.IOException;

public final class PbfFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    PbfFormatException(String message) {
        super(message);
    }
}
