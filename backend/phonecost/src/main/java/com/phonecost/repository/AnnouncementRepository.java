package com.phonecost.repository;

import com.phonecost.domain.Announcement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    Page<Announcement> findByDeletedAtIsNullOrderByPinnedDescPublishedAtDescCreatedAtDesc(Pageable pageable);

    Page<Announcement> findByStatusAndDeletedAtIsNullOrderByPinnedDescPublishedAtDescCreatedAtDesc(Byte status, Pageable pageable);

    Page<Announcement> findByTypeAndDeletedAtIsNullOrderByPinnedDescPublishedAtDescCreatedAtDesc(Byte type, Pageable pageable);

    Page<Announcement> findByStatusAndTypeAndDeletedAtIsNullOrderByPinnedDescPublishedAtDescCreatedAtDesc(Byte status, Byte type, Pageable pageable);

    Page<Announcement> findByTitleContainingAndDeletedAtIsNullOrderByPinnedDescPublishedAtDescCreatedAtDesc(String keyword, Pageable pageable);

    Page<Announcement> findByTitleContainingAndStatusAndDeletedAtIsNullOrderByPinnedDescPublishedAtDescCreatedAtDesc(String keyword, Byte status, Pageable pageable);

    List<Announcement> findTop5ByStatusAndDeletedAtIsNullOrderByPinnedDescPublishedAtDesc(Byte status);

    long countByStatusAndDeletedAtIsNull(Byte status);
}
