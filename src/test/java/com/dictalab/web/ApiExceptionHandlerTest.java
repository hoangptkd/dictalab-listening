package com.dictalab.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.junit.jupiter.params.provider.CsvSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTest {
    @ParameterizedTest
    @CsvSource({"transaction,503", "connection,503", "constraint,409", "statement-timeout,503",
            "transaction-timeout,503", "lock-timeout,409", "deadlock,409"})
    void databaseFailureHasExplicitStatusWithoutLeakingDetails(String kind, int expected) throws Exception {
        var result = MockMvcBuilders.standaloneSetup(new InputController())
                .setControllerAdvice(new ApiExceptionHandler()).build()
                .perform(get("/database/" + kind)).andExpect(status().is(expected))
                .andExpect(jsonPath("$.status").value(expected));
        if (expected == 503) result.andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.errorId").isString());
        else result.andExpect(header().doesNotExist("Retry-After"));
        org.assertj.core.api.Assertions.assertThat(result.andReturn().getResponse().getContentAsString())
                .doesNotContain("private-db-detail");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"lessonId\":[1]}", "{\"lessonId\":\"private-invalid-value\"}", "{"})
    void malformedJsonIsAClientErrorWithoutLeakingItsBody(String body) throws Exception {
        MockMvcBuilders.standaloneSetup(new InputController())
                .setControllerAdvice(new ApiExceptionHandler()).build()
                .perform(post("/input").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Dữ liệu không hợp lệ."))
                .andExpect(jsonPath("$.errorId").doesNotExist());
    }

    @RestController
    static class InputController {
        @GetMapping("/database/{kind}")
        void database(@org.springframework.web.bind.annotation.PathVariable String kind) {
            switch (kind) {
                case "transaction" -> throw new org.springframework.transaction.CannotCreateTransactionException("private-db-detail");
                case "connection" -> throw new org.springframework.jdbc.CannotGetJdbcConnectionException("private-db-detail");
                case "statement-timeout" -> throw new org.springframework.dao.QueryTimeoutException("private-db-detail");
                case "transaction-timeout" -> throw new org.springframework.transaction.TransactionTimedOutException("private-db-detail");
                case "lock-timeout" -> throw new org.springframework.dao.CannotAcquireLockException("private-db-detail");
                case "deadlock" -> throw new org.springframework.dao.PessimisticLockingFailureException("private-db-detail");
                default -> throw new org.springframework.dao.DataIntegrityViolationException("private-db-detail");
            }
        }
        @PostMapping("/input")
        Input input(@RequestBody Input input) { return input; }
    }
    record Input(Long lessonId) {}
}
