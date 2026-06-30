package com.phonecost.controller;

import com.phonecost.domain.VersionUpgradePackage;
import com.phonecost.dto.ApiResponse;
import com.phonecost.service.VersionUpgradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 版本升级Controller
 * 管理员专属功能：上传升级包、应用升级、回滚
 */
@RestController
@RequestMapping("/version")
@RequiredArgsConstructor
public class VersionUpgradeController {

    private final VersionUpgradeService versionUpgradeService;

    /** 获取当前版本信息 */
    @GetMapping("/current")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCurrentVersion() {
        return ResponseEntity.ok(ApiResponse.ok(versionUpgradeService.getCurrentVersion()));
    }

    /** 获取版本历史列表 */
    @GetMapping("/history")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getVersionHistory() {
        return ResponseEntity.ok(ApiResponse.ok(versionUpgradeService.getVersionHistory()));
    }

    /** 获取升级包列表 */
    @GetMapping("/packages")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<List<VersionUpgradePackage>>> listPackages() {
        return ResponseEntity.ok(ApiResponse.ok(versionUpgradeService.listPackages()));
    }

    /** 上传升级包 */
    @PostMapping("/packages/upload")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<VersionUpgradePackage>> uploadPackage(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("userId") Long userId) {
        try {
            VersionUpgradePackage pkg = versionUpgradeService.uploadPackage(file, userId);
            return ResponseEntity.ok(ApiResponse.ok(pkg));
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /** 应用升级 */
    @PostMapping("/packages/{id}/apply")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> applyUpgrade(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        Map<String, Object> result = versionUpgradeService.applyUpgrade(id, userId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /** 回滚升级 */
    @PostMapping("/packages/{id}/rollback")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rollbackUpgrade(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        // id here is the version ID (not package ID) - find the version to get backup
        Map<String, Object> result = versionUpgradeService.rollbackUpgrade(id, userId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /** 删除升级包 */
    @DeleteMapping("/packages/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deletePackage(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        versionUpgradeService.deletePackage(id, userId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
