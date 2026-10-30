package me.ymid.rutea.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import me.ymid.rutea.app.operations.LiveOperation;
import tools.jackson.databind.json.JsonMapper;

/** Sin los datos de OSM (como en la CI), la app arranca igual y dice qué falta y cómo generarlo. */
// Con ".." a propósito: así vienen las rutas por defecto y Spring no debe rechazarlas al arrancar.
@SpringBootTest(properties = {"rutea.graph=../no-existe/suba.rgraph", "rutea.sites=../no-existe/sitios.csv",
        "rutea.bogota-graph=../no-existe/bogota.rgraph", "rutea.warm-up=false"})
@AutoConfigureMockMvc
class OperationsControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Test
    void planWithoutOsmDataSaysWhatIsMissingAndHowToBuildIt() throws Exception {
        mvc.perform(get("/api/plan"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", Matchers.containsString("suba.rgraph")))
                .andExpect(jsonPath("$.detail", Matchers.containsString("mvn -pl rutea-geo compile exec:java")));
    }

    @Test
    void eventHistoryStartsEmptyEvenWithoutData() throws Exception {
        mvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shift").isNumber())
                .andExpect(jsonPath("$.events.length()").value(0));
    }

    @Test
    void breakdownWithoutOsmDataIsAlsoUnavailable() throws Exception {
        mvc.perform(post("/api/breakdowns").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"truck\":\"C-01\",\"atSeconds\":600}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void overflowWithoutOsmDataIsAlsoUnavailable() throws Exception {
        mvc.perform(post("/api/overflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"container\":\"N0001\",\"atSeconds\":600}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void eventsWithoutAnHourHappenAtTheSimulationClockAndAlsoNeedTheData() throws Exception {
        mvc.perform(post("/api/breakdowns").contentType(MediaType.APPLICATION_JSON).content("{\"truck\":\"C-01\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void telemetryAndClockWithoutOsmDataAreUnavailable() throws Exception {
        mvc.perform(get("/api/telemetry")).andExpect(status().isServiceUnavailable());
        mvc.perform(put("/api/clock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"running\":true,\"speed\":60}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void everyErrorIsAProblemDetailThatSaysWhatWasExpected() throws Exception {
        mvc.perform(post("/api/breakdowns").contentType(MediaType.APPLICATION_JSON).content("{truck"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("el cuerpo no es JSON válido"));
        mvc.perform(post("/api/closures").contentType(MediaType.APPLICATION_JSON).content("{\"lat\":4.7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("falta el campo lon o no tiene el tipo esperado"));
        mvc.perform(post("/api/overflows").contentType(MediaType.TEXT_PLAIN).content("N0001"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.detail", Matchers.startsWith("el cuerpo va en JSON")));
        mvc.perform(delete("/api/plan"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.detail").value("/api/plan no admite DELETE; admite GET"));
        mvc.perform(get("/api/nada"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("no hay nada en /api/nada"));
    }

    @Test
    void theLocalitiesToChooseFromNeedNoOsmData() throws Exception {
        mvc.perform(get("/api/localities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$[10].name").value("Suba"))
                .andExpect(jsonPath("$[10].area").value(5))
                .andExpect(jsonPath("$[10].operator").value("Torca"))
                .andExpect(jsonPath("$[19].rural").value(true))
                .andExpect(jsonPath("$[10].outline[0][0].length()").value(2));
    }

    @Test
    void theSelectionTravelsInTheRequestAndIsCheckedBeforeTheData() throws Exception {
        mvc.perform(get("/api/areas"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("falta el parámetro localities"));
        mvc.perform(get("/api/areas").param("localities", "11,21"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("no hay localidad 21; van de 1 (Usaquén) a 20 (Sumapaz)"));
        mvc.perform(get("/api/areas").param("localities", "suba"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("localities no admite el valor suba"));
        mvc.perform(get("/api/areas").param("localities", "11,10"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", Matchers.containsString("bogota.rgraph")))
                .andExpect(jsonPath("$.detail", Matchers.containsString("exec:java@bogota")));
    }

    @Test
    void theClockRefusesASpeedThatWouldSkipTheShiftInOneReport() throws Exception {
        mvc.perform(put("/api/clock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"running\":true,\"speed\":1e9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("la velocidad va hasta 3600×; llegó 1000000000×"));
    }

    @Test
    void eventsAfterASeqMustNotBeNegative() throws Exception {
        mvc.perform(get("/api/events").param("after", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/events").param("after", "uno"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("after no admite el valor uno"));
    }

    @Test
    void everyEventSaysWhatItIsAndWhichAreaWhenSentAloneOrInTheHistory() {
        OperationEvent overflow = new OverflowEvent(2, 5, "N0001", 600, "reporte", LiveOperation.Pickup.PLANNED, "C-01",
                900, 0, 0.1, List.of(), Map.of());

        assertThat(json.writeValueAsString(overflow)).contains("\"type\":\"overflow\"", "\"area\":5");
        assertThat(json.writeValueAsString(new ShiftMessage(1, 5, 0))).contains("\"area\":5");
        assertThat(json.writerFor(json.getTypeFactory().constructCollectionType(List.class, OperationEvent.class))
                .writeValueAsString(List.of(overflow))).contains("\"type\":\"overflow\"");
    }
}
