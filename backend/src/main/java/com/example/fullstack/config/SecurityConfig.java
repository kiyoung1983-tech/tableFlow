package com.example.fullstack.config;

import com.example.fullstack.common.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {
    @Bean
    @ConditionalOnProperty(prefix = "app.security", name = "mode", havingValue = "basic", matchIfMissing = true)
    SecurityFilterChain basicSecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return configureApiSecurity(http, objectMapper)
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.security", name = "mode", havingValue = "oauth2")
    SecurityFilterChain oauth2SecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return configureApiSecurity(http, objectMapper)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }

    private HttpSecurity configureApiSecurity(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeSecurityProblem(
                                request, response, objectMapper, HttpStatus.UNAUTHORIZED,
                                "AUTHENTICATION_REQUIRED", "인증이 필요합니다."))
                        .accessDeniedHandler((request, response, exception) -> writeSecurityProblem(
                                request, response, objectMapper, HttpStatus.FORBIDDEN,
                                "ACCESS_DENIED", "요청을 수행할 권한이 없습니다.")));
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.security", name = "mode", havingValue = "basic", matchIfMissing = true)
    UserDetailsService userDetailsService(@Value("${app.security.username}") String username,
            @Value("${app.security.password}") String password, PasswordEncoder encoder) {
        var user = User.withUsername(username).password(encoder.encode(password)).roles("DEVELOPER").build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origin}") String allowedOrigin) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Accept", "Authorization", "Content-Type", RequestTraceFilter.TRACE_ID_HEADER));
        configuration.setExposedHeaders(List.of(RequestTraceFilter.TRACE_ID_HEADER));
        configuration.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private static void writeSecurityProblem(
            HttpServletRequest request,
            HttpServletResponse response,
            ObjectMapper objectMapper,
            HttpStatus status,
            String code,
            String detail) throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        var traceId = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        if (traceId != null) {
            problem.setProperty("traceId", traceId.toString());
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
