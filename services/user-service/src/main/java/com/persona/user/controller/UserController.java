package com.persona.user.controller;

import com.persona.user.dto.CreateUserRequest;
import com.persona.user.dto.UpdateProfileRequest;
import com.persona.user.dto.UserResponse;
import com.persona.user.model.User;
import com.persona.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * The web edge of the user domain.
 *
 * <p>Everything in this file is <b>translation</b>. HTTP comes in, a method call
 * goes down, a Java value comes back, a status code goes out. There is not one
 * business decision here, and that is the property to protect — the moment an
 * {@code if} about what is <em>allowed</em> appears in this class, the rule it
 * encodes stops applying to anything that is not an HTTP request.
 *
 * <p>{@code @RestController} is {@code @Controller} + {@code @ResponseBody}: it
 * makes "the return value IS the response body, serialise it" the default for
 * every method, instead of "the return value is the name of a template to
 * render".
 *
 * <p><b>And here the two architectures genuinely diverge.</b> In the monolith,
 * Day-09 adds a second, plain {@code @Controller} beside this one, serving the
 * Bootstrap pages from the same process. This service will never get one. It
 * speaks JSON and nothing else — the HTML is somebody else's job, reached through
 * the gateway on Day-17.
 *
 * <p>That is the real difference between the two, and it is worth noticing that
 * it shows up in what a class is <em>allowed to grow into</em>, not in the code
 * below. Every line of the four endpoints is identical across both projects. A
 * microservice is not a different way of writing a controller; it is a different
 * answer to the question of what else may live next to it.
 *
 * <p>{@code @RequestMapping("/users")} factors the shared prefix out of the four
 * methods below. Note the noun, and note the plural: {@code /users} is the
 * collection, {@code /users/{email}} is one member of it. No verb appears in any
 * path in this file — the verb is the HTTP method, which is the one part of the
 * request every proxy, cache and load balancer between here and the client
 * actually reads.
 */
@RestController
@RequestMapping("/users")
public class UserController {

    /**
     * Same constructor injection as {@link UserService}, for the same reasons:
     * final field, never half-built, and a failure at startup rather than at the
     * first request if the bean is missing.
     */
    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    /**
     * Sign up. {@code POST /users}.
     *
     * <p>POST because creating a user is neither safe nor idempotent: sending this
     * twice is supposed to be different from sending it once. That is exactly why
     * the browser shows "Confirm Form Resubmission" on refresh — it is refusing to
     * guess on your behalf.
     *
     * <p><b>201, not 200.</b> 201 Created is the only status that means "a new
     * resource now exists", and it carries an obligation: the {@code Location}
     * header naming where that resource lives. This is the moment the server hands
     * identity back to the client, and it is what lets a client follow up without
     * having to construct the URL itself.
     *
     * <p><b>Note what is NOT here:</b> no check for a duplicate email. It is
     * tempting — {@code if (service.findByEmail(...).isPresent()) return 409;}
     * compiles, passes a test, and works in the browser, and it is the shape most
     * tutorials show. It is still wrong, because the rule then lives in the
     * controller, and next year's admin bulk-import and mobile endpoint do not go
     * through this method. The service throws; this class only decides what that
     * means in HTTP. See {@link com.persona.user.controller.GlobalExceptionHandler}.
     */
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        // Day-05: this method no longer builds the User.
        //
        // It used to call `new User(...)` and hand the result to
        // service.register(User). That looked like the controller doing
        // harmless assembly, and it was the wrong place for two reasons:
        //
        //   1. The service could not tell a request-built User from a
        //      database-loaded one, so it could not know whether the password
        //      needed hashing. Taking the DTO makes that unambiguous.
        //   2. Any future caller that is not an HTTP request — an admin
        //      import, a scheduler — would have had to remember to hash. Now
        //      there is nothing to remember, because register() owns it.
        //
        // What remains here is translation, which is all this class ever does.
        User saved = service.register(request);

