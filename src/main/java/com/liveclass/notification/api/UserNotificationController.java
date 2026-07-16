package com.liveclass.notification.api;

import com.liveclass.notification.api.response.NotificationSummaryResponse;
import com.liveclass.notification.api.response.PageResponse;
import com.liveclass.notification.application.NotificationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/users/{userId}/notifications")
@RequiredArgsConstructor
public class UserNotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public PageResponse<NotificationSummaryResponse> list(
            @PathVariable String userId,
            @RequestParam(required = false) Boolean read,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(notificationService.list(userId, read, PageRequest.of(page, size))
                .map(NotificationSummaryResponse::from));
    }
}
