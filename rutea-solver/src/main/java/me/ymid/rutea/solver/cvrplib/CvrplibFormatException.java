package me.ymid.rutea.solver.cvrplib;

import java.io.IOException;
import java.nio.file.Path;

public final class CvrplibFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    CvrplibFormatException(Path file, int line, String message) {
        super(file.getFileName() + ":" + line + ": " + message);
    }

    CvrplibFormatException(Path file, String message) {
        super(file.getFileName() + ": " + message);
    }
}
