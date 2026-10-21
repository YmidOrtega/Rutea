package me.ymid.rutea.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Sin calentar el plan: armarlo tarda unos 25 s y aquí solo se prueba que el contexto arranca.
@SpringBootTest(properties = "rutea.warm-up=false")
class RuteaApplicationTests {

    @Test
    void contextLoads() {
    }
}
