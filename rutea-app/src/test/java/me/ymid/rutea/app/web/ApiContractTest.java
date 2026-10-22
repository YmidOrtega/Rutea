package me.ymid.rutea.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import me.ymid.rutea.app.operations.LiveOperation;
import me.ymid.rutea.app.planning.Compliance;
import me.ymid.rutea.app.planning.DemoPlan;
import me.ymid.rutea.domain.WasteStream;

/** docs/api.md es el contrato: si un campo o un valor cambia en el código y no allí, este test falla. */
class ApiContractTest {

    private static final Path CONTRACT = Path.of("../docs/api.md");
    private static final Pattern NAME = Pattern.compile("`([^`]+)`");

    static Stream<Class<?>> contract() {
        return Stream.of(OperationsController.BreakdownRequest.class, OperationsController.OverflowRequest.class,
                OperationsController.ClosureRequest.class, OperationsController.ClockRequest.class, EventsPage.class,
                ShiftMessage.class, BreakdownEvent.class, OverflowEvent.class, LiveOperation.Pickup.class,
                ClosureEvent.class, ClosureEvent.Change.class, TelemetryMessage.class, TelemetryMessage.Clock.class,
                TelemetryMessage.Report.class, TelemetryMessage.VehicleState.class, SensorsMessage.class,
                DemoPlan.class, DemoPlan.Meta.class, DemoPlan.Site.class, DemoPlan.Container.class,
                DemoPlan.Route.class, DemoPlan.Sector.class, DemoPlan.Pass.class, WasteStream.class, Compliance.Check.class, Compliance.Status.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contract")
    void documentedFieldsMatchTheCode(Class<?> type) throws IOException {
        assertThat(documented(name(type))).as("docs/api.md, sección `%s`", name(type)).isEqualTo(actual(type));
    }

    private static List<String> actual(Class<?> type) {
        if (type.isEnum()) {
            return Arrays.stream(type.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).toList();
        }
        List<String> fields = new ArrayList<>();
        if (OperationEvent.class.isAssignableFrom(type)) {
            fields.add("type");
        }
        Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).forEach(fields::add);
        return fields;
    }

    /** La primera línea "Campos:" o "Valores:" después del título con el nombre del tipo. */
    private static List<String> documented(String name) throws IOException {
        List<String> lines = Files.readAllLines(CONTRACT);
        int heading = lines.indexOf("### `" + name + "`");
        assertThat(heading).as("docs/api.md no tiene la sección ### `%s`", name).isNotNegative();
        for (String line : lines.subList(heading + 1, lines.size())) {
            if (line.startsWith("#")) {
                break;
            }
            if (line.startsWith("Campos:") || line.startsWith("Valores:")) {
                List<String> names = new ArrayList<>();
                Matcher matcher = NAME.matcher(line);
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
                return names;
            }
        }
        throw new AssertionError("la sección `" + name + "` de docs/api.md no lista sus campos");
    }

    /** Las peticiones van por su nombre corto: quien usa la API no ve el controlador. */
    private static String name(Class<?> type) {
        Class<?> enclosing = type.getEnclosingClass();
        return enclosing == null || enclosing == OperationsController.class ? type.getSimpleName()
                : enclosing.getSimpleName() + "." + type.getSimpleName();
    }
}
