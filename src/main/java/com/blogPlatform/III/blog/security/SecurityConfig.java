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
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
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
// turns on @PreAuthorize: the URL rules below only split public / logged in / admin, roles are checked per endpoint
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    // anyone may read the blog (posts, tags, and the comments / reviews under a post); changing it needs a login
    private static final String[] PUBLIC_READS = {"/api/posts/**", "/api/tags/**"};
    private static final String[] API_DOCS = {"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"};
    // the start of a Google login, and Google's way back with the one-time code
    private static final String[] GOOGLE_LOGIN = {"/oauth2/**", "/login/oauth2/**"};

    private final SecurityErrorHandler errorHandler;
    private final CorsConfigurationSource corsConfigurationSource;
    private final JwtService jwtService;
    private final TokenBlacklist tokenBlacklist;
    private final ActiveSessionRegistry sessions;
    private final SecurityMonitor monitor;

    // GoogleLoginHandler is a parameter, not a field: it needs the AuthenticationManager made below, so as a field
    // it would be a circular reference
    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http,
                                           ObjectProvider<ClientRegistrationRepository> googleClient,
                                           GoogleLoginHandler googleLoginHandler) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // no server session: every request proves by itself who sends it (its JWT)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF abuses cookies the browser sends on its own; this API keeps no login cookie
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
                        .requestMatchers(GOOGLE_LOGIN).permitAll()
                        // Spring forwards errors to /error; showing an error must not need a login
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // the token is read before the access rules run, so the rules already know who is calling
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, errorHandler, tokenBlacklist, sessions, monitor),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new AccessMonitorFilter(monitor), JwtAuthenticationFilter.class);

        // only when a Google client is configured (profile "google"), so the app also starts without Google keys
        if (googleClient.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .successHandler(googleLoginHandler)
                    .failureHandler(googleLoginHandler));
        }
        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
