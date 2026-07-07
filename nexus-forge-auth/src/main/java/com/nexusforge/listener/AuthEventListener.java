package com.nexusforge.listener;

import com.nexusforge.event.UserBannedEvent;
import com.nexusforge.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthEventListener {

    private final AuthService authService;

    @EventListener
    public void onUserBanned(UserBannedEvent event) {
        authService.logoutAllRefreshTokens(event.userId());
    }
}