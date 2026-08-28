package com.phonecost.repository;

import com.phonecost.domain.ComparisonArchive;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComparisonArchiveRepository extends JpaRepository<ComparisonArchive, Long> {

    @Query("SELECT a FROM ComparisonArchive a WHERE a.deletedAt IS NULL ORDER BY a.createdAt DESC")
    List<ComparisonArchive> findAllArchives();

    @Query("SELECT a FROM ComparisonArchive a WHERE a.deletedAt IS NULL AND a.compareType = :type ORDER BY a.createdAt DESC")
    List<ComparisonArchive> findByCompareType(@Param("type") String type);

    @Query("SELECT a FROM ComparisonArchive a WHERE a.deletedAt IS NULL ORDER BY a.createdAt DESC")
    List<ComparisonArchive> findLatest(Pageable pageable);

    @Query("SELECT a FROM ComparisonArchive a WHERE a.deletedAt IS NULL AND a.compareType = :type ORDER BY a.createdAt DESC")
    List<ComparisonArchive> findLatestByType(@Param("type") String type, Pageable pageable);

    /**
     * 便捷方法：获取最新归档（按类型）
     */
    default Optional<ComparisonArchive> findLatestByType(String type) {
        List<ComparisonArchive> list = findLatestByType(type, org.springframework.data.domain.PageRequest.of(0, 1));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
