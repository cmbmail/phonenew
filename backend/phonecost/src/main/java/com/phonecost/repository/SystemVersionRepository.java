package com.phonecost.repository;

import com.phonecost.domain.SystemVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SystemVersionRepository extends JpaRepository<SystemVersion, Long> {

    Optional<SystemVersion> findByIsCurrentTrueAndDeletedAtIsNull();
}
