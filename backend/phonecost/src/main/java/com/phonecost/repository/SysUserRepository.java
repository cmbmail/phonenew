package com.phonecost.repository;

import com.phonecost.domain.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SysUserRepository extends JpaRepository<SysUser, Long> {
    Optional<SysUser> findByUsernameAndDeletedAtIsNull(String username);
    List<SysUser> findByOrgIdAndDeletedAtIsNull(Long orgId);
    boolean existsByUsernameAndDeletedAtIsNull(String username);
}
