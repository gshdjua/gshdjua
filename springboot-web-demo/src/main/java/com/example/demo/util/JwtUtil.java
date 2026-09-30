package com.example.demo.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.logging.Logger;

public class JwtUtil {
    private static final Logger LOGGER = Logger.getLogger(JwtUtil.class.getName());
    private static final String SECRET_KEY = resolveSecretKey();
    private static final long EXPIRATION_TIME = 3600 * 1000;

    private static String resolveSecretKey() {
        String configured = System.getProperty("musichub.jwt.secret");
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv("MUSICHUB_JWT_SECRET");
        }
        if (configured != null && configured.length() >= 32) return configured;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        LOGGER.warning("MUSICHUB_JWT_SECRET is missing or shorter than 32 characters; "
                + "using a temporary key that changes after restart.");
        return Base64.getEncoder().encodeToString(random);
    }

    public static String generateToken(String username) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + EXPIRATION_TIME);
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(expireDate)
                .signWith(SignatureAlgorithm.HS256, SECRET_KEY)
                .compact();
    }

    public static String getUsernameByToken(String token) {
        Claims claims = Jwts.parser()
                .setSigningKey(SECRET_KEY)
                .parseClaimsJws(token)
                .getBody();
        return claims.getSubject();
    }
}
