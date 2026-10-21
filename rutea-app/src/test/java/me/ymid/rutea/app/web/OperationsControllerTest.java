package me.ymid.rutea.app.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Sin los datos de OSM (como en la CI), la app arranca igual y dice qué falta y cómo generarlo. */
// Con ".." a propósito: así vienen las rutas por defecto y Spring no debe rechazarlas al arrancar.
@SpringBootTest(properties = {"rutea.graph=../no-existe/suba.rgraph", "rutea.sites=../no-existe/sitios.csv",
        "rutea.warm-up=false"})
@AutoConfigureMockMvc
class OperationsControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void planWithoutOsmDataSaysWhatIsMissingAndHowToBuildIt() throws Exception {
        mvc.perform(get("/api/plan"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", Matchers.containsString("suba.rgraph")))
                .andExpect(jsonPath("$.error", Matchers.containsString("mvn -pl rutea-geo compile exec:java")));
    }

    @Test
    void eventHistoryStartsEmptyEvenWithoutData() throws Exception {
        mvc.perform(get("/api/events")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void breakdownWithoutOsmDataIsAlsoUnavailable() throws Exception {
        mvc.perform(post("/api/breakdowns").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"truck\":\"C-01\",\"atSeconds\":600}"))
                .andExpect(status().isServiceUnavailable());
    }
}
