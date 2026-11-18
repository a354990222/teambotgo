package com.chaos.core.exception;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

class SafeExceptionDiagnosticsTest {
    @Test
    void preservesLocationsWithoutExceptionMessagesOrCauseValues() {
        var exception = new IllegalStateException("SECRET OUTER", new IllegalArgumentException("SECRET SQL"));
        String diagnostics = SafeExceptionDiagnostics.describe(exception);
        assertTrue(diagnostics.contains("IllegalStateException"));
        assertTrue(diagnostics.contains("IllegalArgumentException"));
        assertTrue(diagnostics.contains("SafeExceptionDiagnosticsTest.java"));
        assertFalse(diagnostics.contains("SECRET"));
    }
}
