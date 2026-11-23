package com.chaos.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.chaos.core.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;

/**
 * {@link DomainException} 单元测试。
 */
class DomainExceptionTest {

    @Test
    void shouldPreserveErrorCodeMessageAndCause() {
        DomainException defaultCode = new DomainException("invalid invariant");
        assertEquals(CommonErrorCode.BAD_REQUEST, defaultCode.errorCode());
        assertEquals("invalid invariant", defaultCode.getMessage());

        DomainException codeOnly = new DomainException(CommonErrorCode.CONFLICT);
        assertEquals(CommonErrorCode.CONFLICT, codeOnly.errorCode());

        IllegalStateException cause = new IllegalStateException("root");
        DomainException withCause = new DomainException(CommonErrorCode.FORBIDDEN, "denied", cause);
        assertEquals(CommonErrorCode.FORBIDDEN, withCause.errorCode());
        assertEquals("denied", withCause.getMessage());
        assertSame(cause, withCause.getCause());
    }
}
