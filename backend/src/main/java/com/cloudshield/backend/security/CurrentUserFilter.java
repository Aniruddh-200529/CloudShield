package com.cloudshield.backend.security;

import com.cloudshield.backend.api.ApiError;
import com.cloudshield.backend.repository.UserAccountRepository;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CurrentUserFilter extends OncePerRequestFilter {
    private final UserAccountRepository users; private final ObjectMapper mapper;
    public CurrentUserFilter(UserAccountRepository users, ObjectMapper mapper) { this.users = users; this.mapper = mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var context = SecurityContextHolder.getContext(); var auth = context.getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserPrincipal oldPrincipal) {
            var current = users.findById(oldPrincipal.id());
            if (current.isEmpty() || !current.get().isEnabled()) {
                context.setAuthentication(null);
                if (request.getSession(false) != null) request.getSession(false).invalidate();
                response.setStatus(401); response.setContentType("application/json");
                mapper.writeValue(response.getOutputStream(), new ApiError(Instant.now(), 401, "Unauthorized", "Account is unavailable", request.getRequestURI(), Map.of()));
                return;
            }
            UserPrincipal refreshed = UserPrincipal.from(current.get());
            context.setAuthentication(new UsernamePasswordAuthenticationToken(refreshed, null, refreshed.authorities()));
        }
        chain.doFilter(request, response);
    }
}
