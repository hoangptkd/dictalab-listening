package com.dictalab.security;

import com.dictalab.repository.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class DictaLabUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    public DictaLabUserDetailsService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByEmail(email)
                .map(DictaLabPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy tài khoản."));
    }
}
