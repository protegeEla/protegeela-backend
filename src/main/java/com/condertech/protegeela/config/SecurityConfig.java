package com.condertech.protegeela.config;

import com.condertech.protegeela.auth.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, AuthService auth,
            @Value("${app.cors.allowed-origins}") String origins) throws Exception {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(Arrays.stream(origins.split(",")).map(String::strip).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);

        return http.cors(c -> c.configurationSource(source))
                // Authentication uses only explicit bearer headers, never cookies.
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/auth/register", "/api/auth/login",
                                "/api/auth/verify-email", "/api/auth/resend-verification").permitAll()
                        .requestMatchers("/api/contact-invitations/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/safety-checkins/shared/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/health").permitAll()
                        // Browser WebSocket authenticates in its first frame, before receiving events.
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/ws/alerts").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, 401, "Autenticacao necessaria."))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, 403, "Acesso negado.")))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                            FilterChain chain) throws ServletException, IOException {
                        String header = request.getHeader("Authorization");
                        if (header != null && header.startsWith("Bearer ")) {
                            auth.resolve(header.substring(7)).ifPresent(user -> {
                                var authentication = new UsernamePasswordAuthenticationToken(user.getId(), null,
                                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                                SecurityContextHolder.getContext().setAuthentication(authentication);
                            });
                        }
                        chain.doFilter(request, response);
                    }
                }, UsernamePasswordAuthenticationFilter.class).build();
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
