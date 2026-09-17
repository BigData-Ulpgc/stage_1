package es.ulpgc.bigdata;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PruebaInicialTest {

    @Test
    public void testEntornoFuncionando() {
        boolean entornoListo = true;
        assertTrue(entornoListo, "El entorno debería estar listo");
    }
}
