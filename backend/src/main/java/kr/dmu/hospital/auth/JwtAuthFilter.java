package kr.dmu.hospital.auth;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final AuthService auth;
    public JwtAuthFilter(AuthService auth) { this.auth = auth; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                UserPrincipal user = auth.authenticate(header.substring(7));
                String authority = user.isProfileComplete() ? "ROLE_" + user.getRole() : "ROLE_PROFILE_PENDING";
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority(authority))));
            } catch (JwtException | IllegalArgumentException error) { SecurityContextHolder.clearContext(); }
        }
        chain.doFilter(request, response);
    }
}
