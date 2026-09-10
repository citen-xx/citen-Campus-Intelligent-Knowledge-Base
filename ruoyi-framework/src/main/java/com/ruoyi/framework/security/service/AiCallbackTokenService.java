package com.ruoyi.framework.security.service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

/** Signs and verifies short-lived Java-to-agent callback credentials. */
@Service
public class AiCallbackTokenService
{
    public static final String TOKEN_HEADER = "X-AI-Callback-Token";
    public static final String CONVERSATION_HEADER = "X-AI-Callback-Conversation-Id";
    public static final String CALLBACK_SUBJECT = "campus-ai-callback";

    private final String secret;
    private final long ttlSeconds;

    public AiCallbackTokenService(@Value("${campus.ai.callback-token-secret:}") String secret,
        @Value("${campus.ai.callback-token-ttl-seconds:90}") long ttlSeconds)
    {
        this.secret = secret == null ? "" : secret.trim();
        this.ttlSeconds = Math.max(60L, Math.min(120L, ttlSeconds));
    }

    public String issue(Long userId, String studentId, String conversationId)
    {
        if (userId == null || isBlank(studentId) || isBlank(conversationId) || secret.isEmpty())
        {
            throw new IllegalStateException("AI callback token configuration or context is invalid");
        }
        Date now = new Date();
        Date expiry = new Date(now.getTime() + TimeUnit.SECONDS.toMillis(ttlSeconds));
        return Jwts.builder()
            .setSubject(CALLBACK_SUBJECT)
            .claim("userId", userId.toString())
            .claim("studentId", studentId)
            .claim("conversationId", conversationId)
            .setIssuedAt(now)
            .setExpiration(expiry)
            .signWith(SignatureAlgorithm.HS512, secret.getBytes(StandardCharsets.UTF_8))
            .compact();
    }

    public Claims verify(String token, String expectedConversationId)
    {
        if (isBlank(token) || isBlank(expectedConversationId) || secret.isEmpty())
        {
            throw unauthorized();
        }
        try
        {
            Claims claims = Jwts.parser()
                .setSigningKey(secret.getBytes(StandardCharsets.UTF_8))
                .parseClaimsJws(token)
                .getBody();
            if (!CALLBACK_SUBJECT.equals(claims.getSubject())
                || isBlank(claims.get("userId", String.class))
                || isBlank(claims.get("studentId", String.class))
                || isBlank(claims.get("conversationId", String.class))
                || !Objects.equals(expectedConversationId, claims.get("conversationId", String.class)))
            {
                throw unauthorized();
            }
            return claims;
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw unauthorized();
        }
    }

    public Claims verifyRequest(HttpServletRequest request)
    {
        return verify(request.getHeader(TOKEN_HEADER), request.getHeader(CONVERSATION_HEADER));
    }

    public Long userId(Claims claims)
    {
        try
        {
            return Long.valueOf(claims.get("userId", String.class));
        }
        catch (Exception ex)
        {
            throw unauthorized();
        }
    }

    public String studentId(Claims claims)
    {
        String value = claims == null ? null : claims.get("studentId", String.class);
        if (isBlank(value))
        {
            throw unauthorized();
        }
        return value;
    }

    public String conversationId(Claims claims)
    {
        String value = claims == null ? null : claims.get("conversationId", String.class);
        if (isBlank(value))
        {
            throw unauthorized();
        }
        return value;
    }

    private ServiceException unauthorized()
    {
        return new ServiceException("AI callback token is invalid or expired", HttpStatus.UNAUTHORIZED);
    }

    private boolean isBlank(String value)
    {
        return value == null || value.isBlank();
    }
}
