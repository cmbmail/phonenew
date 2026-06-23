package com.phonecost.controller;

import com.phonecost.domain.BillTemplate;
import com.phonecost.dto.ApiResponse;
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
    public ResponseEntity<ApiResponse<BillTemplate>> createTemplate(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.createTemplate(body)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<BillTemplate>> updateTemplate(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.updateTemplate(id, body)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteTemplate(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("deleted", true)));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<BillTemplate>> activateTemplate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.activateTemplate(id)));
    }
}
