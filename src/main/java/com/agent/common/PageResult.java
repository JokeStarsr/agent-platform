package com.agent.common;

import java.util.List;

/**
 * 统一分页响应体（管理台列表接口公共结构）
 * page 从 1 起；size 默认在 Repository 层 clamp；totalPages 由 total/size 推导。
 * 对应 docs/design/api/20260902-admin-pages.md §2.1
 */
public class PageResult<T> {

    private int page;
    private int size;
    private long total;
    private int totalPages;
    private List<T> items;

    public PageResult() {
    }

    public PageResult(int page, int size, long total, List<T> items) {
        this.page = page;
        this.size = size;
        this.total = total;
        this.items = items;
        this.totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
    }

    public static <T> PageResult<T> of(int page, int size, long total, List<T> items) {
        return new PageResult<>(page, size, total, items);
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotal() {
        return total;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public List<T> getItems() {
        return items;
    }
}