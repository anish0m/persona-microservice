package com.persona.user.controller;

import com.persona.user.dto.CreateUserRequest;
import com.persona.user.dto.UserResponse;
import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import com.persona.user.model.User;
import com.persona.user.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

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
     * means in HTTP. See {@link #handleDuplicate}.
     */
    @PostMapping
    public ResponseEntity<UserResponse> create(@RequestBody CreateUserRequest request) {
        // The service never sees a DTO. The web layer's types stop at this line;
        // below it, the application is transport-agnostic again.
        User saved = service.register(new User(
                request.email(),
                request.firstName(),
                request.lastName(),
                request.password()));

        URI location = URI.create("/users/" + saved.getEmail());
        return ResponseEntity.created(location).body(UserResponse.from(saved));
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
    @DeleteMapping("/{email}")
    public ResponseEntity<Void> delete(@PathVariable String email) {
        service.deleteByEmail(email);
        return ResponseEntity.noContent().build();
    }

    // =================================================================
    //  Exception translation.
    //
    //  These methods are a routing table: exception type in, status code
    //  out. Spring catches anything thrown below this class and dispatches
    //  on the TYPE.
    //
    //  Which is only possible because Day-01 built real exception classes.
    //  Had the service thrown `new RuntimeException("email taken")`, there
    //  would be nothing to dispatch on but the English text — and you
    //  cannot route on a sentence.
    //
    //  Three places this translation could live:
    //    1. try/catch in every method            -> N copies of the rule
    //    2. @ExceptionHandler here               -> one copy, this controller
    //    3. @RestControllerAdvice, app-wide      -> Day-09
    //
    //  Option 2 today, because there is exactly one controller and moving to
    //  option 3 is then a genuine, visible improvement rather than
    //  architecture applied in advance.
    // =================================================================

    /**
     * 409 Conflict. The request is well-formed and the rule is clear — the world
     * is simply not in the state the client assumed.
     *
     * <p>409 and 404 are opposites: "already exists" against "does not exist".
     *
     * <p>409 against 400 is the subtler line, and worth memorising as a sentence:
     * <b>400 says fix your request; 409 says your request is fine, the world
     * isn't.</b> Resending an identical 400 is pointless. Resending an identical
     * 409 might succeed tomorrow.
     *
     * <p>Without this method the exception escapes to Spring's default handler and
     * becomes a <b>500</b> — and 500 is a lie here. The 4xx/5xx split answers
     * "whose problem is this", and it is the line the entire alerting stack is built
     * on. Classify ordinary duplicate signups as server errors and the 5xx graph
     * spikes during normal use until nobody looks at it any more.
     */
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<Map<String, String>> handleDuplicate(DuplicateEmailException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage()));
    }

    /** 404 Not Found — the resource named by the URL does not exist. */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(UserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
    }

    /**
     * 400 Bad Request — the model's own validation rejected the input, so the
     * request itself is malformed and no amount of resending will help.
     *
     * <p>Note the body is a small JSON object holding the message, and nothing else.
     * Two failure modes are being avoided:
     *
     * <ul>
     *   <li>Returning {@code 200 {"success": false}} — re-inventing the status code,
     *       badly, inside the body, where no proxy, cache or monitoring tool will
     *       ever see it.</li>
     *   <li>Returning the stack trace. It reveals framework versions, package
     *       structure and often SQL. That is reconnaissance, handed over on
     *       request.</li>
     * </ul>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
    }
}
