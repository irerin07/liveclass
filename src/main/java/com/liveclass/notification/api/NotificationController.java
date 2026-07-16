package com.liveclass.notification.api;

import com.liveclass.notification.application.NotificationService;
import com.liveclass.notification.application.RegistrationResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 발송 요청 접수. 발송은 비동기이므로 신규·중복 모두 202로 응답하고, 멱등 재생 여부는
     * 상태 코드가 아니라 본문 {@code duplicated} 플래그로 전달한다 (spec §5.3, decisions.md D-1).
     */
    @PostMapping
    public ResponseEntity<RegisterNotificationResponse> register(
            @Valid @RequestBody RegisterNotificationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        RegistrationResult result = notificationService.register(request.toCommand(), idempotencyKey);
        return ResponseEntity.accepted().body(RegisterNotificationResponse.from(result));
    }

    @GetMapping("/{id}")
    public NotificationResponse get(@PathVariable Long id) {
        return NotificationResponse.from(notificationService.getDetail(id));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return ResponseEntity.ok().build();
    }
}
