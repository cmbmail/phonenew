package com.phonecost.service;

import com.phonecost.domain.SysOrganization;
import com.phonecost.repository.SysOrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final SysOrganizationRepository orgRepository;

    public List<SysOrganization> getTree() {
        return orgRepository.findAll();
    }

    public SysOrganization getById(Long id) {
        return orgRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("组织不存在: " + id));
    }

    @Transactional
    public SysOrganization create(SysOrganization org) {
        if (org.getParentId() != null) {
            orgRepository.findById(org.getParentId())
                    .orElseThrow(() -> new IllegalArgumentException("上级组织不存在: " + org.getParentId()));
        }
        if (org.getCode() != null && !org.getCode().isEmpty()
                && orgRepository.findByCodeAndDeletedAtIsNull(org.getCode()).isPresent()) {
            throw new IllegalArgumentException("组织代码已存在: " + org.getCode());
        }
        if (org.getSortOrder() == null) org.setSortOrder(0);
        if (org.getIsActive() == null) org.setIsActive((byte) 1);
        if (org.getType() == null) org.setType((byte) 0);
        if (org.getCode() != null && org.getCode().isEmpty()) org.setCode(null);
        if (org.getCostCenter() != null && org.getCostCenter().isEmpty()) org.setCostCenter(null);

        org.setPath("");
        SysOrganization saved = orgRepository.save(org);

        String path;
        if (saved.getParentId() == null) {
            path = "/" + saved.getId() + "/";
        } else {
            SysOrganization parent = orgRepository.findById(saved.getParentId()).orElseThrow();
            path = parent.getPath() + saved.getId() + "/";
        }
        saved.setPath(path);
        return orgRepository.save(saved);
    }

    @Transactional
    public SysOrganization update(Long id, SysOrganization updates) {
        SysOrganization existing = getById(id);
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getType() != null) existing.setType(updates.getType());
        if (updates.getCode() != null && !updates.getCode().equals(existing.getCode())) {
            String code = updates.getCode().isEmpty() ? null : updates.getCode();
            if (code != null
                    && orgRepository.findByCodeAndDeletedAtIsNull(code).isPresent()) {
                throw new IllegalArgumentException("组织代码已存在: " + code);
            }
            existing.setCode(code);
        }
        if (updates.getCostCenter() != null) {
            existing.setCostCenter(updates.getCostCenter().isEmpty() ? null : updates.getCostCenter());
        }
        if (updates.getSortOrder() != null) existing.setSortOrder(updates.getSortOrder());
        if (updates.getIsActive() != null) existing.setIsActive(updates.getIsActive());
        return orgRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        SysOrganization org = getById(id);
        List<SysOrganization> descendants = orgRepository.findByPathStartingWithAndDeletedAtIsNull(org.getPath());
        if (descendants.size() > 1) {
            throw new IllegalArgumentException("该组织下存在子组织，无法删除");
        }
        org.setDeletedAt(LocalDateTime.now());
        orgRepository.save(org);
    }

    @Transactional
    public Map<String, Object> importFromExcel(MultipartFile file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        int skipped = 0;

        try (InputStream is = file.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String namePath = getCellStringValue(row, 0);
                String code = getCellStringValue(row, 1);
                String costCenter = getCellStringValue(row, 2);

                if (namePath == null || namePath.isEmpty() || namePath.startsWith("AIGC:")) {
                    skipped++;
                    continue;
                }

                rows.add(new String[]{
                    namePath.trim(),
                    code != null && !code.isEmpty() ? code.trim() : null,
                    costCenter != null && !costCenter.isEmpty() ? costCenter.trim() : null
                });
            }
        }

        // Load existing orgs into cache: "parentId:name" → SysOrganization
        Map<String, SysOrganization> existingCache = new HashMap<>();
        // Find the root (集团, type=1, no parent)
        Long rootId = null;
        for (SysOrganization org : orgRepository.findAll()) {
            if (org.getDeletedAt() != null) continue;
            if (org.getType() == 1 && org.getParentId() == null) {
                rootId = org.getId();
            }
            String key = (org.getParentId() == null ? "null" : org.getParentId().toString()) + ":" + org.getName();
            existingCache.put(key, org);
        }
        if (rootId == null) {
            throw new IllegalArgumentException("未找到根组织（集团），请先创建");
        }

        int created = 0;
        int updated = 0;

        for (String[] r : rows) {
            // Strip leading "/" and split
            String pathStr = r[0];
            if (pathStr.startsWith("/")) {
                pathStr = pathStr.substring(1);
            }
            String[] segments = pathStr.split("/");
            Long parentId = rootId; // start from 集团

            for (int d = 0; d < segments.length; d++) {
                String name = segments[d].trim();
                if (name.isEmpty()) continue;

                // depth 0 → 一级分行(type=2), depth 1 → 二级分行(type=3), ...
                byte type = (byte) Math.min(d + 2, 6);
                String key = parentId.toString() + ":" + name;
                boolean isLeaf = (d == segments.length - 1);

                SysOrganization org = existingCache.get(key);
                if (org == null) {
                    org = SysOrganization.builder()
                            .name(name)
                            .type(type)
                            .code(isLeaf ? r[1] : null)
                            .costCenter(isLeaf ? r[2] : null)
                            .sortOrder(0)
                            .isActive((byte) 1)
                            .path("")
                            .parentId(parentId)
                            .build();
                    org = orgRepository.save(org);
                    existingCache.put(key, org);
                    created++;
                } else if (isLeaf) {
                    boolean changed = false;
                    if (r[1] != null && !r[1].equals(org.getCode())) {
                        org.setCode(r[1]);
                        changed = true;
                    }
                    if (r[2] != null && !r[2].equals(org.getCostCenter())) {
                        org.setCostCenter(r[2]);
                        changed = true;
                    }
                    if (changed) {
                        orgRepository.save(org);
                        updated++;
                    }
                }

                parentId = org.getId();
            }
        }

        rebuildPaths();

        int totalProcessed = created + updated;
        log.info("Organization import completed: total={}, created={}, updated={}, skipped={}",
                totalProcessed, created, updated, skipped);
        return Map.of("total", totalProcessed, "created", created, "skipped", skipped, "updated", updated);
    }

    @Transactional
    public void rebuildPaths() {
        List<SysOrganization> all = orgRepository.findAll();
        Map<Long, SysOrganization> orgMap = new HashMap<>();
        for (SysOrganization org : all) {
            if (org.getDeletedAt() != null) continue;
            orgMap.put(org.getId(), org);
        }

        for (SysOrganization org : orgMap.values()) {
            String path = buildPath(org.getId(), orgMap, new HashSet<>());
            org.setPath(path);
        }
        orgRepository.saveAll(orgMap.values().stream().toList());
        log.info("Rebuilt paths for {} organizations", orgMap.size());
    }

    private String buildPath(Long orgId, Map<Long, SysOrganization> orgMap, Set<Long> visited) {
        if (visited.contains(orgId)) return "/";
        visited.add(orgId);
        SysOrganization org = orgMap.get(orgId);
        if (org == null || org.getParentId() == null) return "/" + orgId + "/";
        String parentPath = buildPath(org.getParentId(), orgMap, visited);
        return parentPath + orgId + "/";
    }

    private String getCellStringValue(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                } else {
                    yield String.valueOf(val);
                }
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue(); }
                catch (Exception ex) { yield String.valueOf(cell.getNumericCellValue()); }
            }
            default -> null;
        };
    }
}
