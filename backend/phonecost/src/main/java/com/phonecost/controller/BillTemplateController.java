package com.phonecost.controller;

import com.phonecost.domain.BillTemplate;
import com.phonecost.dto.ApiResponse;
import com.phonecost.service.AuditLogService;
import com.phonecost.service.BillTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 账单模板管理Controller
 * 仅管理员可操作模板
 */
@RestController
@RequestMapping("/templates")
@RequiredArgsConstructor
public class BillTemplateController {

    private final BillTemplateService templateService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<BillTemplate>>> listTemplates() {
        return ResponseEntity.ok(ApiResponse.ok(templateService.listTemplates()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BillTemplate>> getTemplate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.getTemplate(id)));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<BillTemplate>> getActiveTemplate() {
        return ResponseEntity.ok(ApiResponse.ok(templateService.getActiveTemplate()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<BillTemplate>> createTemplate(
            @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long userId) {
        BillTemplate created = templateService.createTemplate(body);
        auditLogService.log(userId, "TEMPLATE_CREATE", "bill_template", created.getId(),
                Map.of("name", created.getName() != null ? created.getName() : ""));
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<BillTemplate>> updateTemplate(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            @RequestAttribute("userId") Long userId) {
        BillTemplate updated = templateService.updateTemplate(id, body);
        auditLogService.log(userId, "TEMPLATE_UPDATE", "bill_template", id,
                Map.of("name", updated.getName() != null ? updated.getName() : ""));
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteTemplate(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        auditLogService.log(userId, "TEMPLATE_DELETE", "bill_template", id, (Map<String, Object>) null);
        templateService.deleteTemplate(id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("deleted", true)));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<BillTemplate>> activateTemplate(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        BillTemplate activated = templateService.activateTemplate(id);
        auditLogService.log(userId, "TEMPLATE_ACTIVATE", "bill_template", id,
                Map.of("name", activated.getName() != null ? activated.getName() : ""));
        return ResponseEntity.ok(ApiResponse.ok(activated));
    }
}
