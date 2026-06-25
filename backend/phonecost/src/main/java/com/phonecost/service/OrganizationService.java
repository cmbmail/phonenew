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
    private final AuditLogService auditLogService;

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
            if (!updates.getCode().isEmpty()
                    && orgRepository.findByCodeAndDeletedAtIsNull(updates.getCode()).isPresent()) {
                throw new IllegalArgumentException("组织代码已存在: " + updates.getCode());
            }
            existing.setCode(updates.getCode());
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
        List<ImportRow> rows = new ArrayList<>();
        int skipped = 0;

        try (InputStream is = file.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String name = getCellStringValue(row, 0);
                String typeStr = getCellStringValue(row, 1);
                String code = getCellStringValue(row, 2);
                String parentCode = getCellStringValue(row, 3);
                String sortOrderStr = getCellStringValue(row, 4);

                if (name == null || name.isEmpty() || name.startsWith("AIGC:")) {
                    skipped++;
                    continue;
                }

                Byte type = (byte) 0;
                if (typeStr != null && !typeStr.isEmpty()) {
                    try { type = Byte.parseByte(typeStr); }
                    catch (NumberFormatException e) { type = parseTypeName(typeStr); }
                }

                int sortOrder = 0;
                if (sortOrderStr != null && !sortOrderStr.isEmpty()) {
                    try { sortOrder = Integer.parseInt(sortOrderStr); } catch (NumberFormatException ignored) {}
                }

                rows.add(new ImportRow(name.trim(), type, code != null && !code.isEmpty() ? code.trim() : null,
                        parentCode != null ? parentCode.trim() : "", sortOrder));
            }
        }

        List<SysOrganization> saved = new ArrayList<>();
        for (ImportRow r : rows) {
            SysOrganization org = SysOrganization.builder()
                    .name(r.name)
                    .type(r.type)
                    .code(r.code)
                    .sortOrder(r.sortOrder)
                    .isActive((byte) 1)
                    .path("")
                    .parentId(null)
                    .build();
            saved.add(orgRepository.save(org));
        }

        Map<String, Long> codeToId = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String code = rows.get(i).code;
            if (code != null && !code.isEmpty()) {
                codeToId.put(code, saved.get(i).getId());
            }
        }

        Map<Long, String> idToPath = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            ImportRow r = rows.get(i);
            SysOrganization s = saved.get(i);
            Long orgId = s.getId();

            if (idToPath.containsKey(orgId)) continue;

            String path;
            if (!r.parentCode.isEmpty()) {
                Long parentId = codeToId.get(r.parentCode);
                if (parentId != null) {
                    s.setParentId(parentId);
                    String parentPath = resolvePath(parentId, rows, saved, codeToId, idToPath);
                    path = parentPath + orgId + "/";
                } else {
                    path = "/" + orgId + "/";
                }
            } else {
                path = "/" + orgId + "/";
            }
            s.setPath(path);
            idToPath.put(orgId, path);
        }

        orgRepository.saveAll(saved);

        log.info("Organization import completed: total={}, skipped={}", rows.size(), skipped);
        int created = rows.size() - skipped;
        auditLogService.log(0L, "system", "ORG_IMPORT", "organization", null,
                "{\"total_count\":" + rows.size() + ",\"skipped_count\":" + skipped + "}");
        return Map.of("total", rows.size(), "created", created, "skipped", skipped);
    }

    private String resolvePath(Long orgId, List<ImportRow> rows, List<SysOrganization> saved,
                               Map<String, Long> codeToId, Map<Long, String> idToPath) {
        if (idToPath.containsKey(orgId)) return idToPath.get(orgId);

        int idx = -1;
        for (int i = 0; i < saved.size(); i++) {
            if (saved.get(i).getId().equals(orgId)) { idx = i; break; }
        }
        if (idx < 0) return "/" + orgId + "/";

        ImportRow r = rows.get(idx);
        if (r.parentCode.isEmpty()) {
            String path = "/" + orgId + "/";
            idToPath.put(orgId, path);
            return path;
        }

        Long parentId = codeToId.get(r.parentCode);
        if (parentId == null) {
            String path = "/" + orgId + "/";
            idToPath.put(orgId, path);
            return path;
        }

        String parentPath = resolvePath(parentId, rows, saved, codeToId, idToPath);
        String path = parentPath + orgId + "/";
        idToPath.put(orgId, path);
        saved.get(idx).setParentId(parentId);
        return path;
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

    private Byte parseTypeName(String name) {
        return switch (name) {
            case "集团" -> (byte) 1;
            case "一级分行" -> (byte) 2;
            case "二级分行" -> (byte) 3;
            case "部门" -> (byte) 4;
            default -> (byte) 0;
        };
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

    private record ImportRow(String name, Byte type, String code, String parentCode, int sortOrder) {}
}
