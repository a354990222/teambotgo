package com.chaos.web.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;

import com.chaos.core.exception.BizException;
import com.chaos.core.exception.ErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLClassLoader;
import java.util.stream.Stream;

/** 数据库冲突在 HTTP 边界分类，响应和日志均不回显敏感值。 */
class DatabaseConflictHandlingTest {
    @org.junit.jupiter.api.BeforeEach
    void setLocale() {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
    }

    @org.junit.jupiter.api.AfterEach
    void resetLocale() {
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    private static final String SECRET = "secret@example.com SQL INSERT token=SECRET";

    static Stream<RuntimeException> conflicts() {
        return Stream.of(
                new DuplicateKeyException(SECRET),
                new PessimisticLockingFailureException(SECRET),
                new CannotAcquireLockException(SECRET),
                new OptimisticLockingFailureException(SECRET),
                new DuplicateKeyException(SECRET) {});
    }

    @ParameterizedTest
    @MethodSource("conflicts")
    void returnsConflictWithoutLeakingSqlOrValues(RuntimeException exception) throws Exception {
        var mvc =
                MockMvcBuilders.standaloneSetup(new FailureController(exception))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        mvc.perform(get("/failure").header("Accept-Language", "en-US"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMON_CONFLICT"))
                .andExpect(jsonPath("$.message").value("data conflict; refresh and retry"));
        mvc.perform(get("/failure").header("Accept-Language", "zh-CN"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("数据冲突，请刷新后重试"));
    }

    static Stream<RuntimeException> otherFailures() {
        return Stream.of(
                new DataIntegrityViolationException(SECRET),
                new DataAccessResourceFailureException(SECRET),
                new QueryTimeoutException(SECRET),
                new RuntimeException(SECRET, new DuplicateKeyException(SECRET)));
    }

    @ParameterizedTest
    @MethodSource("otherFailures")
    void doesNotMisclassifyOtherDatabaseFailures(RuntimeException exception) throws Exception {
        var response = new GlobalExceptionHandler().handleException(exception);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("internal server error");
    }

    @Test
    void preservesExplicitBusinessErrorCode() throws Exception {
        ErrorCode code =
                new ErrorCode() {
                    public String code() {
                        return "ACCOUNT_DUPLICATE";
                    }

                    public String message() {
                        return "account already exists";
                    }
                };
        var mvc =
                MockMvcBuilders.standaloneSetup(new FailureController(new BizException(code)))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        new ConventionErrorCodeHttpStatusMapper(
                                                value -> value.startsWith("ACCOUNT_"))))
                        .build();
        mvc.perform(get("/failure"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DUPLICATE"))
                .andExpect(jsonPath("$.message").value("account already exists"));
    }

    @Test
    void conflictLogsNeverContainExceptionMessagesOrCause() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
        try {
            new GlobalExceptionHandler()
                    .handleException(
                            new DuplicateKeyException(SECRET, new RuntimeException(SECRET)));
            assertThat(appender.list)
                    .isNotEmpty()
                    .allSatisfy(
                            event -> {
                                assertThat(event.getFormattedMessage())
                                        .doesNotContain("SECRET", "secret@example.com", "INSERT");
                                assertThat(event.getThrowableProxy()).isNull();
                                assertThat(event.getLevel()).isNotEqualTo(Level.ERROR);
                            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    @Test
    void handlerWorksWithoutDaoOnClasspath() throws Exception {
        try (var parent =
                        new FilteredClassLoader(
                                "org.springframework.dao", "com.chaos.web.exception");
                var isolated =
                        new URLClassLoader(
                                new java.net.URL[] {
                                    GlobalExceptionHandler.class
                                            .getProtectionDomain()
                                            .getCodeSource()
                                            .getLocation()
                                },
                                parent)) {
            assertThatThrownBy(
                            () ->
                                    isolated.loadClass(
                                            "org.springframework.dao.DuplicateKeyException"))
                    .isInstanceOf(ClassNotFoundException.class);
            var type = isolated.loadClass(GlobalExceptionHandler.class.getName());
            var handler = type.getConstructor().newInstance();
            var response =
                    (ResponseEntity<?>)
                            type.getMethod("handleException", Exception.class)
                                    .invoke(handler, new IllegalStateException(SECRET));
            assertThat(response.getStatusCode().value()).isEqualTo(500);
        }
    }

    @RestController
    static class FailureController {
        private final RuntimeException failure;

        FailureController(RuntimeException failure) {
            this.failure = failure;
        }

        @GetMapping("/failure")
        String fail() {
            throw failure;
        }
    }
}
