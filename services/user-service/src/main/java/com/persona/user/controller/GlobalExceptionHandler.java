package com.persona.user.controller;

import com.persona.user.exception.DuplicateEmailException;
import com.persona.user.exception.UserNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One routing table from exception type to HTTP status, for every controller.
 *
 * <h2>Why these moved out of UserController</h2>
 *
 * <p>Three of these handlers lived inside {@link UserController} from Day-02,
 * and that was the right call at the time: there was one controller, and
 * locality beats indirection. The Day-02 comment said the move to a global
 * advice should happen when it became "a genuine, visible improvement rather
 * than architecture applied in advance".
 *
 * <p>Day-05 made it genuine. {@link MethodArgumentNotValidException} is a
 * <b>framework</b> exception with exactly one correct response everywhere in the
 * application. Handling that per-controller is duplication at N=1 — a second
 * controller would not create the problem, only make it obvious.
 *
 * <p>{@code @RestControllerAdvice} is {@code @ControllerAdvice} +
 * {@code @ResponseBody}: a registry of {@code @ExceptionHandler} methods that
 * applies to every controller in the application. Spring catches anything
 * thrown below the web layer and dispatches on the exception <b>type</b>.
 *
 * <p>Which is only possible because Day-01 built real exception classes. Had
 * the service thrown {@code new RuntimeException("email taken")}, there would be
 * nothing to dispatch on but English text, and you cannot route on a sentence.
 *
 * <h2>A 500 is a confession</h2>
 *
 * <p>4xx means the caller did something wrong and can fix it. 5xx means the
 * server did, and only the server can. <b>Every unhandled exception becomes a
 * 500</b>, so an incomplete list here does not merely produce a wrong number: it
 * misattributes blame, and buries real defects among false ones. On Day-19
 * somebody is paged on a 500 rate, and validation errors inside that number make
 * the signal useless.
 *
 * <p>So this class is best understood not as error handling but as a
 * <b>classification of blame</b>, whose default answer is "my fault".
 *
 * <h2>Why ProblemDetail</h2>
 *
 * <p>{@link ProblemDetail} is Spring's implementation of RFC 9457, the standard
 * shape for an HTTP error body. It is built in, so there is no dependency and no
 * hand-maintained {@code ErrorResponse} class to drift.
 *
 * <p>Being standardised, clients and gateways understand it without reading any
 * documentation — which matters on Day-17, when an API Gateway sits in front of
 * five services and five hand-rolled error shapes would mean five parsers.
 *
 * <h2>What an error must never contain</h2>
 *
 * <pre>
 *   GOOD  "Email khi0ne&#64;example.com is already registered"
 *   BAD   "duplicate key value violates unique constraint \"users_email_key\""
 *   BAD   the stack trace, the SQL, or the hash
 * </pre>
 *
 * <p>The second leaks the schema, the table names and the constraint names to
 * anyone who can POST twice. <b>Log the cause, return the meaning</b> — they are
 * different audiences, one authenticated by having server access and the other
 * being the internet. Day-02 stopped the password leaving through the DTO; an
 * exception message is the other door out, and it bypasses the DTO entirely.
 *
 * <h2>Why this class matters more in a microservice</h2>
 *
 * <p>In the monolith an exception travels up the stack with its type intact and
 * the compiler helping. Here, <b>an exception cannot cross the wire.</b> The
 * moment a response leaves this process, {@link DuplicateEmailException} has
 * been flattened into a status code and a JSON body, and whatever called this
 * service must reconstruct the meaning from those alone.
 *
 * <p>Which promotes this file from a convenience to <b>part of the service's
 * public contract</b>. The status codes and the body shape are as much an API
 * as the URL paths are, and changing a 409 to a 422 breaks callers exactly as
 * surely as renaming an endpoint would — but silently, because nothing fails to
 * compile anywhere.
 *
 * <p>That is also the strongest argument for {@link ProblemDetail} over a
 * hand-rolled shape. An API Gateway, a retry policy and another team's client
 * can all read RFC 9457 without being told what persona invented. Five services
 * with five bespoke error shapes is five parsers, and the fifth one is written
 * in a hurry by somebody who has never read this file.
 *
 * <p><b>The 4xx/5xx split is also a routing decision here, not only a label.</b>
 * A caller's retry logic keys off it: 5xx is worth retrying because the server
 * might recover, 4xx never is because the request will fail identically forever.
 * Misclassify a validation error as a 500 and every caller in the system
 * politely retries a request that cannot ever succeed — which is how one bad
 * input becomes a traffic spike.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 400 — bean validation rejected the request body.
     *
     * <p>This is the handler Day-05 exists for, and the reason it is not a
     * one-liner is that {@code @Valid} collects <b>every</b> violation before
     * failing. Six bad fields arrive as one exception carrying six failures:
     *
     * <pre>
     * MethodArgumentNotValidException
     *   +- BindingResult
     *        +- FieldError  field: "firstName"  message: "First name is required"
     *        +- FieldError  field: "email"      message: "must be a valid email address"
     *        +- FieldError  field: "password"   message: "must be between 8 and 72 characters"
     * </pre>
     *
     * <p>A handler that returned a status and a single message string would have
     * collected six answers and thrown away five. So the structure is preserved
     * rather than flattened, and the response carries a field-to-message map:
     *
     * <pre>
     * { "status": 400,
     *   "title":  "Bad Request",
     *   "detail": "Request validation failed",
     *   "errors": { "firstName": "First name is required",
     *               "email":     "must be a valid email address" } }
     * </pre>
     *
     * <p>A form can highlight three fields from a map. It can only print a
     * sentence. <b>An error is data</b>; the moment it is concatenated into
     * prose, every consumer has to parse English back out of it — which is the
     * same reason {@code DuplicateEmailException} was given a {@code getEmail()}
     * accessor in slice 4 rather than only a message.
     *
     * <p>A {@code LinkedHashMap} rather than {@code Map.of} for two reasons that
     * both matter: insertion order is preserved, so the response lists fields in
     * declaration order rather than a hash order that changes between runs; and
     * {@code Map.of} throws on a duplicate key, which happens the moment one
     * field carries two failing constraints — {@code @NotBlank} and
     * {@code @Size} on the same field is not hypothetical, it is what
     * {@code password} has. The merge function below keeps the first message
     * rather than letting the handler itself throw while reporting an error.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fieldError ->
                errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Bad Request");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * 400 — bean validation rejected a path variable or request parameter.
     *
     * <p><b>A different exception type for the same kind of failure</b>, and this
     * is the trap worth knowing. Constraints on a {@code @RequestBody} produce
     * {@link MethodArgumentNotValidException}; constraints on a
     * {@code @PathVariable} or {@code @RequestParam} produce
     * {@link ConstraintViolationException}, from a different package.
     *
     * <p>The second is unhandled by default, so without this method a bad path
     * variable returns <b>500</b> — the server confessing to the caller's
     * mistake. persona does not yet annotate any path variable, so this handler
     * currently catches nothing. It is written anyway, because the failure it
     * prevents is invisible until it happens and then looks like a server bug.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getConstraintViolations().forEach(violation ->
                errors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Bad Request");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * 400 — the body was not valid JSON, or a field had the wrong JSON type.
     *
     * <p>This fires <em>before</em> validation, because there is nothing to
     * validate until Jackson has built the object. Without it, a trailing comma
     * in a request body is a 500.
     *
     * <p>The detail message is deliberately generic. Jackson's own message names
     * the target Java class, the field and often the line and column of the
     * parse failure — useful in a log, and a free description of the internal
     * object model to anyone who sends malformed JSON on purpose.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Malformed request body");
        problem.setTitle("Bad Request");
        return problem;
    }

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
     * <p>The email is attached as a structured property as well as appearing in
     * the message, because {@code DuplicateEmailException} carries it as data.
     * A client that wants to highlight the email field should not have to
     * regex it back out of a sentence.
     */
    @ExceptionHandler(DuplicateEmailException.class)
    public ProblemDetail handleDuplicate(DuplicateEmailException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Conflict");
        problem.setProperty("email", e.getEmail());
        return problem;
    }

    /** 404 Not Found — the resource named by the URL does not exist. */
    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleNotFound(UserNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Not Found");
        return problem;
    }

    /**
     * 400 — the model's own validation rejected the input.
     *
     * <p>Reaching this handler now means something got past the DTO constraints,
     * which is worth thinking about rather than treating as routine. Either a
     * field has a model rule the DTO does not mirror, or a non-HTTP caller built
     * a {@code User} directly. Both are legitimate; both are also the signal
     * that the two validation layers have drifted apart.
     *
     * <p>Note what is <em>not</em> done here: returning {@code 200} with
     * {@code {"success": false}} in the body, which re-invents the status code
     * badly in a place no proxy, cache or monitoring tool will ever look.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadInput(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Bad Request");
        return problem;
    }
}
