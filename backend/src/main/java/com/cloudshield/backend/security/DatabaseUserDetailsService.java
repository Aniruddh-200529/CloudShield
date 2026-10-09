package com.cloudshield.backend.security;

import com.cloudshield.backend.repository.UserAccountRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import java.util.Locale;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UserAccountRepository users;
    public DatabaseUserDetailsService(UserAccountRepository users) { this.users = users; }
    @Override public UserDetails loadUserByUsername(String username) {
        return users.findByUsername(username.toLowerCase(Locale.ROOT)).map(UserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password"));
    }
}
