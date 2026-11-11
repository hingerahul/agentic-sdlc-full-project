package com.hingera.agenticsdlc.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${agentic.security.requester.username:}") String requesterUsername,
            @Value("${agentic.security.requester.password:}") String requesterPassword,
            @Value("${agentic.security.reviewer.username:}") String reviewerUsername,
            @Value("${agentic.security.reviewer.password:}") String reviewerPassword
    ) {
        requireCredentials("requester", requesterUsername, requesterPassword);
        requireCredentials("reviewer", reviewerUsername, reviewerPassword);
        if (requesterUsername.equals(reviewerUsername)) {
            throw new IllegalStateException("Requester and reviewer accounts must be distinct");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(requesterUsername).password(passwordEncoder.encode(requesterPassword))
                        .roles("REQUESTER").build(),
                User.withUsername(reviewerUsername).password(passwordEncoder.encode(reviewerPassword))
                        .roles("REVIEWER").build());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Strict"));
        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/runs/*/review").hasRole("REVIEWER")
                        .requestMatchers(HttpMethod.PATCH, "/api/runs/*/stages/*/output").hasRole("REVIEWER")
                        .anyRequest().authenticated())
                .httpBasic(basic -> { });
        return http.build();
    }

    private void requireCredentials(String role, String username, String password) {
        if (username == null || username.isBlank() || password == null || password.length() < 12) {
            throw new IllegalStateException("Configure " + role + " username and a password of at least 12 characters");
        }
    }
}
