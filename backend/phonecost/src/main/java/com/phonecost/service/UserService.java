package com.phonecost.service;

import com.phonecost.domain.SysUser;
import com.phonecost.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public List<SysUser> list() {
        return userRepository.findAll();
    }

    public SysUser getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + id));
    }

    @Transactional
    public SysUser create(SysUser user) {
        if (userRepository.existsByUsernameAndDeletedAtIsNull(user.getUsername())) {
            throw new IllegalArgumentException("用户名已存在: " + user.getUsername());
        }
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
        if (updates.getOrgId() != null) existing.setOrgId(updates.getOrgId());
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
        SysUser user = getById(id);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePwd((byte) 1);
        userRepository.save(user);
        log.info("Password reset for user: id={}", id);
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("原密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePwd((byte) 0);
        userRepository.save(user);
    }
}
