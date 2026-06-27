package com.phonecost.repository;

import com.phonecost.domain.BillDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillDetailRepository extends JpaRepository<BillDetail, Long> {
    List<BillDetail> findByBatchIdAndDeletedAtIsNull(Long batchId);
    List<BillDetail> findByPhoneNumberAndBatchIdAndDeletedAtIsNull(String phoneNumber, Long batchId);
    List<BillDetail> findByPhoneNumberAndDeletedAtIsNull(String phoneNumber);
}
