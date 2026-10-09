package com.cloudshield.backend.security;

import com.cloudshield.backend.domain.UserAccount;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record UserPrincipal(UUID id, String username, String displayName, String password, boolean enabled,
        Collection<? extends GrantedAuthority> authorities) implements UserDetails {
    public static UserPrincipal from(UserAccount user) {
        return new UserPrincipal(user.getId(), user.getUsername(), user.getDisplayName(), user.getPasswordHash(), user.isEnabled(),
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public String toString() { return "UserPrincipal[id=" + id + ", username=" + username + ", role=" + authorities + "]"; }
}
