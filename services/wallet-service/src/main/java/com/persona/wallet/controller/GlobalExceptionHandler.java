package com.persona.wallet.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.persona.wallet.exception.InsufficientFundsException;
import com.persona.wallet.exception.WalletNotActiveException;
import com.persona.wallet.exception.WalletNotFoundException;

/**
 * Turns exceptions into RFC 9457 {@code ProblemDetail} responses. Day-07.
 *
 * <p>A second copy of a class user-service already has, and that duplication is
 * the deliberate kind — see {@code JwtVerifier.hexToBytes} for the rule. A
 * shared {@code persona-common} jar holding this handler would re-couple the
 * two services at build time, so that changing an error shape for one forces a
 * redeploy of the other.
 *
 * <p>Worth being precise about what is actually shared here: <b>the error
 * FORMAT is a contract, the error CODE is not.</b> Both services emit
 * {@code application/problem+json} with the same field names, because clients
 * parse that. Which exceptions map to which status is each service's own
 * business, and the two lists below differ accordingly — this one has no
 * {@code DuplicateEmailException} and no {@code InvalidCredentialsException},
 * because this service has no emails and issues no credentials.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Logs the one error that should not be happening.
     *
     * <p>Handlers here generally do not log — a 400 or a 409 is a normal
     * outcome and logging each one turns the log into a transcript of user
     * typos. {@link WalletNotFoundException} is the exception, for a reason
     * specific to this build; see its handler.
     */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 400 — bean validation rejected the request body.
     *
     * <p>Every violation is collected and returned at once, rather than the
     * first one. A client fixing one field at a time across several round
     * trips is a worse experience than one list.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Bad Request");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * 400 — the body was not parseable at all.
     *
     * <p>Deliberately does NOT include the parser's own message. It names
     * internal class and field names, which is free reconnaissance for anyone
     * probing the service.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Malformed request body");
        problem.setTitle("Bad Request");
        return problem;
    }

    /**
     * 404 — the user has no wallet.
     *
     * <p><b>This means something different here than in the monolith, and the
     * log line is where that difference is recorded.</b>
     *
     * <p>In the monolith this is nearly impossible: registration creates the
     * user and the wallet in one transaction. Seeing it there is the signature
     * of a broken {@code @Transactional}.
     *
     * <p>Here it is an expected outcome. There is no transaction spanning the
     * two services, so between user-service committing a user and this service
     * opening their wallet there is a real window in which the user exists and
     * the wallet does not. The 404 is correct and the caller should retry.
     *
     * <p>Logged at INFO rather than WARN for exactly that reason — it is not an
     * alarm here, but it is still the thing to grep for when someone asks why a
     * user cannot see their balance.
     */
    @ExceptionHandler(WalletNotFoundException.class)
    public ProblemDetail handleWalletNotFound(WalletNotFoundException e) {
        log.info("no wallet for user {} — expected transiently after signup, "
                + "a persistent occurrence means the create call never succeeded",
                e.getUserId());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Not Found");
        return problem;
    }

    /**
     * 409 — the wallet exists and is refusing.
     *
     * <p>Not 400: the request was well-formed and resending it unchanged would
     * succeed once the wallet is unfrozen. Not 500: nothing failed. 409
     * Conflict is precisely "well-formed request, conflicts with current
     * resource state".
     *
     * <p>{@code status} is attached so a client can branch without parsing
     * prose. Safe because this response only ever reaches the wallet's owner or
     * an admin.
     */
    @ExceptionHandler(WalletNotActiveException.class)
    public ProblemDetail handleWalletNotActive(WalletNotActiveException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Wallet Not Active");
        problem.setProperty("status", e.getStatus().name());
        return problem;
    }

    /**
     * 409 — not enough money.
     *
     * <p>The balance is named in the message but deliberately NOT attached as a
     * machine-readable property: an amount held is not something an error needs
     * to broadcast to every intermediary that logs responses.
     */
    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(InsufficientFundsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Insufficient Funds");
        return problem;
    }

    /**
     * 409 — an operation the object's state forbids.
     *
     * <p>Note the asymmetry with the handler below: {@code IllegalArgument} is
     * 400 because the INPUT was wrong; {@code IllegalState} is 409 because the
     * input was fine and the STATE was not. Two JDK exceptions that look
     * interchangeable and mean genuinely different things.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Conflict");
        return problem;
    }

    /**
     * 400 — the model's own validation rejected the input.
     *
     * <p>Reaching this handler means something got past the DTO constraints,
     * which is worth noticing rather than treating as routine: either a model
     * rule has no DTO mirror, or a non-HTTP caller built the object directly.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadInput(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Bad Request");
        return problem;
    }
}
