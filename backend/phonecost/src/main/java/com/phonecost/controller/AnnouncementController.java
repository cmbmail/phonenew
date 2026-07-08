package com.phonecost.controller;

import com.phonecost.domain.Announcement;
import com.phonecost.dto.ApiResponse;
import com.phonecost.service.AnnouncementService;
import com.phonecost.service.AuditLogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;
    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Byte status,
            @RequestParam(required = false) Byte type,
            @RequestParam(required = false) String keyword) {
        size = Math.min(size, 200);
        Page<Announcement> result = announcementService.listPaged(page, size, status, type, keyword);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "content", result.getContent(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "number", result.getNumber(),
                "size", result.getSize()
        )));
    }

    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<List<Announcement>>> latest() {
        return ResponseEntity.ok(ApiResponse.ok(announcementService.listLatestPublished()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Announcement>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(announcementService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Announcement>> create(
            @Valid @RequestBody CreateAnnouncementRequest req,
            @RequestAttribute("userId") Long userId) {
        Announcement announcement = Announcement.builder()
                .title(req.title)
                .content(req.content)
                .type(req.type != null ? req.type : 0)
                .priority(req.priority != null ? req.priority : 0)
                .status(req.status != null ? req.status : 0)
                .authorId(userId)
                .authorName(req.authorName != null ? req.authorName : "")
                .pinned(req.pinned != null ? req.pinned : 0)
                .build();
        Announcement created = announcementService.create(announcement);
        auditLogService.log(userId, "CREATE_ANNOUNCEMENT", "announcement", created.getId(), Map.of("title", created.getTitle()));
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Announcement>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAnnouncementRequest req,
            @RequestAttribute("userId") Long userId) {
        Announcement updates = new Announcement();
        if (req.title != null) updates.setTitle(req.title);
        if (req.content != null) updates.setContent(req.content);
        if (req.type != null) updates.setType(req.type);
        if (req.priority != null) updates.setPriority(req.priority);
        if (req.pinned != null) updates.setPinned(req.pinned);
        Announcement updated = announcementService.update(id, updates);
        auditLogService.log(userId, "UPDATE_ANNOUNCEMENT", "announcement", id, Map.of("title", updated.getTitle()));
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }

    @PutMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Announcement>> publish(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        Announcement published = announcementService.publish(id);
        auditLogService.log(userId, "PUBLISH_ANNOUNCEMENT", "announcement", id, Map.of("title", published.getTitle()));
        return ResponseEntity.ok(ApiResponse.ok(published));
    }

    @PutMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Announcement>> archive(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        Announcement archived = announcementService.archive(id);
        auditLogService.log(userId, "ARCHIVE_ANNOUNCEMENT", "announcement", id, Map.of("title", archived.getTitle()));
        return ResponseEntity.ok(ApiResponse.ok(archived));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        announcementService.delete(id);
        auditLogService.log(userId, "DELETE_ANNOUNCEMENT", "announcement", id, (String) null);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    // ---- Request DTOs ----
    @Data
    public static class CreateAnnouncementRequest {
        @NotBlank(message = "标题不能为空")
        private String title;
        @NotBlank(message = "内容不能为空")
        private String content;
        private Byte type;
        private Byte priority;
        private Byte status;
        private String authorName;
        private Byte pinned;
    }

    @Data
    public static class UpdateAnnouncementRequest {
        private String title;
        private String content;
        private Byte type;
        private Byte priority;
        private Byte pinned;
    }
}
