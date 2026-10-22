CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE shift (
    id         bigserial   PRIMARY KEY,
    started_at timestamptz NOT NULL DEFAULT now()
);

-- Las órdenes del turno en el orden en que llegaron: repetirlas reconstruye la operación, que es determinista.
CREATE TABLE command (
    shift_id   bigint  NOT NULL REFERENCES shift (id) ON DELETE CASCADE,
    seq        integer NOT NULL,
    at_seconds bigint  NOT NULL,
    kind       text    NOT NULL CHECK (kind IN ('breakdown', 'overflow', 'closure')),
    args       jsonb   NOT NULL,
    PRIMARY KEY (shift_id, seq)
);

-- Cada punto que transmitió un vehículo (R08). El estado va solo en el último punto de cada reporte; los anteriores
-- son la calle recorrida entre dos reportes.
CREATE TABLE vehicle_position (
    shift_id   bigint                NOT NULL REFERENCES shift (id) ON DELETE CASCADE,
    vehicle    text                  NOT NULL,
    at_seconds double precision      NOT NULL,
    state      text,
    position   geometry(Point, 4326) NOT NULL
);
CREATE INDEX vehicle_position_track ON vehicle_position (shift_id, vehicle, at_seconds);
CREATE INDEX vehicle_position_where ON vehicle_position USING gist (position);
