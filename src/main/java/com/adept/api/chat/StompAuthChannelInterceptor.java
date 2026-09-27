package com.adept.api.chat;

import java.util.List;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.JwtClaims;
import com.adept.api.security.JwtService;
import com.adept.api.security.PrincipalValidationService;

@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final PrincipalValidationService principalValidationService;

    public StompAuthChannelInterceptor(
            JwtService jwtService,
            PrincipalValidationService principalValidationService) {
        this.jwtService = jwtService;
        this.principalValidationService = principalValidationService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7).trim();
                try {
                    JwtClaims claims = jwtService.parse(token);
                    principalValidationService.validate(
                        claims.userId(),
                        claims.membershipId(),
                        claims.workspaceId(),
                        claims.role(),
                        claims.tokenVersion(),
                        claims.authenticatedAt()
                    ).ifPresent(validated -> {
                        AuthenticatedPrincipal principal = validated.principal();
                        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))
                        );
                        accessor.setUser(auth);
                    });
                } catch (Exception ignored) {
                    // Invalid token -> accessor.getUser() remains unauthenticated
                }
            }
        }
        return message;
    }
}
