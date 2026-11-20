package kr.dmu.hospital.config;

import java.util.List;
import kr.dmu.hospital.auth.JwtAuthFilter;
import kr.dmu.hospital.auth.SocialLoginFlow;
import kr.dmu.hospital.auth.SocialLoginSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {
    @Bean public static PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean public SecurityFilterChain security(HttpSecurity http, JwtAuthFilter filter, SocialLoginSettings social, SocialLoginFlow flow) throws Exception {
        return http.csrf(csrf -> csrf.disable()).cors(cors -> cors.configurationSource(corsSource(social)))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
            .requestCache(cache -> cache.requestCache(new NullRequestCache()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/health", "/api/meta", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll()
                .requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/social/providers").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/social/exchange").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register", "/api/chat").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/hospitals/**", "/api/doctors/**", "/api/slots").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/auth/logout", "/api/auth/social/profile").authenticated()
                .requestMatchers("/api/admin/**").hasRole("ADMIN").anyRequest().hasAnyRole("USER", "ADMIN"))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) -> { response.setStatus(401); response.setContentType("application/json;charset=UTF-8"); response.getWriter().write("{\"message\":\"로그인이 필요합니다.\"}"); })
                .accessDeniedHandler((request, response, error) -> { response.setStatus(403); response.setContentType("application/json;charset=UTF-8"); response.getWriter().write("{\"message\":\"접근 권한이 없습니다.\"}"); }))
            .oauth2Login(oauth -> oauth.clientRegistrationRepository(social::registration)
                .authorizedClientRepository(flow.transientClients())
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestRepository(flow.authorizationRequests()))
                .loginPage(social.getFrontendUrl() + "/#/login")
                .successHandler(flow::success).failureHandler(flow::failure))
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class).build();
    }
    private CorsConfigurationSource corsSource(SocialLoginSettings social) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5174", "http://127.0.0.1:5174", social.getFrontendUrl()).stream().distinct().toList());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**", config); return source;
    }
}
