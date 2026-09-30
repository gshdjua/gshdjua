package com.example.demo.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtUtilTest {
    @Test
    void generatedTokenCanBeVerifiedByTheSameApplicationInstance() {
        String token = JwtUtil.generateToken("release-check-user");
        assertEquals("release-check-user", JwtUtil.getUsernameByToken(token));
    }
}
