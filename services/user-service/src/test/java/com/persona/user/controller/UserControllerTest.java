package com.persona.user.controller;

import com.persona.user.dto.CreateUserRequest;
import com.persona.user.dto.UpdateProfileRequest;
import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import com.persona.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * <h2>What this class is really testing, in a microservice</h2>
 *
 * <p>In the monolith these tests check a translation layer. Here they check
 * <b>the service's public contract</b>: an exception cannot cross the wire, so
 * every caller of user-service sees only the status code and the body shape
 * asserted below. A 409 quietly becoming a 422, or {@code errors} becoming
 * {@code fieldErrors}, breaks every consumer — and breaks them silently,
 * because nothing anywhere fails to compile.
 *
 * <p>Which makes the assertions in this file closer to a published schema than
 * to ordinary unit tests. That is also why {@code thereIsNoCountEndpoint}
 * matters more here than it did in the monolith: in a microservice, a public
 * method that accidentally becomes a public endpoint is a public method another
 * team will start depending on.
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
/*
 * DAY-06 — why this class needed changing when security arrived.
 *
 * Adding spring-boot-starter-security turned all 19 tests here red at once,
 * and the error was not about authentication: it was
 * NoSuchBeanDefinitionException: JwtService. The cause is what @WebMvcTest is
 * for. It loads the WEB slice — controllers, Jackson, advice — and also
 * SecurityConfig, because a filter chain is part of the web layer. That
 * config depends on JwtAuthenticationFilter, which depends on JwtService,
 * TokenDenyList and UserRepository — none of which are web beans, so none are
 * in the slice.
 *
 * @MockitoBean supplies them, for the same reason it already supplies
 * UserService: this class tests HTTP translation, and the genuine security
 * behaviour — 401 without a token, 403 for the wrong role — is verified end
 * to end against a running service instead, where a real token and a real
 * filter chain are in play. A slice test asserting on a mocked filter would
 * be asserting on the mock.
 *
 * @AutoConfigureMockMvc(addFilters = false) then takes the filter chain back
 * out, so these tests keep asserting what they were written to assert. That
 * is a deliberate narrowing and the risk is worth naming: nothing in this
 * file would notice if every endpoint became public. The verification script
 * covers that, by asking a real server rather than a mock.
 */
