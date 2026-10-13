package com.rateshield.authentication.service;

import com.rateshield.authentication.dto.AuthResponse;
import com.rateshield.authentication.dto.LoginRequest;
import com.rateshield.authentication.dto.SignupRequest;
import com.rateshield.authentication.jwt.JwtTokenProvider;
import com.rateshield.authentication.model.User;
import com.rateshield.authentication.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public AuthResponse signup(SignupRequest request) {

        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("Username already exists");
        }

        var encodedPassword = passwordEncoder.encode(request.password());

        var user = new User(
                request.username(),
                encodedPassword,
                "USER"
        );

        userRepository.save(user);

        String token = jwtTokenProvider.generateToken(user.getUsername());

        return new AuthResponse(
                "User registered successfully",
                user.getUsername(),
                token
        );
    }

    public AuthResponse login(LoginRequest request) {

        var user = userRepository.findByUsername(request.username())
                .orElseThrow(() ->
                        new IllegalArgumentException("Invalid username or password")
                );

        if (!passwordEncoder.matches(
                request.password(),
                user.getPassword()
        )) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        String token = jwtTokenProvider.generateToken(user.getUsername());

        return new AuthResponse(
                "Login successful",
                user.getUsername(),
                token
        );
    }
}