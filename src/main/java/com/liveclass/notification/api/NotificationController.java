package com.liveclass.notification.api;

import com.liveclass.notification.application.NotificationService;
import com.liveclass.notification.application.RegistrationResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 발송 요청 접수. 신규 접수는 202(발송은 비동기), 멱등 중복은 200 + 기존 ID (Phase 2).
     */
    @PostMapping
    public ResponseEntity<RegisterNotificationResponse> register(
            @Valid @RequestBody RegisterNotificationRequest request) {
        RegistrationResult result = notificationService.register(request.toCommand());
        HttpStatus status = result.duplicated() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(RegisterNotificationResponse.from(result));
    }

    @GetMapping("/{id}")
    public NotificationResponse get(@PathVariable Long id) {
        return NotificationResponse.from(notificationService.get(id));
    }
}