@WebMvcTest(UserController.class)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService service;

    // Day-06. Not used by any assertion here — present so SecurityConfig's
    // filter chain can be constructed at all. See the comment above the class.
    @MockitoBean
    private com.persona.user.config.JwtService jwtService;

    @MockitoBean
    private com.persona.user.config.TokenDenyList tokenDenyList;

    @MockitoBean
    private com.persona.user.repository.UserRepository userRepository;

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
        when(service.register(any(CreateUserRequest.class))).thenReturn(anishom());

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
        when(service.register(any(CreateUserRequest.class))).thenReturn(anishom());

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
        when(service.register(any(CreateUserRequest.class)))
                .thenThrow(new DuplicateEmailException("khi0ne@example.com"));

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").exists());
    }

    /**
     * Invalid input is 400 — and as of Day-05 it is rejected one layer earlier
     * than it used to be.
     *
     * <p>This test passed before today too, but for a different reason, and the
     * difference is the whole point of adding {@code @Valid}. Previously the
     * controller built a {@code User}, whose constructor threw
     * {@code IllegalArgumentException}. Now {@code @NotBlank} rejects the body
     * before any application code runs at all — the service is never called, and
     * neither is the model.
     *
     * <p>Both layers still exist and both still matter; see
     * {@link CreateUserRequest} for why deleting either one causes a real
     * defect.
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
                .andExpect(jsonPath("$.detail").exists());
    }

    /**
     * An empty page is a successful answer: {@code 200} with empty
     * {@code content}, never 404.
     */
    @Test
    void listingWithNoUsersReturns200AndEmptyContent() throws Exception {
        when(service.findAll(any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listingReturnsAPageOfUsers() throws Exception {
        when(service.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(anishom()),
                        PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].email").value("khi0ne@example.com"))
                .andExpect(jsonPath("$.content[0].password").doesNotExist());
    }

    /**
     * <b>The Day-09 shape change, asserted deliberately.</b> The response used to
     * be a bare JSON array; it is now an object carrying the rows under
     * {@code content} plus the totals a UI needs to draw "page 3 of 7".
     *
     * <p>This test exists to make the break loud. Without it, a later refactor
     * could quietly return to an array and every other test here would still
     * pass — they read {@code $.content[0]}, which simply would not match, and
     * {@code jsonPath} on a missing path fails for reasons that do not name the
     * cause.
     */
    @Test
    void listingReturnsAPageObjectRatherThanABareArray() throws Exception {
        when(service.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(anishom()),
                        PageRequest.of(0, 20), 42));

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap())
                .andExpect(jsonPath("$.totalElements").value(42))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(0));
    }

    /**
     * The query string reaches the service as a {@link Pageable}. Nothing in the
     * controller parses it — Spring resolves {@code page} and {@code size} — so
     * what is verified is that the resolved values are the ones asked for and
     * are passed through rather than ignored.
     */
    @Test
    void pageAndSizeFromTheQueryStringReachTheService() throws Exception {
        when(service.findAll(any(Pageable.class))).thenReturn(Page.empty());

        mockMvc.perform(get("/users").param("page", "2").param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).findAll(captor.capture());

        assertEquals(2, captor.getValue().getPageNumber());
        assertEquals(5, captor.getValue().getPageSize());
    }

    /**
     * With no query string the defaults from {@code @PageableDefault} apply, and
     * the sort is pinned to {@code id}.
     *
     * <p>The sort assertion is the one worth having: an unsorted page is not
     * merely untidy, it is incorrect, because {@code LIMIT}/{@code OFFSET} over
     * an unordered result can repeat rows on one page and skip them on the next.
     */
    @Test
    void defaultsApplyWhenNoPagingParametersAreGiven() throws Exception {
        when(service.findAll(any(Pageable.class))).thenReturn(Page.empty());

        mockMvc.perform(get("/users")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).findAll(captor.capture());

        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(20, captor.getValue().getPageSize());
        assertEquals(Sort.by("id"), captor.getValue().getSort());
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
                .andExpect(jsonPath("$.detail").exists());
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

    // =================================================================
    //  Day-05 — validation at the edge, and the shape of an error.
    // =================================================================

    /**
     * Several invalid fields produce ONE response naming ALL of them.
     *
     * <p>This is what {@code @Valid} buys that a hand-written guard cannot. A
     * guard is a {@code throw}, and a throw ends the method, so it reports the
     * first failure only: the caller fixes one field, resubmits, and discovers
     * the next. Bean validation collects every violation before failing.
     *
     * <p>Note the test asserts on the STRUCTURE, not on the message text. The
     * response carries a field-to-message map, so a client can highlight three
     * inputs without parsing English out of a sentence. An error is data.
     */
    @Test
    void everyInvalidFieldIsReportedAtOnce() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "firstName": "",
                                 "lastName": "", "password": "short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.firstName").exists())
                .andExpect(jsonPath("$.errors.lastName").exists())
                .andExpect(jsonPath("$.errors.password").exists());

        // And the service was never reached. Validation at the edge means the
        // application layer is not asked to defend itself against nonsense it
        // could not have produced.
        verifyNoInteractions(service);
    }

    /**
     * A password shorter than the minimum is rejected, with the field named.
     *
     * <p>Worth its own test rather than folding into the one above, because this
     * is the constraint most likely to be loosened by someone in a hurry, and a
     * named test is harder to delete silently than one assertion inside five.
     */
    @Test
    void aShortPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "khi0ne@example.com", "firstName": "Anishom",
                                 "lastName": "Frost", "password": "short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    /**
     * Malformed JSON is 400, not 500.
     *
     * <p>Without a handler for {@code HttpMessageNotReadableException}, a
     * trailing comma in a request body is reported as a server error — the
     * server confessing to the caller's typo. 4xx and 5xx answer "whose problem
     * is this", and that answer is what the whole alerting stack is built on.
     */
    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json "))
                .andExpect(status().isBadRequest());
    }

    /**
     * An error body never leaks the internals that produced it.
     *
     * <p>Asserting on absence again. The failure this prevents — a constraint
     * name, a table name or a stack trace reaching the client — is invisible in
     * every manual test, because the response still looks like a sensible error.
     */
    @Test
    void anErrorBodyNeverLeaksInternals() throws Exception {
        when(service.register(any(CreateUserRequest.class)))
                .thenThrow(new DuplicateEmailException("khi0ne@example.com"));

        String body = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "khi0ne@example.com", "firstName": "Anishom",
                                 "lastName": "Frost", "password": "Pass1234#"}
                                """))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("users_email_key"), "constraint names must not leak");
        assertFalse(body.contains("org.springframework"), "stack traces must not leak");
        assertFalse(body.contains("Pass1234#"), "the password must not appear in an error");
    }

    @Test
    void updatingAProfileReturns200AndTheUpdatedUser() throws Exception {
        User updated = new User("khi0ne@example.com", "Turhan", "Winter", "$2a$10$hash");
        when(service.updateProfile(anyString(), any(UpdateProfileRequest.class)))
                .thenReturn(updated);

        mockMvc.perform(put("/users/khi0ne@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName": "Turhan", "lastName": "Winter", "image": null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Turhan"))
                .andExpect(jsonPath("$.username").value("@turhan-winter"));
    }

    /**
     * A profile update cannot change the password, no matter what is sent.
     *
     * <p>The request body below contains {@code passwordHash} and {@code email}
     * fields. {@link UpdateProfileRequest} has neither, so Jackson discards them
     * and there is nothing for the server to defend against — the defence is
     * that the fields do not exist, not that they are checked for.
     *
     * <p>This is the test that fails the day somebody "simplifies" the endpoint
     * to take a {@code User}, which is exactly the mass-assignment hole this
     * record was written to close.
     */
    @Test
    void aProfileUpdateCannotChangeThePasswordOrEmail() throws Exception {
        User updated = new User("khi0ne@example.com", "Turhan", "Winter", "$2a$10$hash");
        when(service.updateProfile(anyString(), any(UpdateProfileRequest.class)))
                .thenReturn(updated);

        mockMvc.perform(put("/users/khi0ne@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName": "Turhan", "lastName": "Winter",
                                 "passwordHash": "attacker-chosen",
                                 "email": "attacker@evil.com",
                                 "id": 1}
                                """))
                .andExpect(status().isOk());

        // The service received a DTO that has no field capable of carrying
        // either value. Captured rather than assumed.
        ArgumentCaptor<UpdateProfileRequest> captor =
                ArgumentCaptor.forClass(UpdateProfileRequest.class);
        verify(service).updateProfile(anyString(), captor.capture());

        assertEquals("Turhan", captor.getValue().firstName());
        // There is no getter to assert on for password or email, which IS the
        // assertion: the type cannot express them.
    }

    @Test
    void updatingAMissingUserReturns404() throws Exception {
        when(service.updateProfile(anyString(), any(UpdateProfileRequest.class)))
                .thenThrow(new UserNotFoundException("nobody@example.com"));

        mockMvc.perform(put("/users/nobody@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName": "Turhan", "lastName": "Winter", "image": null}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
    }

    /**
     * A blank name in an update is rejected by the DTO, not by the model.
     *
     * <p>Same two-layer story as signup: {@code @NotBlank} answers "did the
     * caller send something usable" at the edge, while {@code requireText}
     * inside {@code User} still guarantees the object cannot hold a blank name
     * regardless of who is calling.
     */
    @Test
    void aBlankNameInAnUpdateIsRejected() throws Exception {
        mockMvc.perform(put("/users/khi0ne@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName": "", "lastName": "Winter", "image": null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.firstName").exists());

        verifyNoInteractions(service);
    }
}
