package com.nexusforge.base;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.Collections;
import java.util.List;

/**
 * 统一分页响应包装。
 *
 * <p>与 {@link Result} 配合使用:分页接口返回
 * {@code Result<PageResult<T>>}。字段命名遵循 Element Plus / Ant Design Pro
 * 主流约定,前端可直接绑定表格组件而无需适配。
 *
 * <h3>字段语义</h3>
 * <ul>
 *   <li>{@code records}      当前页数据列表(空页时为 {@code []} 而非 {@code null})</li>
 *   <li>{@code total}        总记录数(数据库 count,非 records.size())</li>
 *   <li>{@code page}         当前页码,<b>1-based</b>(与服务层 {@code page-1} 转换无关)</li>
 *   <li>{@code size}         每页大小</li>
 *   <li>{@code pages}        总页数,等价于 {@code ceil(total / size)};size<=0 或 total=0 时为 0</li>
 *   <li>{@code hasNext}      是否存在下一页({@code page < pages})</li>
 *   <li>{@code hasPrevious}  是否存在上一页({@code page > 1})</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // service 层返回 Page<T> 时直接转
 * return PageResult.of(page);
 *
 * // service 层手头是 List<T> + 已知 total 时
 * return PageResult.of(list, total, page, size);
 *
 * // 空结果
 * return PageResult.empty(page, size);
 * }</pre>
 *
 * @param <T> 单条记录类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /** 当前页数据列表 */
    private List<T> records;

    /** 总记录数(数据库 count) */
    private long total;

    /** 当前页码(1-based) */
    private int page;

    /** 每页大小 */
    private int size;

    /** 总页数,等价于 {@code ceil(total / size)} */
    private int pages;

    /** 是否存在下一页 */
    private boolean hasNext;

    /** 是否存在上一页 */
    private boolean hasPrevious;

    /**
     * 基础构造:由 records 列表 + total + page + size 推算 pages / hasNext / hasPrevious。
     *
     * <p>{@code size <= 0} 时 {@code pages} 强制为 0(避免除零);{@code records}
     * 为 null 时归一为空列表(避免前端 NPE)。</p>
     */
    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        List<T> safe = records == null ? Collections.emptyList() : records;
        int pages = (size <= 0 || total <= 0) ? 0
                : (int) Math.ceil((double) total / (double) size);
        return PageResult.<T>builder()
                .records(safe)
                .total(total)
                .page(page)
                .size(size)
                .pages(pages)
                .hasNext(page < pages)
                .hasPrevious(page > 1)
                .build();
    }

    /**
     * 从 Spring Data {@link Page} 适配(Spring Data 的 page 是 0-based,这里
     * 内部 +1 转为对外 1-based,统一前端约定)。
     */
    public static <T> PageResult<T> of(Page<T> page) {
        if (page == null) {
            return empty(1, 0);
        }
        return of(page.getContent(), page.getTotalElements(),
                 page.getNumber() + 1, page.getSize());
    }

    /** 空结果(records=[], total=0, pages=0) */
    public static <T> PageResult<T> empty(int page, int size) {
        return of(Collections.emptyList(), 0, page, size);
    }
}
