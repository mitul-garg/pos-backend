package com.pos.model;

import java.util.List;

/**
 * The paginated list envelope — {@code {items, total, page, pageSize}}, exactly the shape
 * every {@code list()} in the frontend's service layer already returns.
 *
 * <p>The frontend currently asks for {@code pageSize: 200} and renders everything, so
 * {@code total} is decoration today. It is here anyway because the envelope is what lets
 * a real pager arrive later without a wire change (backend-plan.md section 11), and
 * retrofitting an envelope means touching every caller.
 *
 * <p><b>{@code total} is a number, not a {@link JsonId} string.</b> Ids are stringified
 * per field rather than by a blanket mapper rule precisely so counts stay numeric — the
 * frontend does arithmetic on this one.
 */
public class PageData<T> {

    private final List<T> items;
    private final long total;
    private final int page;
    private final int pageSize;

    public PageData(List<T> items, long total, int page, int pageSize) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    public List<T> getItems() {
        return items;
    }

    public long getTotal() {
        return total;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }
}
