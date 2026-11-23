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

    /** 같은 사용자인지는 상위 클래스(User)처럼 아이디로만 본다. 이름·역할은 표시용이라 비교에 넣지 않는다. */
    @Override
    public boolean equals(Object o) {
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
