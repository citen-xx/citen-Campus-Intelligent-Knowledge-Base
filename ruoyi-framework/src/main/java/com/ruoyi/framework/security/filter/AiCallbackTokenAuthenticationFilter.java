package com.ruoyi.framework.security.filter;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.ruoyi.framework.security.service.AiCallbackTokenService;
import io.jsonwebtoken.Claims;

/** Authenticates only the private AI callback endpoints using the signed callback header. */
@Component
public class AiCallbackTokenAuthenticationFilter extends OncePerRequestFilter
{
    private static final String INTERNAL_PREFIX = "/internal/ai/tool/";
    private static final String CALLBACK_AUTHORITY = "ROLE_AI_CALLBACK";

    private final AiCallbackTokenService tokenService;

    public AiCallbackTokenAuthenticationFilter(AiCallbackTokenService tokenService)
    {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException
    {
        if (request.getRequestURI().startsWith(INTERNAL_PREFIX))
        {
            try
            {
                if (request.getHeader(AiCallbackTokenService.TOKEN_HEADER) == null)
                {
                    SecurityContextHolder.clearContext();
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                Claims claims = tokenService.verifyRequest(request);
                AiCallbackPrincipal principal = new AiCallbackPrincipal(tokenService.userId(claims),
                    tokenService.studentId(claims), tokenService.conversationId(claims));
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority(CALLBACK_AUTHORITY))));
            }
            catch (RuntimeException ex)
            {
                SecurityContextHolder.clearContext();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    public record AiCallbackPrincipal(Long userId, String studentId, String conversationId)
    {
    }
}
