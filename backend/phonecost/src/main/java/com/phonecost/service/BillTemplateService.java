package com.phonecost.service;

import com.phonecost.domain.BillTemplate;
import com.phonecost.dto.BillTemplateRequest;
import com.phonecost.repository.BillTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 账单模板管理服务
 * 提供模板CRUD、激活切换、配置校验
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillTemplateService {

    private final BillTemplateRepository templateRepository;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<BillTemplate> listTemplates() {
        return templateRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();
    }

    public BillTemplate getTemplate(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + id));
    }

    public BillTemplate getActiveTemplate() {
        return templateRepository.findByIsActiveAndDeletedAtIsNull((byte) 1)
                .orElseThrow(() -> new IllegalArgumentException("未找到活跃的账单模板"));
    }

    @Transactional
    public BillTemplate createTemplate(BillTemplateRequest req) {
        String name = req.getName();
        String operator = req.getOperator() != null ? req.getOperator() : "CHINA_TELECOM";
        String monthPattern = req.getMonthPattern();
        String description = req.getDescription();
        String sheetConfigs = req.getSheetConfigs();

        if (sheetConfigs == null || sheetConfigs.isBlank()) {
            throw new IllegalArgumentException("Sheet配置不能为空");
        }

        // Validate JSON structure
        validateSheetConfigs(sheetConfigs);

        // If this is the first template, auto-activate
        long count = templateRepository.count();
        byte isActive = count == 0 ? (byte) 1 : (byte) 0;

        BillTemplate template = BillTemplate.builder()
                .name(name.trim())
                .operator(operator != null ? operator : "CHINA_TELECOM")
                .monthPattern(monthPattern)
                .description(description)
                .sheetConfigs(sheetConfigs)
                .isActive(isActive)
                .build();

        template = templateRepository.save(template);
        log.info("Template created: id={}, name={}, autoActive={}", template.getId(), name, isActive == 1);
        return template;
    }

    @Transactional
    public BillTemplate updateTemplate(Long id, BillTemplateRequest req) {
        BillTemplate template = getTemplate(id);

        if (req.getName() != null) {
            String name = req.getName();
            if (name.isBlank()) {
                throw new IllegalArgumentException("模板名称不能为空");
            }
            template.setName(name.trim());
        }
        if (req.getOperator() != null) {
            template.setOperator(req.getOperator());
        }
        if (req.getMonthPattern() != null) {
            template.setMonthPattern(req.getMonthPattern());
        }
        if (req.getDescription() != null) {
            template.setDescription(req.getDescription());
        }
        if (req.getSheetConfigs() != null) {
            String sheetConfigs = req.getSheetConfigs();
            if (sheetConfigs.isBlank()) {
                throw new IllegalArgumentException("Sheet配置不能为空");
            }
            validateSheetConfigs(sheetConfigs);
            template.setSheetConfigs(sheetConfigs);
        }

        template = templateRepository.save(template);
        log.info("Template updated: id={}", id);
        return template;
    }

    @Transactional
    public void deleteTemplate(Long id) {
        BillTemplate template = getTemplate(id);
        if (template.getIsActive() != null && template.getIsActive() == 1) {
            throw new IllegalArgumentException("不能删除当前活跃模板，请先切换到其他模板");
        }
        template.setDeletedAt(LocalDateTime.now());
        templateRepository.save(template);
        log.info("Template deleted: id={}", id);
    }

    @Transactional
    public BillTemplate activateTemplate(Long id) {
        BillTemplate template = getTemplate(id);

        // Deactivate all other templates
        List<BillTemplate> all = templateRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();
        for (BillTemplate t : all) {
            if (t.getIsActive() == 1) {
                t.setIsActive((byte) 0);
                templateRepository.save(t);
            }
        }

        // Activate target
        template.setIsActive((byte) 1);
        template = templateRepository.save(template);
        log.info("Template activated: id={}, name={}", id, template.getName());
        return template;
    }

    /**
     * Validate sheet_configs JSON structure
     * Must be a JSON array of sheet config objects with required fields
     */
    private void validateSheetConfigs(String sheetConfigsJson) {
        try {
            List<Map<String, Object>> sheets = MAPPER.readValue(sheetConfigsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});

            if (sheets.isEmpty()) {
                throw new IllegalArgumentException("Sheet配置至少需要定义一个Sheet");
            }

            for (int i = 0; i < sheets.size(); i++) {
                Map<String, Object> sheet = sheets.get(i);
                String pattern = (String) sheet.get("sheetNamePattern");
                String sheetType = (String) sheet.get("sheetType");

                if (pattern == null || pattern.isBlank()) {
                    throw new IllegalArgumentException(String.format("第%d个Sheet配置缺少 sheetNamePattern", i + 1));
                }
                if (sheetType == null || sheetType.isBlank()) {
                    throw new IllegalArgumentException(String.format("第%d个Sheet配置缺少 sheetType", i + 1));
                }

                // Validate sheetType is one of allowed values
                List<String> validTypes = List.of("CALL", "RECORDING", "CRBT", "FLASH_MSG");
                if (!validTypes.contains(sheetType)) {
                    throw new IllegalArgumentException(
                            String.format("第%d个Sheet的sheetType='%s'无效，允许值: %s", i + 1, sheetType, validTypes));
                }

                // Validate phoneColumn exists
                if (!sheet.containsKey("phoneColumn")) {
                    throw new IllegalArgumentException(String.format("第%d个Sheet配置缺少 phoneColumn", i + 1));
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Sheet配置JSON格式错误: " + e.getMessage());
        }
    }
}
