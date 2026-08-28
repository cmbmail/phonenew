package com.phonecost.service;

import com.phonecost.domain.SysUser;
import com.phonecost.repository.SysUserRepository;
import com.phonecost.repository.SysOrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserRepository userRepository;
    private final SysOrganizationRepository orgRepository;
    private final PasswordEncoder passwordEncoder;

    public SysUser getById(Long id) {
        return userRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + id));
    }

    @Transactional
    public SysUser create(SysUser user) {
        if (userRepository.existsByUsernameAndDeletedAtIsNull(user.getUsername())) {
            throw new IllegalArgumentException("用户名已存在: " + user.getUsername());
        }
        if (user.getOrgId() != null && !orgRepository.existsByIdAndDeletedAtIsNull(user.getOrgId())) {
            throw new IllegalArgumentException("组织不存在: " + user.getOrgId());
        }
        // M-02 fix: enforce password complexity on user creation
        validatePasswordComplexity(user.getPassword());
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getRealName() == null) user.setRealName("");
        if (user.getRole() == null) user.setRole((byte) 4);
        if (user.getStatus() == null) user.setStatus((byte) 1);
        if (user.getMustChangePwd() == null) user.setMustChangePwd((byte) 1);
        SysUser saved = userRepository.save(user);
        log.info("User created: id={}, username={}, role={}", saved.getId(), saved.getUsername(), saved.getRole());
        return saved;
    }

    @Transactional
    public SysUser update(Long id, SysUser updates) {
        SysUser existing = getById(id);
        if (updates.getRealName() != null) existing.setRealName(updates.getRealName());
        if (updates.getRole() != null) existing.setRole(updates.getRole());
        if (updates.getOrgId() != null) {
            if (!orgRepository.existsByIdAndDeletedAtIsNull(updates.getOrgId())) {
                throw new IllegalArgumentException("组织不存在: " + updates.getOrgId());
            }
            existing.setOrgId(updates.getOrgId());
        }
        if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
        return userRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        SysUser user = getById(id);
        user.setDeletedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("User deleted: id={}, username={}", id, user.getUsername());
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        // M-01 fix: enforce password complexity on admin password reset
        validatePasswordComplexity(newPassword);
        SysUser user = getById(id);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePwd((byte) 1);
        userRepository.save(user);
        log.info("Password reset for user: id={}", id);
    }

    /** Validate password meets complexity requirements (shared with AuthController) */
    private void validatePasswordComplexity(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("密码长度至少8个字符");
        }
        if (password.length() > 128) {
            throw new IllegalArgumentException("密码长度不能超过128个字符");
        }
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = password.chars().anyMatch(c -> "!@#$%^&*()_+-=[]{}|;:',.<>?/`~".indexOf(c) >= 0);
        int categories = (hasUpper ? 1 : 0) + (hasLower ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSpecial ? 1 : 0);
        if (categories < 3) {
            throw new IllegalArgumentException("密码必须包含大写字母、小写字母、数字、特殊字符中至少3种");
        }
    }
}
