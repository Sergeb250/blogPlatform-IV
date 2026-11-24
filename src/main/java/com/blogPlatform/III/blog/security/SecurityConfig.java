package com.blogPlatform.III.blog.security;

import com.blogPlatform.III.blog.aop.SecurityEventAspect;
import com.blogPlatform.III.blog.security.jwt.JwtAuthenticationFilter;
import com.blogPlatform.III.blog.security.jwt.JwtService;
import com.blogPlatform.III.blog.security.monitor.AccessMonitorFilter;
import com.blogPlatform.III.blog.security.oauth2.GoogleLoginHandler;
import com.blogPlatform.III.blog.security.token.ActiveSessionRegistry;
import com.blogPlatform.III.blog.security.token.TokenBlacklist;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
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
    // the sign-in page, Swagger, the Google login round trip and Spring's error page
    private static final String[] OPEN = {"/", "/index.html", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
            "/oauth2/**", "/login/oauth2/**", "/error"};

    private final SecurityErrorHandler errorHandler;
    private final CorsConfigurationSource corsConfigurationSource;
    private final JwtService jwtService;
    private final TokenBlacklist tokenBlacklist;
    private final ActiveSessionRegistry sessions;
    private final SecurityEventAspect securityEvents;

    // only /csrf-demo: a page with a session cookie, which the browser sends on its own, so CSRF tokens are on.
    // The JWT API below has no login cookie, so CSRF is off there.
    @Bean
    @Order(1)
    public SecurityFilterChain csrfDemoSecurity(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/csrf-demo/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(Customizer.withDefaults())
                .exceptionHandling(errors -> errors.accessDeniedHandler(errorHandler::rejectCsrf))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurity(HttpSecurity http,
                                           ObjectProvider<ClientRegistrationRepository> googleClient,
                                           GoogleLoginHandler googleLoginHandler) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_READS).permitAll()
                        .requestMatchers(OPEN).permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, errorHandler, tokenBlacklist, sessions),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new AccessMonitorFilter(securityEvents), JwtAuthenticationFilter.class);

        if (googleClient.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .loginPage("/")
                    .successHandler(googleLoginHandler)
                    .failureHandler(googleLoginHandler));
        }
        return http.build();
    }
}
