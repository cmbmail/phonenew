package com.phonecost.controller;

import com.phonecost.domain.SysOrganization;
import com.phonecost.dto.ApiResponse;
import com.phonecost.service.DataScope;
import com.phonecost.service.DataScopeService;
import com.phonecost.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/org")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;
    private final DataScopeService dataScopeService;

    @GetMapping("/tree")
    public ResponseEntity<ApiResponse<List<SysOrganization>>> getTree(
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        List<SysOrganization> allOrgs = organizationService.getTree();
        List<SysOrganization> filtered = scope.filterByOrgId(
                allOrgs.stream().filter(o -> o.getDeletedAt() == null).toList(),
                SysOrganization::getId);
        return ResponseEntity.ok(ApiResponse.ok(filtered));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SysOrganization>> getById(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        DataScope scope = dataScopeService.getDataScope(userId);
        if (!scope.isOrgVisible(id)) {
            throw new IllegalArgumentException("无权访问该组织数据");
        }
        return ResponseEntity.ok(ApiResponse.ok(organizationService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SysOrganization>> create(@RequestBody SysOrganization org) {
        return ResponseEntity.ok(ApiResponse.ok(organizationService.create(org)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<SysOrganization>> update(@PathVariable Long id,
                                                                 @RequestBody SysOrganization org) {
        return ResponseEntity.ok(ApiResponse.ok(organizationService.update(id, org)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        organizationService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importFromExcel(
            @RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> result = organizationService.importFromExcel(file);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            throw new IllegalArgumentException("组织导入失败: " + e.getMessage());
        }
    }

    @PostMapping("/rebuild-paths")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> rebuildPaths() {
        organizationService.rebuildPaths();
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
