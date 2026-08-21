package com.city.controller.session;

import com.city.constants.CityConstants;
import com.city.model.CreateSessionResponse;
import com.city.service.session.SessionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/city/sessions")
public class SessionController {
    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public CreateSessionResponse create(@RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId) {
        return new CreateSessionResponse(sessionService.createSession(userId));
    }
}
