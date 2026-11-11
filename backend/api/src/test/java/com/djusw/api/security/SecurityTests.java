package com.djusw.api.security;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"DB_USER=test", "DB_PASSWORD=test"})
@Import(SecurityTests.TestEndpoints.class)
class SecurityTests {
    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void healthAndVersionArePublic() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/version")).andExpect(status().isOk());
    }

    @Test
    void anonymousRequestReceivesJsonUnauthorized() throws Exception {
        mvc.perform(get("/api/test/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void missingPathReturnsJsonNotFound() throws Exception {
        mvc.perform(get("/api/does-not-exist").with(authentication(user(CurrentUser.Role.STUDENT))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void frameworkMethodErrorsKeepStatusHeadersAndJsonBody() throws Exception {
        mvc.perform(post("/api/test/me").with(authentication(user(CurrentUser.Role.STUDENT))).with(csrf().asHeader()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void unsupportedContentTypeReturnsJson() throws Exception {
        mvc.perform(post("/api/test/json").with(authentication(user(CurrentUser.Role.STUDENT))).with(csrf().asHeader())
                        .contentType("text/plain").content("test"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void errorDispatchReturnsJsonEvenForHtmlAccept() throws Exception {
        mvc.perform(get("/error").with(request -> { request.setDispatcherType(DispatcherType.ERROR); return request; })
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404).accept("text/html"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(get("/error").with(request -> { request.setDispatcherType(DispatcherType.ERROR); return request; })
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("internal_error"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void anonymousWritesReturnUnauthorizedWhenCsrfTokenIsMissingOrInvalid() throws Exception {
        for (var request : List.of(post("/api/test/write"), patch("/api/test/write"),
                delete("/api/test/write"), post("/api/test/write").header("X-CSRF-Token", "invalid"),
                patch("/api/test/write").header("X-CSRF-Token", "invalid"),
                delete("/api/test/write").header("X-CSRF-Token", "invalid"))) {
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }

    @Test
    void guestNeedsProfileButCanAccessCurrentUser() throws Exception {
        var guest = user(CurrentUser.Role.GUEST);
        mvc.perform(get("/api/test/me").with(authentication(guest)))
                .andExpect(status().isOk()).andExpect(content().string("7"));
        mvc.perform(get("/api/test/profile").with(authentication(guest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("profile_required"));
    }

    @Test
    void roleCheckAllowsAdminAndRejectsStudent() throws Exception {
        mvc.perform(get("/api/test/admin").with(authentication(user(CurrentUser.Role.STUDENT))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("forbidden"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(get("/api/test/admin").with(authentication(user(CurrentUser.Role.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void loadsCurrentUserFromSessionSecurityContext() throws Exception {
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(user(CurrentUser.Role.STUDENT));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        mvc.perform(get("/api/test/me").session(session))
                .andExpect(status().isOk()).andExpect(content().string("7"));
    }

    @Test
    void sessionTokenProtectsWritingRequests() throws Exception {
        var result = mvc.perform(get("/api/test/csrf").with(authentication(user(CurrentUser.Role.STUDENT))))
                .andExpect(status().isOk()).andReturn();
        String token = result.getResponse().getContentAsString();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        org.junit.jupiter.api.Assertions.assertNotNull(session);
        org.junit.jupiter.api.Assertions.assertFalse(token.isBlank());

        mvc.perform(post("/api/test/write").session(session).with(authentication(user(CurrentUser.Role.STUDENT))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("csrf"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(patch("/api/test/write").session(session).with(authentication(user(CurrentUser.Role.STUDENT))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("csrf"));
        mvc.perform(delete("/api/test/write").session(session).with(authentication(user(CurrentUser.Role.STUDENT))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("csrf"));
        mvc.perform(post("/api/test/write").session(session).with(authentication(user(CurrentUser.Role.STUDENT)))
                        .header("X-CSRF-Token", "incorrect"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("csrf"));
        mvc.perform(post("/api/test/write").session(session).with(authentication(user(CurrentUser.Role.STUDENT)))
                        .header("X-CSRF-Token", token)).andExpect(status().isOk());
        mvc.perform(patch("/api/test/write").session(session).with(authentication(user(CurrentUser.Role.STUDENT)))
                        .header("X-CSRF-Token", token)).andExpect(status().isOk());
        mvc.perform(delete("/api/test/write").session(session).with(authentication(user(CurrentUser.Role.STUDENT)))
                        .header("X-CSRF-Token", token)).andExpect(status().isOk());
        mvc.perform(post("/api/test/write").session(new MockHttpSession())
                        .with(authentication(user(CurrentUser.Role.STUDENT))).header("X-CSRF-Token", token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("csrf"));
    }

    @Test
    void csrfTokenInRequestParameterDoesNotReplaceRequiredHeader() throws Exception {
        mvc.perform(post("/api/test/write").with(authentication(user(CurrentUser.Role.STUDENT))).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("csrf"));
    }

    @Test
    void operatorLoginIsExemptAndCanObtainTokenForLaterRequests() throws Exception {
        var result = mvc.perform(post("/auth/admin/login"))
                .andExpect(status().isOk()).andReturn();
        String token = result.getResponse().getContentAsString();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        org.junit.jupiter.api.Assertions.assertNotNull(session);
        org.junit.jupiter.api.Assertions.assertFalse(token.isBlank());
        mvc.perform(post("/api/test/write").session(session).header("X-CSRF-Token", token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("unauthorized"));
        mvc.perform(post("/api/test/write").session(session).with(authentication(user(CurrentUser.Role.ADMIN)))
                        .header("X-CSRF-Token", token)).andExpect(status().isOk());
    }

    @Test
    void protectedWriteRequiresBothRoleAndValidCsrfToken() throws Exception {
        var result = mvc.perform(get("/api/test/csrf").with(authentication(user(CurrentUser.Role.ADMIN))))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        String token = result.getResponse().getContentAsString();

        mvc.perform(post("/api/test/admin-write").session(session)
                        .with(authentication(user(CurrentUser.Role.STUDENT))).header("X-CSRF-Token", token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(post("/api/test/admin-write").session(session)
                        .with(authentication(user(CurrentUser.Role.ADMIN))).header("X-CSRF-Token", token))
                .andExpect(status().isOk());
    }

    @Test
    void roleCheckAcceptsAnyListedRole() throws Exception {
        mvc.perform(get("/api/test/managers").with(authentication(user(CurrentUser.Role.SUPER_ADMIN))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/test/managers").with(authentication(user(CurrentUser.Role.ADMIN))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/test/managers").with(authentication(user(CurrentUser.Role.PROFESSOR))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void guestReceivesProfileRequiredFromRoleCheck() throws Exception {
        mvc.perform(get("/api/test/admin").with(authentication(user(CurrentUser.Role.GUEST))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("profile_required"));
    }

    @Test
    void principalOtherThanCurrentUserIsNotLoggedIn() throws Exception {
        mvc.perform(get("/api/test/me").with(authentication(otherPrincipal())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void githubLoginPathsArePublic() throws Exception {
        for (String path : List.of("/auth/github/login", "/auth/github/callback")) {
            mvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("not_found"));
        }
    }

    @Test
    void earlierMaskedTokenRemainsValidInSameSession() throws Exception {
        var first = mvc.perform(get("/api/test/csrf").with(authentication(user(CurrentUser.Role.STUDENT))))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) first.getRequest().getSession(false);
        String earlier = first.getResponse().getContentAsString();
        String later = mvc.perform(get("/api/test/csrf").session(session)
                        .with(authentication(user(CurrentUser.Role.STUDENT))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertNotEquals(earlier, later);

        for (String token : List.of(earlier, later)) {
            mvc.perform(post("/api/test/write").session(session)
                            .with(authentication(user(CurrentUser.Role.STUDENT))).header("X-CSRF-Token", token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void apiErrorKeepsStatusWhenBrowserAsksForHtml() throws Exception {
        mvc.perform(get("/api/test/profile").with(authentication(user(CurrentUser.Role.GUEST))).accept("text/html"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("profile_required"));
    }

    @Test
    void defaultLogoutEndpointDoesNotRedirectToMissingLoginPage() throws Exception {
        mvc.perform(post("/logout").with(authentication(user(CurrentUser.Role.STUDENT))).with(csrf().asHeader()))
                .andExpect(status().is4xxClientError())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void generatedInMemoryUserIsNotCreated() {
        org.junit.jupiter.api.Assertions.assertEquals(0,
                context.getBeanNamesForType(InMemoryUserDetailsManager.class).length);
    }

    @Test
    void csrfFailureUsesSameLoginDecisionAsCurrentUserService() throws Exception {
        mvc.perform(post("/api/test/write").with(authentication(otherPrincipal())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("unauthorized"));
    }

    private UsernamePasswordAuthenticationToken user(CurrentUser.Role role) {
        return UsernamePasswordAuthenticationToken.authenticated(new CurrentUser(7, role), null, List.of());
    }

    private UsernamePasswordAuthenticationToken otherPrincipal() {
        return UsernamePasswordAuthenticationToken.authenticated("github-user", null, List.of());
    }

    @TestConfiguration
    static class TestEndpoints {
        @Bean TestController testController(CurrentUserService users, CsrfTokenService csrf) {
            return new TestController(users, csrf);
        }
    }

    @RestController
    static class TestController {
        private final CurrentUserService users;
        private final CsrfTokenService csrf;
        TestController(CurrentUserService users, CsrfTokenService csrf) { this.users = users; this.csrf = csrf; }

        @GetMapping("/api/test/me") long me() { return users.requireCurrentUser().id(); }
        @GetMapping("/api/test/profile") void profile() { users.requireCompletedProfile(); }
        @GetMapping("/api/test/admin") void admin() { users.requireRole(CurrentUser.Role.ADMIN); }
        @GetMapping("/api/test/managers") void managers() {
            users.requireRole(CurrentUser.Role.SUPER_ADMIN, CurrentUser.Role.ADMIN);
        }
        @GetMapping("/api/test/csrf") String token(HttpServletRequest request) { return csrf.tokenFor(request); }
        @PostMapping("/auth/admin/login") String login(HttpServletRequest request) { return csrf.tokenFor(request); }
        @PostMapping("/api/test/write") void postWrite() {}
        @PostMapping(value = "/api/test/json", consumes = "application/json") void json() {}
        @PatchMapping("/api/test/write") void patchWrite() {}
        @DeleteMapping("/api/test/write") void deleteWrite() {}
        @PostMapping("/api/test/admin-write") void adminWrite() { users.requireRole(CurrentUser.Role.ADMIN); }
    }
}
