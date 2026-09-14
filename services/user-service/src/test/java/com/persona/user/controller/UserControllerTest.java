package com.persona.user.controller;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import com.persona.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link UserController}.
 *
 * <p>These are the first tests in persona that boot Spring, and the reason is
 * worth stating: {@link com.persona.user.service.UserService} could be tested with
 * {@code new} because it is plain Java, but there is no plain-Java way to ask
 * "does a POST to /users return 201 with a Location header?". The thing under
 * test <em>is</em> the HTTP translation, so HTTP has to be in the room.
 *
 * <p>{@code @WebMvcTest} boots only the web layer — this controller, Jackson, the
 * exception handlers — and deliberately not the service, the repository or a
 * database. It is a slice test, and it costs a fraction of a full
 * {@code @SpringBootTest}.
 *
 * <p>{@code @MockitoBean} then supplies a fake {@code UserService}. That is what
 * makes a case like "409 when the email is taken" cheap to write: the test states
 * that the service throws, without having to first register a real user. The
 * controller's job is to turn that exception into a status code, and that is
 * precisely what is being asserted.
 *
 * <p>{@code MockMvc} sends requests through the whole Spring MVC machinery —
 * routing, binding, JSON serialisation, exception handling — without opening a
 * TCP socket. Real dispatch, no network.
 */
@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService service;

    private User anishom() {
        return new User("khi0ne@example.com", "Anishom", "Frost", "Pass1234#");
    }

    private static final String VALID_JSON = """
            {
              "email": "khi0ne@example.com",
              "firstName": "Anishom",
              "lastName": "Frost",
              "password": "Pass1234#"
            }
            """;

    /**
     * The full happy path for creation: 201, a Location header naming the new
     * resource, and the created user in the body.
     */
    @Test
    void signupReturns201WithLocation() throws Exception {
        when(service.register(any(User.class))).thenReturn(anishom());

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/users/khi0ne@example.com"))
                .andExpect(jsonPath("$.email").value("khi0ne@example.com"))
                .andExpect(jsonPath("$.username").value("@anishom-frost"));
    }

    /**
     * The most important test in this file.
     *
     * <p>It does not assert what the response contains. It asserts what it
     * <b>cannot</b> contain. A password leak is not a thing a normal test notices —
     * every other assertion passes perfectly while the field is present — so the
     * absence has to be asserted directly.
     *
     * <p>{@code $.password} with {@code doesNotExist()} rather than checking the
     * value: the field must not be in the JSON at all, not merely be empty.
     */
    @Test
    void responseNeverContainsThePassword() throws Exception {
        when(service.register(any(User.class))).thenReturn(anishom());

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Pass1234#"))));
    }

    /**
     * A duplicate email is 409, not 500 and not 400.
     *
     * <p>Note the controller contains no duplicate check — the mock simply throws,
     * exactly as the real service does. This test therefore verifies the
     * {@code @ExceptionHandler} routing table, which is the only place the
     * controller knows about duplicates at all.
     */
    @Test
    void duplicateEmailReturns409() throws Exception {
        when(service.register(any(User.class)))
                .thenThrow(new DuplicateEmailException("khi0ne@example.com"));

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    /**
     * Invalid input is 400 — the model's own {@code IllegalArgumentException},
     * translated.
     *
     * <p>The service is never reached here: the controller builds the {@code User}
     * first, and the constructor rejects the blank email before
     * {@code register} is called. Validation at the edge, by the model, without the
     * controller knowing the rule.
     */
    @Test
    void blankEmailReturns400() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "", "firstName": "Anishom",
                                 "lastName": "Frost", "password": "Pass1234#"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /** An empty collection is a successful answer: {@code 200 []}, never 404. */
    @Test
    void listingWithNoUsersReturns200AndEmptyArray() throws Exception {
        when(service.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void listingReturnsEveryUser() throws Exception {
        when(service.findAll()).thenReturn(List.of(anishom()));

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("khi0ne@example.com"))
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }

    @Test
    void fetchingOneUserReturns200() throws Exception {
        when(service.getByEmail("khi0ne@example.com")).thenReturn(anishom());

        mockMvc.perform(get("/users/khi0ne@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Anishom"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /** A missing user is 404 — the exact opposite of the 409 case above. */
    @Test
    void fetchingAMissingUserReturns404() throws Exception {
        when(service.getByEmail(anyString()))
                .thenThrow(new UserNotFoundException("nobody@example.com"));

        mockMvc.perform(get("/users/nobody@example.com"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    /**
     * 204 means no content, and the test asserts the body really is empty — the
     * status code is a promise, and this is the promise being checked.
     */
    @Test
    void deletingReturns204WithNoBody() throws Exception {
        doNothing().when(service).deleteByEmail("khi0ne@example.com");

        mockMvc.perform(delete("/users/khi0ne@example.com"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    /**
     * Deleting something that is not there is 404 — and DELETE is still idempotent.
     *
     * <p>Idempotency is a claim about the state of the server, not about the
     * response being identical. After this call, as after the previous one, the user
     * does not exist. That is what makes a retry safe.
     */
    @Test
    void deletingAMissingUserReturns404() throws Exception {
        doThrow(new UserNotFoundException("nobody@example.com"))
                .when(service).deleteByEmail(anyString());

        mockMvc.perform(delete("/users/nobody@example.com"))
                .andExpect(status().isNotFound());
    }

    /**
     * There is no {@code /users/count} endpoint, and that is deliberate.
     *
     * <p>{@code UserService.count()} is a public method, and a public method is not
     * automatically a public endpoint. It exists because the service tests needed a
     * way to observe the repository. Exposing it would also collide with
     * {@code /users/{email}} — Spring would have to decide whether {@code count} is
     * an email — and {@code count} is not a noun anyway. A total belongs in an
     * {@code X-Total-Count} response header on the collection, which is Day-07's
     * work alongside pagination.
     *
     * <p>This test pins that decision so it cannot be undone by accident: the path
     * is routed as an email lookup, which for a user named "count" is a 404.
     */
    @Test
    void thereIsNoCountEndpoint() throws Exception {
        when(service.getByEmail("count"))
                .thenThrow(new UserNotFoundException("count"));

        mockMvc.perform(get("/users/count"))
                .andExpect(status().isNotFound());
    }
}
