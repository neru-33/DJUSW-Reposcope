package com.djusw.api.error;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTests {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void serializesApiErrorsWithoutOptionalFields() throws Exception {
        mvc.perform(get("/test/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.fields").doesNotExist());
    }

    @Test
    void includesFieldErrorsWhenProvided() throws Exception {
        mvc.perform(get("/test/invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.fields.name").value("이름을 입력하세요."));
    }

    @Test
    void malformedJsonReturnsInvalidRequest() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void unexpectedErrorsDoNotExposeExceptionDetails() throws Exception {
        mvc.perform(get("/test/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("internal_error"))
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
    }

    @RestController
    static class TestController {
        @GetMapping("/test/unauthorized")
        void unauthorized() {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요합니다.");
        }

        @GetMapping("/test/invalid")
        void invalid() {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "입력값을 확인하세요.",
                    Map.of("name", "이름을 입력하세요."));
        }

        @PostMapping("/test/body")
        void body(@RequestBody Map<String, Object> body) {}

        @GetMapping("/test/failure")
        void failure() { throw new IllegalStateException("internal detail"); }
    }
}
