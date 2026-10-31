-- Una operación en vivo por localidad y franja (F8): cada turno es de una, como '11-LMVNoche'. Los turnos anteriores
-- eran de la operación única de Suba y no se retoman.
ALTER TABLE shift ADD COLUMN operation text;
CREATE INDEX shift_operation ON shift (operation, id);
