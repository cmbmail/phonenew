package com.phonecost.repository;

import com.phonecost.domain.DataSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DataSnapshotRepository extends JpaRepository<DataSnapshot, Long> {

    Optional<DataSnapshot> findByBillBatchIdAndDeletedAtIsNull(Long billBatchId);

    List<DataSnapshot> findAllByDeletedAtIsNullOrderByCreatedAtDesc();
}
