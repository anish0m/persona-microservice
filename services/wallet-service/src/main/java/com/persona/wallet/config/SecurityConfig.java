package com.persona.wallet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The security chain for wallet-service. Day-07.
 *
 * <h2>Every endpoint requires authentication — there is no public route</h2>
 *
 * <p>user-service has two: {@code POST /users} (signup — there is no token
 * yet, by definition) and {@code POST /auth/login} (the endpoint that ISSUES
 * the token). Both exist because that service is where identity begins.
 *
 * <p>Nothing here is public. A wallet is only ever reachable by its owner or
 * an admin, and there is no bootstrapping problem to solve — by the time any
 * request arrives at this service, user-service has already minted a token.
 * So the configuration below is simply {@code anyRequest().authenticated()},
 * which is the <b>default-deny</b> arrangement: a route added tomorrow is
 * protected automatically, rather than being public until somebody remembers
 * to list it.
 *
 * <h2>No PasswordEncoder bean, and that is a boundary made visible</h2>
 *
 * <p>user-service's {@code SecurityConfig} declares one, because that service
 * hashes and verifies passwords. This service never sees a password, so
 * declaring a {@code PasswordEncoder} here would be an unused bean advertising
 * a capability that does not exist — and the day somebody needs one, the
 * question "why does wallet-service have a password encoder?" is the right
 * question to be forced to ask.
 *
 * <p>A service's bean list is a readable summary of what it is allowed to do.
 * Keeping it honest is free.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    /**
     * @see JwtAuthenticationFilter for why the filter populates but never
     *      rejects, and what the split removed from it
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // Safe to disable ONLY because authentication is a header and
                // never a cookie. A browser attaches cookies to cross-site
                // requests automatically; it does not attach an Authorization
                // header. Re-enable the moment a session cookie appears.
                .csrf(csrf -> csrf.disable())

                // No server-side session: the token carries everything. This
                // is also what lets wallet-service be scaled to N instances
                // behind a load balancer with no sticky sessions and no
                // shared session store.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // DAY-06'S HARDEST-WON LINE, and it is not optional
                        // here either.
                        //
                        // When a request is rejected by @PreAuthorize, Spring
                        // Boot FORWARDS to /error to render the body. That
                        // forward re-enters this chain as a new dispatch,
                        // carrying no Authorization header — so it is
                        // anonymous, the entry point fires, and a correct 403
                        // is overwritten with a 401.
                        //
                        // The symptom is maddening precisely because the
                        // authorization worked: the method never ran. Only the
                        // status code lies.
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR,
                                jakarta.servlet.DispatcherType.FORWARD).permitAll()

                        // Default deny. No permitAll above it, deliberately.
                        .anyRequest().authenticated())

                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)

                .exceptionHandling(ex -> ex
                        // Without an explicit entry point, a request with no
                        // token gets 403 rather than 401 — because the default
                        // entry point is chosen from the configured login
                        // mechanisms, and there are none. 401 means "I do not
                        // know who you are"; 403 means "I know, and no". A
                        // client that retries after refreshing its token needs
                        // to be able to tell those apart.
                        //
                        // Written as a literal rather than through
                        // ProblemDetail because this runs in a filter, outside
                        // the MVC stack, where no message converter exists.
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType("application/problem+json");
                            response.getWriter().write("""
                                    {"type":"about:blank","title":"Unauthorized",\
                                    "status":401,\
                                    "detail":"Authentication required"}""");
                        }))
                .build();
    }
}