        URI location = URI.create("/users/" + saved.getEmail());
        return ResponseEntity.created(location).body(UserResponse.from(saved));
    }

    /**
     * Edit a profile. {@code PUT /users/{email}}. <b>Day-05.</b>
     *
     * <p><b>PUT, not POST</b>, because this replaces the complete editable state
     * of an existing resource and is idempotent: sending it twice leaves the
     * server exactly as sending it once did. POST is for "create a thing",
     * which is why it is neither safe nor idempotent and why the browser warns
     * before resubmitting one.
     *
     * <p><b>PUT, not PATCH</b>, because {@link UpdateProfileRequest} carries all
     * three editable fields rather than a subset. See that record for why PATCH
     * would cost more than it is worth here — and for the consequence that
     * omitting {@code image} from the body <em>clears</em> the image, which is
     * what "complete new state" means and is not a bug.
     *
     * <p><b>200, not 201 and not 204.</b> 201 would claim something was created.
     * 204 would be defensible, but returning the updated profile saves the
     * client a follow-up GET and lets it see server-derived fields — most
     * visibly {@code username}, which changes when the name does and which the
     * client cannot compute for itself without duplicating the rule.
     *
     * <p>The email comes from the <b>path</b>, not the body, and the body has no
     * email field to disagree with it. A body that could carry a second,
     * different email would raise the question of which one wins, and every
     * answer to that question is a vulnerability in some reading.
     */
    @PutMapping("/{email}")
    public UserResponse updateProfile(@PathVariable String email,
                                      @Valid @RequestBody UpdateProfileRequest request) {
        return UserResponse.from(service.updateProfile(email, request));
    }

    /**
     * The caller's own profile. {@code GET /users/me}. <b>Day-06.</b>
     *
     * <p><b>This exists because {@code GET /users/{email}} is the wrong shape
     * for "show me my profile".</b> A client that must name itself in the URL
     * has to know its own email, and — more importantly — nothing about that
     * URL stops it naming someone <em>else's</em>. The identity here comes
     * from the verified token instead, via the {@link java.security.Principal}
     * that {@link com.persona.user.config.JwtAuthenticationFilter} populated.
     * It cannot be spoofed by editing a URL, because it was never in the URL.
     *
     * <p>{@code Principal} rather than {@code @AuthenticationPrincipal}: it is
     * a plain Servlet API type, so this signature does not tie the controller
     * to Spring Security's classes.
     */
    @GetMapping("/me")
    public UserResponse me(java.security.Principal principal) {
        return UserResponse.from(service.getByEmail(principal.getName()));
    }

    /**
     * Every user. {@code GET /users}.
     *
     * <p>With no users registered this returns {@code 200 []}, not 404. The
     * collection exists and happens to be empty — that is a successful answer to a
     * well-formed question. 404 would claim {@code /users} itself is not a thing.
     *
     * <p>These are the HTTP equivalents of {@code Optional.empty()} versus
     * {@code UserNotFoundException}: <b>empty is not an error.</b>
     *
     * <p>Returns a plain {@code List} rather than {@code ResponseEntity}. When the
     * status is 200 and there are no custom headers, the object alone says
     * everything; {@code ResponseEntity} earns its verbosity only when you need the
     * start line or the headers, as {@link #create} does.
     */
    @GetMapping
    public List<UserResponse> findAll() {
        return service.findAll().stream()
                .map(UserResponse::from)
                .toList();
    }

    /**
     * One user. {@code GET /users/{email}}.
     *
     * <p>{@code @PathVariable} binds the {@code {email}} segment of the path. Path
     * variables are required by their nature — without one there is no path to
     * match, so there is no "missing" case to handle. That is the difference from
     * {@code @RequestParam}, which reads the query string and is optional by
     * nature. The rule: the path says <em>which resource</em>; query parameters say
     * <em>how you want the collection arranged</em>, and they must never be the
     * thing that selects a different resource.
     *
     * <p>Calls {@code getByEmail}, the throwing variant, rather than unwrapping an
     * Optional here. A caller asking for a specific user by URL cannot continue
     * without them, so absence genuinely is the exceptional case — and translating
     * it is a job this class already does, once, below.
     */
    @GetMapping("/{email}")
    public UserResponse findByEmail(@PathVariable String email) {
        return UserResponse.from(service.getByEmail(email));
    }

    /**
     * Remove a user. {@code DELETE /users/{email}}.
     *
     * <p>DELETE is not safe (it changes state) but it <b>is</b> idempotent: deleting
     * the same user five times leaves the server in the state one delete would.
     * Idempotency is a claim about server state, not about the response — the first
     * call here returns 204 and the second returns 404, and that is still
     * idempotent.
     *
     * <p>That property is what makes a retry safe, and retries are not hypothetical:
     * networks lose <em>responses</em>, not only requests, so a client that gets no
     * answer cannot know whether the work happened. Idempotent means it does not
     * need to know.
     *
     * <p>{@code 204 No Content} is a literal promise that the body is empty — so the
     * method returns {@code ResponseEntity<Void>} and sends nothing. Returning 204
     * with a body is a protocol violation that some clients silently discard and
     * others choke on.
     */
    @org.springframework.security.access.prepost.PreAuthorize(
            "#email == authentication.name or hasRole('ADMIN')")
    @DeleteMapping("/{email}")
    public ResponseEntity<Void> delete(@PathVariable String email) {
        service.deleteByEmail(email);
        return ResponseEntity.noContent().build();
    }

    // =================================================================
    //  Why the rule above is an annotation and not an `if`. Day-06.
    //
    //  "You may delete yourself, or anyone if you are an admin" cannot be
    //  expressed in SecurityConfig, because the URL alone does not contain
    //  the answer — DELETE /users/a@b.com is allowed or forbidden depending
    //  on WHO is asking. SecurityConfig matches on method and path; this
    //  needs to compare a path variable against the authenticated principal.
    //
    //  @PreAuthorize runs before the method body. `#email` refers to the
    //  parameter by name, `authentication.name` is the principal the JWT
    //  filter set, and hasRole('ADMIN') reads the authority it granted —
    //  stored as ROLE_ADMIN, because hasRole adds the prefix (see
    //  JwtAuthenticationFilter.authorities for that trap in full).
    //
    //  One of the few places a rule legitimately lives in the controller
    //  rather than the service, and the reason is narrow: it is a rule about
    //  the CALLER, not about the data. UserService has never known who is
    //  calling it.
    //
    //  Worth knowing: @PreAuthorize does nothing without @EnableMethodSecurity,
    //  which is on SecurityConfig. Annotation present, feature off, no
    //  warning — this project's recurring failure mode, and the reason the
    //  verification script tests a forbidden delete rather than trusting the
    //  annotation to be wired.
    // =================================================================

    // =================================================================
    //  Exception translation MOVED OUT. Day-05. See GlobalExceptionHandler.
    //
    //  The Day-02 comment here said option 3 (@RestControllerAdvice) should
    //  wait until it was "a genuine, visible improvement rather than
    //  architecture applied in advance". Day-05 made it genuine:
    //  MethodArgumentNotValidException is a FRAMEWORK exception with one
    //  correct response everywhere, so handling it per-controller is
    //  duplication at N=1.
    //
    //  In a microservice there is a second reason the monolith does not have.
    //  These status codes are this service's PUBLIC CONTRACT — an exception
    //  cannot cross the wire, so a caller only ever sees the status and the
    //  body. Scattering that contract across controllers means no single file
    //  answers "what can this service return?", which is the first question
    //  anyone integrating with it will ask.
    // =================================================================
}
