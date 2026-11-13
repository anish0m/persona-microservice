package com.persona.user.service;

import com.persona.user.exception.DuplicateRequestException;
import com.persona.user.exception.RequestInProgressException;
import com.persona.user.repository.IdempotencyRecord;
import com.persona.user.repository.IdempotencyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs an operation at most once per idempotency key.
 *
 * <p><b>The governing constraint, and everything else follows from it: the key
 * record must outlive a rollback of the work.</b>
 *
 * <p>The tempting design is one transaction around the claim and the work
 * together. It cannot work, and the reason is worth stating as a sentence rather
 * than a diagram: <em>the act of recording "this failed" is undone by the
 * failure</em>. If the work rolls back, the claim rolls back with it, the row
 * vanishes, and the retry does the whole thing again — which is fine when the
 * failure was transient and catastrophic when it was not, because the client is
 * now retrying a request that will fail identically forever while the server does
 * the work every time.
 *
 * <p>So the key's lifecycle and the business transaction's lifecycle are
 * different, and the shape is <b>two transactions</b>:
 *
 * <pre>
 *   TX 1   claim the key (IN_PROGRESS)   -- COMMITS
 *   ----   do the work                   -- its own transaction, may roll back
 *   TX 2   record the outcome            -- COMMITS
 * </pre>
 *
 * <p><b>Why TX 1 must commit before the work starts, and not merely begin.</b> A
 * uniqueness constraint arbitrates between committed rows and uncommitted ones —
 * the second inserter blocks until the first transaction ends, then either fails
 * or succeeds depending on what the first did. If the claim stayed open for the
 * duration of the work, every duplicate would sit there <em>blocked</em>, holding
 * a connection, for as long as the original takes. Committing the claim first
 * turns a lock-wait into an immediate answer.
 *
 * <p><b>Why {@code REQUIRES_NEW} and the proxy.</b> {@link #claim} and
 * {@link #settle} are annotated {@code REQUIRES_NEW} so that each suspends
 * whatever transaction is in progress and commits independently. Two traps
 * attach to that, and both are silent:
 *
 * <ul>
 *   <li>It only works through a <b>real proxy hop</b>. A {@code this.claim(...)}
 *       from inside this class goes straight to the method body, the interceptor
 *       never runs, and the three transactions collapse into one — restoring
 *       exactly the design that cannot work, with no error anywhere. That is why
 *       {@link #execute} is the only public entry point and why it calls through
 *       {@code self}.</li>
 *   <li>A suspended transaction <b>keeps its connection</b> while the new one
 *       takes a second. Nesting these doubles connection usage and a pool that
 *       looked generous starts timing out under load.</li>
 * </ul>
 *
 * <p><b>Why a separate class rather than methods on {@code UserService}.</b>
 * {@code UserService}'s own javadoc already says a self-reference is "more
 * structure than the problem deserves" for one retry and "would be the better
 * shape" at two or three. This is the second, and it is also not about users:
 * nothing below is specific to signup, so the next endpoint that needs
 * idempotency wraps a different {@link Supplier} and changes nothing here.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;

    /**
     * This bean through its transactional proxy. Same device, same reason, and
     * the same default as {@code UserService}: {@code this}, so a plain
     * {@code new IdempotencyService(fake)} still works in a unit test with no
     * context — without transactions, which those tests are not asking for.
     */
    private IdempotencyService self;

    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = repository;
        this.self = this;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSelf(@org.springframework.context.annotation.Lazy IdempotencyService self) {
        this.self = self;
    }

    /**
     * The result of running — or replaying — an idempotent operation.
     *
     * <p>{@code replayed} is carried separately because the caller needs to know,
     * even though the body is identical either way. It is what lets the HTTP layer
     * restore the original status code rather than inventing a new one, and it is
     * the only honest way to observe how often retries are actually arriving.
     */
    public record Outcome<T>(T value, boolean replayed, Integer replayedStatus,
                             String replayedBody) {

        static <T> Outcome<T> fresh(T value) {
            return new Outcome<>(value, false, null, null);
        }

        static <T> Outcome<T> replay(IdempotencyRecord record) {
            return new Outcome<>(null, true, record.responseStatus(), record.responseBody());
        }
    }

    /**
     * Claims {@code key}, runs {@code work} exactly once, and replays the stored
     * response on any later attempt.
     *
     * <p><b>The order of the three branches below is the whole protocol.</b>
     *
     * <ol>
     *   <li><b>Fingerprint mismatch</b> is checked first, before anything is
     *       replayed. Same key with a different body is a client bug — a reused
     *       key — and replaying the first response for it would silently discard
     *       the second request. Answering 422 is the only option that does not
     *       lose data.</li>
     *   <li><b>Still {@code IN_PROGRESS}</b> is a 409, not a wait and not a
     *       re-run. There is no stored response to replay, so the only
     *       alternatives are to redo the work — which is the duplicate the whole
     *       mechanism exists to prevent — or to block, holding a thread for an
     *       unbounded time on behalf of a client that has already shown it is
     *       impatient. "Not right now, ask again" is the truthful answer.</li>
     *   <li><b>Replayable</b> returns the stored response, including a stored
     *       failure. A failure is an answer, and the client that never received
     *       it deserves to receive it now.</li>
     * </ol>
     *
     * <p><b>And the claim is outside any retry of the work.</b> If a caller
     * retries by calling this method again, it meets its own claim and the server
     * mistakes its own retry for the client's duplicate. Retry logic belongs
     * inside {@code work}, under one claim.
     *
     * @param classifier decides what to do with a failure: return a
     *                   {@link ResponseCapture} to store and replay it, or
     *                   {@code null} to release the key so the client may try
     *                   again. The caller supplies this rather than the
     *                   exception declaring it, because "is this final?" is a
     *                   domain question while "what does it look like as a
     *                   response?" is an HTTP one, and this class must not force
     *                   the exception types to know about status codes.
     */
    public <T> Outcome<T> execute(String scope, String key, String requestBody,
                                  Supplier<ResponseCapture<T>> work,
                                  Function<RuntimeException, ResponseCapture<T>> classifier) {

        String fingerprint = fingerprint(requestBody);

        Optional<IdempotencyRecord> existing = self.claim(scope, key, fingerprint);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();

            if (!record.requestFingerprint().equals(fingerprint)) {
                throw new DuplicateRequestException(key);
            }
            if (IdempotencyRecord.IN_PROGRESS.equals(record.status())) {
                throw new RequestInProgressException(key);
            }
            return Outcome.replay(record);
        }

        ResponseCapture<T> captured;
        try {
            captured = work.get();
        } catch (RuntimeException businessFailure) {
            // Two kinds of failure reach here and they are NOT interchangeable.
            //
            // A business outcome — "that email is taken" — is an answer, and it
            // is recorded so the retry gets the same answer rather than running
            // the work again. Anything else is an accident the client should be
            // allowed to retry past, so the claim is released rather than
            // settled; leaving it IN_PROGRESS would brick the key forever.
            //
            // The classifier draws that line, and note that the DEFAULT here is
            // release: a null means "I do not recognise this", and the safe
            // reading of an unrecognised failure is that it might succeed later.
            // Guessing the other way would store an accident as a verdict.
            //
            // The settle() path runs in its OWN transaction, which is the only
            // reason it can write at all: the work's transaction is already
            // rolling back, and a write that joined it would roll back too.
            ResponseCapture<T> failure = classifier.apply(businessFailure);
            if (failure != null) {
                self.settle(scope, key, IdempotencyRecord.FAILED,
                        failure.status(), failure.body());
            } else {
                self.release(scope, key);
            }
            throw businessFailure;
        }

        // Recorded only after work.get() has RETURNED — which, because work is a
        // call through someone else's transactional proxy, means after it has
        // committed. Writing COMPLETED from inside that method would claim
        // success while the commit could still fail.
        self.settle(scope, key, IdempotencyRecord.COMPLETED,
                captured.status(), captured.body());

        return Outcome.fresh(captured.value());
    }

    /** What the work produced, plus how it should be replayed later. */
    public record ResponseCapture<T>(T value, int status, String body) {}


    /** {@code REQUIRES_NEW}: committed before the work begins. See the class note. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencyRecord> claim(String scope, String key, String fingerprint) {
        return repository.claim(scope, key, fingerprint);
    }

    /** {@code REQUIRES_NEW}: must commit even though the work's transaction did not. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(String scope, String key, String status, int httpStatus, String body) {
        repository.complete(scope, key, status, httpStatus, body);
    }

    /** {@code REQUIRES_NEW}: frees a key whose work died without producing an answer. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String scope, String key) {
        repository.release(scope, key);
    }

    /**
     * SHA-256 of the request body, hex encoded.
     *
     * <p>Note what this is <em>not</em> used for. It never decides whether two
     * requests are the same — the key does that. Hashing content to deduplicate
     * would swallow a real second payment: two identical transfers a minute
     * apart are two intents that happen to serialise to the same bytes. The hash
     * only answers "is this the same body the key was first used with?", which is
     * a question about client correctness.
     *
     * <p>SHA-256 rather than {@code String.hashCode()}: a 32-bit hash collides by
     * accident at a few tens of thousands of values, and a collision here means
     * accepting a genuinely different body as a replay.
     */
    static String fingerprint(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every Java platform implementation.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
