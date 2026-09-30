package com.persona.user.config;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.persona.user.model.User;
import com.persona.user.repository.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads {@code Authorization: Bearer <token>}, verifies it, and tells Spring
 * Security who is calling. <b>Day-06.</b>
 *
 * <p>This is the piece that makes a stateless token behave like a session.
 * Every request arrives anonymous; this filter runs before the controller,
 * and if the token checks out it populates the {@code SecurityContext} for
 * the duration of that one request. Nothing is remembered afterwards — which
 * is the whole claim of "stateless", and why there is no session store
 * anywhere in this project.
 *
 * <h2>Why OncePerRequestFilter</h2>
 *
 * <p>A plain {@code Filter} can run more than once for a single HTTP request
 * — a {@code forward} to an error page, or an async dispatch, re-enters the
 * chain. Doing the work twice is wasteful here and genuinely wrong in filters
 * that mutate state. {@link OncePerRequestFilter} guarantees exactly one
 * invocation per request, which is what almost every custom filter actually
 * wants.
 *
 * <h2>What this filter deliberately does NOT do</h2>
 *
 * <p>It never rejects anything. A missing header, a garbage token, an expired
 * one — all leave the {@code SecurityContext} empty and call
 * {@code chain.doFilter} anyway. Deciding that an empty context is not good
 * enough for a particular URL is the job of the authorization rules in
 * {@link SecurityConfig}, and keeping the two separate is what lets
 * {@code POST /users} and {@code POST /auth/login} stay public without this
 * filter needing to know they exist.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository repository;
    private final TokenDenyList denyList;

    /**
     * Depends on {@link UserRepository} rather than {@code UserService}, and
     * the reason is a real one rather than taste: a filter that called
     * {@code UserService} would drag the whole business layer — and on a
     * future day its {@code @Transactional} semantics — into the servlet
     * chain, which runs outside any transaction. All this filter needs is a
     * lookup.
     */
    public JwtAuthenticationFilter(JwtService jwtService, UserRepository repository,
                                   TokenDenyList denyList) {
        this.jwtService = jwtService;
        this.repository = repository;
        this.denyList = denyList;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HEADER);

        // No header, or not a Bearer one: stay anonymous and move on. This is
        // the normal path for POST /users and POST /auth/login, which have no
        // token yet by definition.
        if (header == null || !header.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(PREFIX.length());

        // isValid FIRST, then extractEmail. The order is load-bearing:
        // extractEmail reads the payload, and the payload is only Base64 —
        // anyone can write whatever email they like into a token they forged.
        // What makes the claim trustworthy is the signature check, so nothing
        // derived from an unverified token may be acted on.
        if (!jwtService.isValid(token)) {
            chain.doFilter(request, response);
            return;
        }

        // Signature and expiry are fine, but the user logged out. This check
        // is the ONLY thing that makes logout real — the token itself is
        // still cryptographically perfect and will stay that way until `exp`.
        // See TokenDenyList for why this state has to exist and what it costs.
        if (denyList.isDenied(token)) {
            chain.doFilter(request, response);
            return;
        }

        String email = jwtService.extractEmail(token);

        // Already GENUINELY authenticated? Leave it alone. Re-populating a
        // real authentication is how a filter accidentally overrides a
        // stronger mechanism with a weaker one.
        //
        // THE AnonymousAuthenticationToken CHECK IS LOAD-BEARING. A plain
        // `!= null` here was a real bug: Spring's AnonymousAuthenticationFilter
        // puts an AnonymousAuthenticationToken in the context for every
        // unauthenticated request, so getAuthentication() is essentially never
        // null, this branch always returned early, and the real authentication
        // was never set. Anonymous IS an authentication as far as the type
        // system is concerned; it just carries no identity.
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && !(existing instanceof AnonymousAuthenticationToken)) {
            chain.doFilter(request, response);
            return;
        }

        // The token is valid, but the user may have been deleted since it was
        // issued — the token would still verify, because nothing about
        // deleting a row invalidates a signature. This lookup is what makes
        // "delete a user" take effect immediately rather than after `exp`.
        // It is also the one database hit per request that a stateless token
        // was supposed to avoid; the alternative (trusting the token alone)
        // is faster and means a deleted user keeps working for 15 minutes.
        repository.findByEmail(email).ifPresent(user -> {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            user.getEmail(),
                            // No credentials. The password is not in the token
                            // and must not be put here — this object ends up in
                            // the SecurityContext and, on some paths, in logs.
                            null,
                            authorities(user));

            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        });

        chain.doFilter(request, response);
    }

    /**
     * Turns the stored role into what {@code hasRole('ADMIN')} expects.
     *
     * <p><b>The {@code ROLE_} prefix is the trap of the day.</b> Spring
     * Security stores authorities as opaque strings, and {@code hasRole('X')}
     * is defined as "has the authority {@code ROLE_X}" — it adds the prefix
     * for you. So a database value of {@code "ADMIN"} must be stored here as
     * {@code "ROLE_ADMIN"}, or {@code hasRole('ADMIN')} silently never
     * matches: no error, no warning, just a 403 that looks like a
     * configuration problem. {@code hasAuthority('ADMIN')} would match the
     * unprefixed form — which is precisely why mixing the two functions in one
     * codebase produces bugs nobody can reproduce.
     */
    private static List<SimpleGrantedAuthority> authorities(User user) {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
    }
}
