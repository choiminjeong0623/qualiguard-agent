package com.qualiguard.qms.security;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** 로그인 사용자. 검토 이력에는 display_name 이 남는다. */
public class AppUser extends User {

    private final String displayName;
    private final String role;

    public AppUser(String username, String passwordHash, boolean enabled, String displayName, String role) {
        super(username, passwordHash, enabled, true, true, true, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.displayName = displayName;
        this.role = role;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRole() {
        return role;
    }
}
