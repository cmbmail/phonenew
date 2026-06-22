package com.phonecost.repository;

import com.phonecost.domain.BillTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BillTemplateRepository extends JpaRepository<BillTemplate, Long> {
    Optional<BillTemplate> findByIsActiveAndDeletedAtIsNull(Byte isActive);
}
