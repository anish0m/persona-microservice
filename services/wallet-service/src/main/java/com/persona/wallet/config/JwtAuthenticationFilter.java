package com.persona.wallet.config;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a verified JWT into a Spring Security authentication. Day-07.
 *
 * <h2>The one line that is missing, and why it matters most</h2>
 *
 * <p>user-service's filter ends with:
 *
 * <pre>
 *     repository.findByEmail(email).ifPresent(user -&gt; { ...build authentication... });
 * </pre>
 *
 * <p><b>That line cannot exist here.</b> There is no users table in
 * {@code persona_wallet} and no {@code UserRepository} on this classpath, so
 * the authentication is built <em>entirely from the token's claims</em> — the
 * email from {@code sub}, the id from {@code uid}, the role from {@code role}.
 *
 * <p>Two consequences follow, and both are architectural rather than cosmetic:
 *
 * <ul>
 *   <li><b>No existence check.</b> user-service's filter leaves the request
 *       anonymous if the email in a perfectly valid token matches no row —
 *       a deleted user's unexpired token stops working immediately. Here, a
 *       deleted user's token keeps working until it expires, because nothing
 *       in this service can discover the deletion. The {@code ifPresent} was
 *       doing real security work that is simply unavailable.</li>
 *   <li><b>The token is the source of truth.</b> Whatever role it claims is
 *       the role enforced. Demote an admin and this service honours the old
 *       role for up to one token lifetime.</li>
 * </ul>
 *
 * <p>Neither is fixable without either a call to user-service on every request
 * — which would make this service's availability depend on that one's, undoing
 * the split — or shared state such as Redis. Both are later days. What matters
 * today is that <b>the split moved a security check from "enforced by a
 * database read" to "trusted from a signed claim", and that is a downgrade
 * accepted on purpose.</b>
 *
 * <h2>Three traps inherited from Day-06, unchanged by the split</h2>
 *
 * <ul>
 *   <li><b>This filter populates, it never rejects.</b> A missing, malformed
 *       or invalid token means "carry on without an authentication", and the
 *       401 is produced later by the authentication entry point. A filter that
 *       writes its own 401 bypasses the configured error handling and produces
 *       a different response shape for the same condition.</li>
 *   <li><b>The guard is {@code instanceof AnonymousAuthenticationToken}, not
 *       {@code != null}.</b> {@code getAuthentication()} is almost never null —
 *       Spring's anonymous filter has already put an
 *       {@code AnonymousAuthenticationToken} there. A {@code != null} check
 *       therefore always passes, this filter always returns early, and <b>no
 *       request is ever authenticated</b> while every test that does not use
 *       the real chain stays green.</li>
 *   <li><b>The {@code ROLE_} prefix</b> is added here, not stored. The claim
 *       says {@code ADMIN}; {@code hasRole('ADMIN')} looks for an authority
 *       literally named {@code ROLE_ADMIN}. Store the prefix as well and you
 *       get {@code ROLE_ROLE_ADMIN}, which fails silently as a 403.</li>
 * </ul>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    /**
     * The request attribute under which the authenticated user's id is
     * published for controllers to read.
     *
     * <p>user-service's controllers take a {@code java.security.Principal} and
     * get an email, which is all they need — they can look the rest up. This
     * service needs the <b>id</b>, which is not what {@code Principal.getName()}
     * returns and cannot be derived from it without a call to user-service.
     *
     * <p>A request attribute rather than a custom {@code Principal}
     * implementation: it is the smaller change, it does not require touching
     * Spring Security's type hierarchy, and it keeps the id visible to anything
     * that can see the request. The constant lives here, next to the only code
     * that sets it.
     */
    public static final String USER_ID_ATTRIBUTE = "persona.userId";

    private final JwtVerifier jwtVerifier;

    /**
     * One dependency, where user-service's filter has three
     * ({@code JwtService}, {@code UserRepository}, {@code TokenDenyList}).
     *
     * <p>Not simplicity — <b>capability</b>. The repository is absent because
     * this service has no users table; the deny list is absent because logout
     * is user-service's concern and this service cannot see its state. Each
     * missing dependency is a thing this filter cannot check.
     */
    public JwtAuthenticationFilter(JwtVerifier jwtVerifier) {
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(PREFIX.length());

        // Pass through rather than reject. The entry point produces the 401.
        if (!jwtVerifier.isValid(token)) {
            chain.doFilter(request, response);
            return;
        }

        // Anonymous IS an authentication — see the class comment. A `!= null`
        // check here would make every request anonymous forever.
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && !(existing instanceof AnonymousAuthenticationToken)) {
            chain.doFilter(request, response);
            return;
        }

        String email = jwtVerifier.extractEmail(token);
        Long userId = jwtVerifier.extractUserId(token);
        String role = jwtVerifier.extractRole(token);

        // A token that verifies but carries no uid is a token minted by an
        // older user-service, before Day-07 added the claim. Refusing to
        // authenticate it is the right call: this service cannot do anything
        // useful without an id, and guessing is worse than a 401.
        //
        // Worth noticing as a distributed-systems fact rather than a null
        // check: THE TWO SERVICES CAN BE RUNNING DIFFERENT VERSIONS. The
        // aggregator pom builds them together, which hides that they deploy
        // separately and that an old user-service can be live against a new
        // wallet-service for as long as a rollout takes.
        if (userId == null || role == null) {
            chain.doFilter(request, response);
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        email,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));

        authentication.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        // The id controllers will read. Set only on the authenticated path, so
        // its presence always implies a verified token.
        request.setAttribute(USER_ID_ATTRIBUTE, userId);

        chain.doFilter(request, response);
    }
}
