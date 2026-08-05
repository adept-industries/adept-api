package com.adept.api.common.paging;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Consistent paginated response envelope used by all list endpoints.
 *
 * <p>Wire format (from the API contract):
 * <pre>
 * {
 *   "items":      [],
 *   "page":       0,
 *   "size":       25,
 *   "totalItems": 0,
 *   "totalPages": 0
 * }
 * </pre>
 *
 * <p>Page indexing is zero-based. Maximum page size is 100; controllers must cap
 * the {@code size} query parameter before invoking repositories.
 *
 * @param <T> DTO type of each list item. Never pass a JPA entity as {@code T}.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {

    /**
     * Convenience factory that maps a Spring Data {@link Page} to this envelope.
     *
     * @param springPage result returned by a repository {@code findAll(Pageable)} call.
     * @param <T>        DTO item type.
     * @return wrapped response ready to serialize.
     */
    public static <T> PageResponse<T> from(Page<T> springPage) {
        return new PageResponse<>(
                springPage.getContent(),
                springPage.getNumber(),
                springPage.getSize(),
                springPage.getTotalElements(),
                springPage.getTotalPages()
        );
    }
}
