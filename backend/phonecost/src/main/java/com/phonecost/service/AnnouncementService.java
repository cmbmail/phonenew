package com.phonecost.service;

import com.phonecost.domain.Announcement;
import com.phonecost.repository.AnnouncementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;

    public Announcement getById(Long id) {
        return announcementRepository.findById(id)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("公告不存在: " + id));
    }

    public Page<Announcement> listPaged(int page, int size, Byte status, Byte type, String keyword) {
        size = Math.min(size, 200);
        Pageable pageable = PageRequest.of(page, size);

        boolean hasKeyword = keyword != null && !keyword.isEmpty();
        boolean hasStatus = status != null;
        boolean hasType = type != null;

        if (hasKeyword && hasStatus && hasType) {
            return announcementRepository.findByTitleContainingAndStatusAndDeletedAtIsNullOrderByPinnedDescPublishedAtDescCreatedAtDesc(keyword, status, pageable);
        } else if (hasKeyword && hasStatus) {
            return announcementRepository.findByTitleContainingAndStatusAndDeletedAtIsNullOrderByPinnedDescPublishedAtDescCreatedAtDesc(keyword, status, pageable);
        } else if (hasStatus && hasType) {
            return announcementRepository.findByStatusAndTypeAndDeletedAtIsNullOrderByPinnedDescPublishedAtDescCreatedAtDesc(status, type, pageable);
        } else if (hasStatus) {
            return announcementRepository.findByStatusAndDeletedAtIsNullOrderByPinnedDescPublishedAtDescCreatedAtDesc(status, pageable);
        } else if (hasType) {
            return announcementRepository.findByTypeAndDeletedAtIsNullOrderByPinnedDescPublishedAtDescCreatedAtDesc(type, pageable);
        } else if (hasKeyword) {
            return announcementRepository.findByTitleContainingAndDeletedAtIsNullOrderByPinnedDescPublishedAtDescCreatedAtDesc(keyword, pageable);
        }
        return announcementRepository.findByDeletedAtIsNullOrderByPinnedDescPublishedAtDescCreatedAtDesc(pageable);
    }

    public List<Announcement> listLatestPublished() {
        return announcementRepository.findTop5ByStatusAndDeletedAtIsNullOrderByPinnedDescPublishedAtDesc((byte) 1);
    }

    @Transactional
    public Announcement create(Announcement announcement) {
        if (announcement.getTitle() == null || announcement.getTitle().isBlank()) {
            throw new IllegalArgumentException("公告标题不能为空");
        }
        if (announcement.getContent() == null || announcement.getContent().isBlank()) {
            throw new IllegalArgumentException("公告内容不能为空");
        }
        if (announcement.getStatus() == null) {
            announcement.setStatus((byte) 0);
        }
        if (announcement.getType() == null) {
            announcement.setType((byte) 0);
        }
        if (announcement.getPriority() == null) {
            announcement.setPriority((byte) 0);
        }
        if (announcement.getPinned() == null) {
            announcement.setPinned((byte) 0);
        }
        // If publishing directly, set publishedAt
        if (announcement.getStatus() == 1 && announcement.getPublishedAt() == null) {
            announcement.setPublishedAt(LocalDateTime.now());
        }
        return announcementRepository.save(announcement);
    }

    @Transactional
    public Announcement update(Long id, Announcement updates) {
        Announcement existing = getById(id);
        if (updates.getTitle() != null) existing.setTitle(updates.getTitle());
        if (updates.getContent() != null) existing.setContent(updates.getContent());
        if (updates.getType() != null) existing.setType(updates.getType());
        if (updates.getPriority() != null) existing.setPriority(updates.getPriority());
        if (updates.getPinned() != null) existing.setPinned(updates.getPinned());
        return announcementRepository.save(existing);
    }

    @Transactional
    public Announcement publish(Long id) {
        Announcement a = getById(id);
        if (a.getStatus() == 1) {
            throw new IllegalArgumentException("公告已发布，请勿重复操作");
        }
        a.setStatus((byte) 1);
        a.setPublishedAt(LocalDateTime.now());
        return announcementRepository.save(a);
    }

    @Transactional
    public Announcement archive(Long id) {
        Announcement a = getById(id);
        a.setStatus((byte) 2);
        a.setPinned((byte) 0); // 归档时取消置顶
        return announcementRepository.save(a);
    }

    @Transactional
    public void delete(Long id) {
        Announcement a = getById(id);
        a.setDeletedAt(LocalDateTime.now());
        announcementRepository.save(a);
    }
}
