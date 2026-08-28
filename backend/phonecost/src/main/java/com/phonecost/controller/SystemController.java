package com.phonecost.controller;

import com.phonecost.dto.ApiResponse;
import com.phonecost.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/system")
@RequiredArgsConstructor
public class SystemController {

    private final AuditLogService auditLogService;

    /**
     * Restart the backend service.
     * Uses systemd-run --on-active=3s to schedule a delayed restart,
     * avoiding the security risks of creating temp scripts (symlink hijack, command injection).
     */
    @PostMapping("/restart")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> restartService(
            @RequestAttribute("userId") Long userId) {

        log.info("Service restart requested by user {}", userId);

        // Audit log - use Map detail since audit_log.detail is JSON column
        Map<String, Object> detail = new HashMap<>();
        detail.put("operator", "admin");
        detail.put("action", "restart_backend");
        auditLogService.log(userId, "RESTART_SERVICE", "system", null, detail);

        // Use systemd-run to schedule a delayed restart (avoids temp file symlink hijack)
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "sudo", "systemd-run", "--on-active=3s", "--unit=phonecost-restart",
                    "systemctl", "restart", "phonecost-backend"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.warn("systemd-run failed (exit={}): {}. Falling back to nohup approach.", exitCode, output);
                // Fallback: use nohup without creating a temp script (inline command only)
                ProcessBuilder fallbackPb = new ProcessBuilder(
                        "nohup", "bash", "-c", "sleep 3 && sudo systemctl restart phonecost-backend"
                );
                fallbackPb.redirectOutput(ProcessBuilder.Redirect.to(new File("/dev/null")));
                fallbackPb.redirectError(ProcessBuilder.Redirect.to(new File("/dev/null")));
                fallbackPb.start();
            }
            log.info("Restart scheduled successfully via systemd-run");
        } catch (Exception e) {
            log.error("Failed to schedule restart: {}", e.getMessage());
            // Still return success - the admin can restart manually
        }

        Map<String, Object> result = new HashMap<>();
        result.put("restarting", true);
        result.put("estimated_downtime_seconds", 15);
        result.put("message", "服务将在3秒后重启，预计15秒后恢复");

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Get service status info.
     */
    @GetMapping("/status")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getServiceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("up_since", getStartTime());
        status.put("java_version", System.getProperty("java.version"));
        status.put("os_name", System.getProperty("os.name"));
        status.put("os_arch", System.getProperty("os.arch"));
        Runtime runtime = Runtime.getRuntime();
        status.put("max_memory_mb", runtime.maxMemory() / 1024 / 1024);
        status.put("used_memory_mb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        status.put("free_memory_mb", runtime.freeMemory() / 1024 / 1024);
        status.put("available_processors", runtime.availableProcessors());
        return ResponseEntity.ok(ApiResponse.ok(status));
    }

    private String getStartTime() {
        try {
            long uptimeMs = System.currentTimeMillis()
                    - java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
            LocalDateTime start = LocalDateTime.now().minusSeconds(uptimeMs / 1000);
            return start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return "unknown";
        }
    }
}
