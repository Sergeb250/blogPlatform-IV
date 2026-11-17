package com.blogPlatform.III.blog.security;

import com.blogPlatform.III.blog.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

// How Spring Security finds a user by username: from our users table (soft-deleted users can't log in)
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(AuthUser::from)
                // never shown to the caller: Spring turns it into "Bad credentials", so a wrong username and a
                // wrong password look the same and nobody can find out which usernames exist
                .orElseThrow(() -> new UsernameNotFoundException("No user " + username));
    }
}
