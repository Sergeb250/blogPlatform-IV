package com.blogPlatform.III.blog.security;

import com.blogPlatform.III.blog.security.jwt.JwtAuthenticationFilter;
import com.blogPlatform.III.blog.security.jwt.JwtService;
import com.blogPlatform.III.blog.security.monitor.AccessMonitorFilter;
import com.blogPlatform.III.blog.security.monitor.SecurityMonitor;
import com.blogPlatform.III.blog.security.oauth2.GoogleLoginHandler;
import com.blogPlatform.III.blog.security.token.ActiveSessionRegistry;
import com.blogPlatform.III.blog.security.token.TokenBlacklist;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_READS = {"/api/posts/**", "/api/tags/**"};
    private static final String[] API_DOCS = {"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"};

    private final SecurityErrorHandler errorHandler;
    private final JwtService jwtService;
    private final TokenBlacklist tokenBlacklist;
    private final ActiveSessionRegistry sessions;
    private final SecurityMonitor monitor;
    private final CorsConfigurationSource corsConfigurationSource;


    @Bean
    @Order(1)
    public SecurityFilterChain csrfDemoSecurity(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/csrf-demo/**")
                // a normal server session (JSESSIONID cookie), which the browser sends with every request on its own
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // CSRF on (the default, written out on purpose): every POST must carry the token from the form
                .csrf(Customizer.withDefaults())
                .exceptionHandling(errors -> errors.accessDeniedHandler(errorHandler::rejectCsrf))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    // GoogleLoginHandler is a parameter, not a field: it needs AuthService -> AuthenticationManager, which is
    // made in this class, so it can only be built once this class exists (a field would be a circular reference)
    public SecurityFilterChain apiSecurity(HttpSecurity http,
                                           ObjectProvider<ClientRegistrationRepository> googleClient,
                                           GoogleLoginHandler googleLoginHandler) throws Exception {
        http
                // allowed browser origins (CorsConfig); first in the chain, so a preflight never needs a token
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // no server session: every request proves by itself who sends it (its JWT)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF tricks the browser into sending a cookie it attaches on its own. This API has no login
                // cookie: the token must be put into the Authorization header by our own code, which a
                // foreign page can't do. So there is nothing for CSRF to abuse here.
                .csrf(AbstractHttpConfigurer::disable)
                // a JSON API: no browser password pop-up, no HTML login page, no /logout redirect
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                // checked top to bottom, the first matching rule wins
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_READS).permitAll()
                        .requestMatchers(API_DOCS).permitAll()
                        // the start of a Google login, and Google's way back with the one-time code
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        // Spring forwards errors to /error; showing an error must not need a login
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // the token is read before the access rules run, so the rules already know who is calling
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, errorHandler, tokenBlacklist, sessions, monitor),
                        UsernamePasswordAuthenticationFilter.class)
                // counts who calls which endpoint; after the JWT filter, so the caller is known
                .addFilterAfter(new AccessMonitorFilter(monitor), JwtAuthenticationFilter.class);

        // Google login only when a Google client is configured (profile "google"), so the app also starts
        // without Google keys. Spring does the OAuth2 round trip; our handler turns the result into our JWT.
        if (googleClient.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .successHandler(googleLoginHandler)
                    .failureHandler(googleLoginHandler));
        }
        return http.build();
    }

    // Checks a username + password: loads the user (AppUserDetailsService), then compares the password
    // with the stored BCrypt hash. AuthService.login calls it.
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
