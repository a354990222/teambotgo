
package com.chaos.domain.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DomainTextsTest {

    @Test
    void hasTextRejectsNullAndUnicodeWhitespace() {
        assertFalse(DomainTexts.hasText(null));
        assertFalse(DomainTexts.hasText(" \t\u2003 "));
        assertTrue(DomainTexts.hasText(" \u2003value "));
    }

    @Test
    void requireTextStripsUnicodeWhitespace() {
        assertEquals("value", DomainTexts.requireText("\u2003value\u2003",
                () -> new IllegalArgumentException("文本不能为空")));
    }

    @Test
    void requireTextUsesCallerProvidedException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> DomainTexts.requireText(" \u2003 ", () -> new IllegalArgumentException("文本不能为空")));

        assertEquals("文本不能为空", exception.getMessage());
    }

    @Test
    void stripToNullNormalizesOptionalText() {
        assertNull(DomainTexts.stripToNull(null));
        assertNull(DomainTexts.stripToNull(" \u2003 "));
        assertEquals("value", DomainTexts.stripToNull(" value "));
    }

    @Test
    void normalizeDistinctFiltersAndPreservesFirstOccurrenceOrder() {
        assertEquals(List.of(), DomainTexts.normalizeDistinct(null));
        assertEquals(List.of("one", "two"),
                DomainTexts.normalizeDistinct(List.of(" one ", "", "two", "one", " \u2003 ")));
    }

    @Test
    void requireTextWithMaxLengthAndIdentityTextValidation() {
        assertEquals("abc", DomainTexts.requireText(" abc ", 5, "标题", IllegalArgumentException::new));
        assertThrows(IllegalArgumentException.class,
                () -> DomainTexts.requireText("abcdef", 5, "标题", IllegalArgumentException::new));
        assertThrows(IllegalArgumentException.class,
                () -> DomainTexts.requireText(" ", 5, "标题", IllegalArgumentException::new));

        DomainTexts.requireIdentityText("user_01", 2, 16, "用户标识");
        assertThrows(IllegalArgumentException.class, () -> DomainTexts.requireIdentityText(" a", 2, 16, "用户标识"));
        assertThrows(IllegalArgumentException.class, () -> DomainTexts.requireIdentityText("a", 2, 16, "用户标识"));
        assertThrows(IllegalArgumentException.class, () -> DomainTexts.requireIdentityText("a\nb", 2, 16, "用户标识"));
    }
}
