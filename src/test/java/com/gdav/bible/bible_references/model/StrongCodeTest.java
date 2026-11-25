package com.gdav.bible.bible_references.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class StrongCodeTest {

    @Test
    void of_simple() {
        StrongCode code = StrongCode.of("H430");
        assertEquals("H430", code.value());
        assertEquals("H430", code.first());
        assertNull(code.second());
        assertFalse(code.isCompound());
    }

    @Test
    void of_compuesto() {
        StrongCode code = StrongCode.of("H853 H430");
        assertEquals("H853 H430", code.value());
        assertEquals("H853", code.first());
        assertEquals("H430", code.second());
        assertTrue(code.isCompound());
    }

    @Test
    void of_normalizaMayusculasYEspacios() {
        assertEquals("H430", StrongCode.of(" h430 ").value());
        assertEquals("H853 H430", StrongCode.of("h853   H430").value());
        assertEquals("H853 H430", StrongCode.of("H853\tH430").value());
    }

    @Test
    void reversed() {
        assertEquals("H430 H853", StrongCode.of("H853 H430").reversed().value());
        assertEquals("H430", StrongCode.of("H430").reversed().value());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "H%", "H_", "H430 G%", "H1 H2 H3", "H-1", "H430;"})
    void of_malFormado_lanza(String raw) {
        assertThrows(IllegalArgumentException.class, () -> StrongCode.of(raw));
    }

    @Test
    void constructor_validaTambien() {
        assertThrows(IllegalArgumentException.class, () -> new StrongCode(null, null));
        assertThrows(IllegalArgumentException.class, () -> new StrongCode("h430", null));
        assertThrows(IllegalArgumentException.class, () -> new StrongCode("H1", "H 2"));
    }
}
