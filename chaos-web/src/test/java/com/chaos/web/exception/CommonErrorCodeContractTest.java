package com.chaos.web.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.core.exception.ChaosException;
import com.chaos.core.exception.CommonErrorCode;
import com.chaos.web.result.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/** 语义错误码与 HTTP 状态是两个独立契约。 */
class CommonErrorCodeContractTest {
    @ParameterizedTest
    @CsvSource({
        "BAD_REQUEST, COMMON_BAD_REQUEST, 400",
        "UNAUTHORIZED, COMMON_UNAUTHORIZED, 401",
        "FORBIDDEN, COMMON_FORBIDDEN, 403",
        "NOT_FOUND, COMMON_NOT_FOUND, 404",
        "METHOD_NOT_ALLOWED, COMMON_METHOD_NOT_ALLOWED, 405",
        "CONFLICT, COMMON_CONFLICT, 409",
        "IDEMPOTENT_REJECTED, COMMON_IDEMPOTENT_REJECTED, 409",
        "UNSUPPORTED_MEDIA_TYPE, COMMON_UNSUPPORTED_MEDIA_TYPE, 415",
        "TOO_MANY_REQUESTS, COMMON_TOO_MANY_REQUESTS, 429",
        "INTERNAL_ERROR, COMMON_INTERNAL_ERROR, 500",
        "SERVICE_UNAVAILABLE, COMMON_SERVICE_UNAVAILABLE, 503"
    })
    void preservesHttpStatusAndUsesDistinctSemanticCode(String name, String code, int status) {
        // 模拟应用映射器声明处理所有业务码，框架内置码仍必须遵守自身契约。
        var handler = new GlobalExceptionHandler(value -> HttpStatus.BAD_REQUEST);
        var response = handler.handleChaosException(new ChaosException(CommonErrorCode.valueOf(name)));
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().code()).isEqualTo(code);
    }

    @Test
    void keepsSuccessAndUniqueMachineReadableCodes() {
        assertThat(Result.success().code()).isEqualTo("0");
        assertThat(CommonErrorCode.values()).extracting(CommonErrorCode::code).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @CsvSource({"410, COMMON_GONE", "422, COMMON_UNPROCESSABLE_ENTITY",
                "499, COMMON_HTTP_ERROR", "511, COMMON_INTERNAL_ERROR"})
    void preservesOtherHttpStatusesWithoutNumericResponseCodes(int status, String code) throws Exception {
        var response = new GlobalExceptionHandler().handleException(
                new ResponseStatusException(HttpStatusCode.valueOf(status), "secret"));
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().message()).doesNotContain("secret");
    }
}
