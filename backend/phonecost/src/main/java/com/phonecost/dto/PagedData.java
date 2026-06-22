package com.phonecost.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PagedData<T> {

    private List<T> content;
    private int page;
    private int size;
    private long total;
    private int totalPages;

    public static <T> PagedData<T> of(Page<T> pageData) {
        return PagedData.<T>builder()
            .content(pageData.getContent())
            .page(pageData.getNumber())
            .size(pageData.getSize())
            .total(pageData.getTotalElements())
            .totalPages(pageData.getTotalPages())
            .build();
    }
}
