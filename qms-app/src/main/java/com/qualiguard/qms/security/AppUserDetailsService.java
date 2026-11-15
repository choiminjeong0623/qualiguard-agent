package com.qualiguard.qms.security;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** dbo.app_user 에서 사용자를 읽는다 (09_users.sql). */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbc;

    public AppUserDetailsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        List<AppUser> users = jdbc.query("""
                SELECT username, password_hash, enabled, display_name, role FROM dbo.app_user WHERE username = ?
                """, (rs, i) -> new AppUser(rs.getString("username"), rs.getString("password_hash"),
                rs.getBoolean("enabled"), rs.getString("display_name"), rs.getString("role")), username);
        if (users.isEmpty()) {
            throw new UsernameNotFoundException("사용자를 찾을 수 없습니다");
        }
        return users.get(0);
    }
}
