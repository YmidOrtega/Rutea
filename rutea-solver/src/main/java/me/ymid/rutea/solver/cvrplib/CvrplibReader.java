package me.ymid.rutea.solver.cvrplib;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import me.ymid.rutea.solver.DistanceMatrix;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;

/**
 * El nodo k del .vrp es el k - 1 de Instance, para dejar el depósito en 0; los clientes del .sol ya
 * vienen numerados desde 1.
 */
public final class CvrplibReader {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private CvrplibReader() {
    }

    public static Instance readInstance(Path file) throws IOException {
        return new InstanceParser(file).parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    public static BestKnownSolution readSolution(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<int[]> routes = new ArrayList<>();
        Long cost = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            int lineNumber = i + 1;
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("Route")) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    throw new CvrplibFormatException(file, lineNumber, "falta ':' en la ruta");
                }
                String body = line.substring(colon + 1).strip();
                String[] tokens = body.isEmpty() ? new String[0] : WHITESPACE.split(body);
                int[] route = new int[tokens.length];
                for (int t = 0; t < tokens.length; t++) {
                    route[t] = parseInt(file, lineNumber, tokens[t]);
                }
                routes.add(route);
            } else if (line.startsWith("Cost")) {
                String[] tokens = WHITESPACE.split(line);
                if (tokens.length != 2) {
                    throw new CvrplibFormatException(file, lineNumber, "se esperaba 'Cost <entero>'");
                }
                try {
                    cost = Long.parseLong(tokens[1]);
                } catch (NumberFormatException e) {
                    throw new CvrplibFormatException(file, lineNumber, "costo no entero: " + tokens[1]);
                }
            } else {
                throw new CvrplibFormatException(file, lineNumber, "línea no reconocida: " + line);
            }
        }
        if (cost == null) {
            throw new CvrplibFormatException(file, "falta la línea 'Cost'");
        }
        if (routes.isEmpty()) {
            throw new CvrplibFormatException(file, "no hay rutas");
        }
        return new BestKnownSolution(new Solution(routes.toArray(int[][]::new)), cost);
    }

    private static int parseInt(Path file, int line, String token) throws CvrplibFormatException {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new CvrplibFormatException(file, line, "se esperaba un entero, llegó '" + token + "'");
        }
    }

    private static double parseDouble(Path file, int line, String token) throws CvrplibFormatException {
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw new CvrplibFormatException(file, line, "se esperaba un número, llegó '" + token + "'");
        }
    }

    private enum Section { HEADER, COORDINATES, DEMANDS, DEPOTS, DONE }

    private static final class InstanceParser {

        private final Path file;
        private String name;
        private int dimension = -1;
        private int capacity = -1;
        private String edgeWeightType;
        private double[] x;
        private double[] y;
        private int[] demands;
        private boolean[] hasCoordinates;
        private boolean[] hasDemand;
        private int depotCount;

        InstanceParser(Path file) {
            this.file = file;
            this.name = file.getFileName().toString().replaceFirst("\\.vrp$", "");
        }

        Instance parse(List<String> lines) throws CvrplibFormatException {
            Section section = Section.HEADER;
            for (int i = 0; i < lines.size() && section != Section.DONE; i++) {
                String line = lines.get(i).strip();
                int lineNumber = i + 1;
                if (line.isEmpty()) {
                    continue;
                }
                switch (line) {
                    case "NODE_COORD_SECTION" -> section = startSection(lineNumber, Section.COORDINATES);
                    case "DEMAND_SECTION" -> section = startSection(lineNumber, Section.DEMANDS);
                    case "DEPOT_SECTION" -> section = startSection(lineNumber, Section.DEPOTS);
                    case "EOF" -> section = Section.DONE;
                    default -> section = readLine(section, lineNumber, line);
                }
            }
            return build();
        }

        private Section startSection(int line, Section section) throws CvrplibFormatException {
            if (dimension < 0) {
                throw new CvrplibFormatException(file, line, "la sección empieza antes de DIMENSION");
            }
            if (x == null) {
                x = new double[dimension];
                y = new double[dimension];
                demands = new int[dimension];
                hasCoordinates = new boolean[dimension];
                hasDemand = new boolean[dimension];
            }
            return section;
        }

        private Section readLine(Section section, int line, String text) throws CvrplibFormatException {
            switch (section) {
                case HEADER -> readHeader(line, text);
                case COORDINATES -> {
                    String[] tokens = expectTokens(line, text, 3);
                    int node = nodeIndex(line, tokens[0]);
                    if (hasCoordinates[node]) {
                        throw new CvrplibFormatException(file, line, "coordenadas repetidas para el nodo " + tokens[0]);
                    }
                    x[node] = parseDouble(file, line, tokens[1]);
                    y[node] = parseDouble(file, line, tokens[2]);
                    hasCoordinates[node] = true;
                }
                case DEMANDS -> {
                    String[] tokens = expectTokens(line, text, 2);
                    int node = nodeIndex(line, tokens[0]);
                    if (hasDemand[node]) {
                        throw new CvrplibFormatException(file, line, "demanda repetida para el nodo " + tokens[0]);
                    }
                    demands[node] = parseInt(file, line, tokens[1]);
                    hasDemand[node] = true;
                }
                case DEPOTS -> {
                    int depot = parseInt(file, line, text);
                    if (depot == -1) {
                        return Section.DONE;
                    }
                    if (depot != 1) {
                        throw new CvrplibFormatException(file, line,
                                "solo se soporta el depósito en el nodo 1, llegó " + depot);
                    }
                    depotCount++;
                }
                case DONE -> throw new IllegalStateException("no se leen líneas después de EOF");
            }
            return section;
        }

        private void readHeader(int line, String text) throws CvrplibFormatException {
            int colon = text.indexOf(':');
            if (colon < 0) {
                throw new CvrplibFormatException(file, line, "se esperaba 'CLAVE : valor', llegó: " + text);
            }
            String key = text.substring(0, colon).strip();
            String value = text.substring(colon + 1).strip();
            switch (key) {
                case "NAME" -> name = value;
                case "COMMENT" -> { }
                case "TYPE" -> requireValue(line, key, value, "CVRP");
                case "EDGE_WEIGHT_TYPE" -> {
                    requireValue(line, key, value, "EUC_2D");
                    edgeWeightType = value;
                }
                case "DIMENSION" -> {
                    dimension = parseInt(file, line, value);
                    if (dimension < 2) {
                        throw new CvrplibFormatException(file, line, "DIMENSION debe ser al menos 2, es " + dimension);
                    }
                }
                case "CAPACITY" -> capacity = parseInt(file, line, value);
                default -> throw new CvrplibFormatException(file, line, "clave no soportada: " + key);
            }
        }

        private void requireValue(int line, String key, String value, String expected)
                throws CvrplibFormatException {
            if (!value.equals(expected)) {
                throw new CvrplibFormatException(file, line,
                        key + " '" + value + "' no soportado; solo " + expected);
            }
        }

        private String[] expectTokens(int line, String text, int count) throws CvrplibFormatException {
            String[] tokens = WHITESPACE.split(text);
            if (tokens.length != count) {
                throw new CvrplibFormatException(file, line,
                        "se esperaban " + count + " valores, llegaron " + tokens.length + ": " + text);
            }
            return tokens;
        }

        private int nodeIndex(int line, String token) throws CvrplibFormatException {
            int node = parseInt(file, line, token);
            if (node < 1 || node > dimension) {
                throw new CvrplibFormatException(file, line,
                        "nodo " + node + " fuera de 1.." + dimension);
            }
            return node - 1;
        }

        private Instance build() throws CvrplibFormatException {
            if (dimension < 0) {
                throw new CvrplibFormatException(file, "falta DIMENSION");
            }
            if (capacity < 0) {
                throw new CvrplibFormatException(file, "falta CAPACITY");
            }
            if (edgeWeightType == null) {
                throw new CvrplibFormatException(file, "falta EDGE_WEIGHT_TYPE");
            }
            if (x == null) {
                throw new CvrplibFormatException(file, "faltan NODE_COORD_SECTION y DEMAND_SECTION");
            }
            requireAll(hasCoordinates, "coordenadas");
            requireAll(hasDemand, "demanda");
            if (depotCount != 1) {
                throw new CvrplibFormatException(file,
                        "se esperaba exactamente un depósito en DEPOT_SECTION, hay " + depotCount);
            }
            try {
                return new Instance(name, capacity, demands, DistanceMatrix.euclidean(x, y));
            } catch (IllegalArgumentException e) {
                throw new CvrplibFormatException(file, e.getMessage());
            }
        }

        private void requireAll(boolean[] present, String what) throws CvrplibFormatException {
            for (int node = 0; node < present.length; node++) {
                if (!present[node]) {
                    throw new CvrplibFormatException(file, "falta " + what + " del nodo " + (node + 1));
                }
            }
        }
    }
}
