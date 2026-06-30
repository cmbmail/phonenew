package com.phonecost.repository;

import com.phonecost.domain.VersionUpgradePackage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VersionUpgradePackageRepository extends JpaRepository<VersionUpgradePackage, Long> {

    Page<VersionUpgradePackage> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<VersionUpgradePackage> findByTargetVersionAndDeletedAtIsNull(String targetVersion);
}
