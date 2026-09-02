package com.nexusforge.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageResult} 单元测试 —— 覆盖 of / of(Page) / empty 三个工厂的
 * pages / hasNext / hasPrevious 计算边界,以及 Spring Data Page 的 0→1-based
 * page 转换。
 */
@DisplayName("PageResult 单元测试")
class PageResultTest {

    // ----------------------------------------------------------------
    // of(records, total, page, size)
    // ----------------------------------------------------------------
    @Test
    @DisplayName("of: 标准场景,5 条 / 20 size / page 1 → pages=1, hasNext=false, hasPrevious=false")
    void of_standard() {
        PageResult<String> r = PageResult.of(List.of("a", "b", "c", "d", "e"), 5, 1, 20);
        assertThat(r.getRecords()).containsExactly("a", "b", "c", "d", "e");
        assertThat(r.getTotal()).isEqualTo(5);
        assertThat(r.getPage()).isEqualTo(1);
        assertThat(r.getSize()).isEqualTo(20);
        assertThat(r.getPages()).isEqualTo(1);
        assertThat(r.isHasNext()).isFalse();
        assertThat(r.isHasPrevious()).isFalse();
    }

    @Test
    @DisplayName("of: 多页场景,共 45 条 / 20 size → page 2 有 hasPrevious, hasNext=true")
    void of_multipage_middle() {
        PageResult<String> r = PageResult.of(List.of("x"), 45, 2, 20);
        assertThat(r.getPages()).isEqualTo(3);                       // ceil(45/20)=3
        assertThat(r.isHasNext()).isTrue();
        assertThat(r.isHasPrevious()).isTrue();
    }

    @Test
    @DisplayName("of: 最后一页,page == pages → hasNext=false, hasPrevious=true")
    void of_last_page() {
        PageResult<String> r = PageResult.of(List.of("z"), 45, 3, 20);
        assertThat(r.isHasNext()).isFalse();
        assertThat(r.isHasPrevious()).isTrue();
    }

    @Test
    @DisplayName("of: 空列表 total=0 → pages=0, hasNext=false(空页视为终态)")
    void of_empty() {
        PageResult<String> r = PageResult.of(List.of(), 0, 1, 20);
        assertThat(r.getPages()).isEqualTo(0);
        assertThat(r.isHasNext()).isFalse();
        assertThat(r.isHasPrevious()).isFalse();
    }

    @Test
    @DisplayName("of: 边界 size<=0 → pages=0, 避免除零")
    void of_zero_size() {
        PageResult<String> r = PageResult.of(List.of("a"), 100, 1, 0);
        assertThat(r.getPages()).isEqualTo(0);
        assertThat(r.getSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("of: null records 归一为空列表(避免前端 NPE)")
    void of_null_records() {
        PageResult<String> r = PageResult.of(null, 0, 1, 20);
        assertThat(r.getRecords()).isEmpty();
    }

    // ----------------------------------------------------------------
    // of(Page<T>)
    // ----------------------------------------------------------------
    @Test
    @DisplayName("of(Page): Spring Data 0-based page 转 1-based,total/size 原样")
    void of_page_0based_to_1based() {
        // 模拟 Spring Data:page=1 (0-based),size=20,content 含 5 条,total=45
        Page<String> springPage = new PageImpl<>(
                List.of("a", "b", "c", "d", "e"),
                PageRequest.of(1, 20),
                45
        );
        PageResult<String> r = PageResult.of(springPage);
        assertThat(r.getRecords()).hasSize(5);
        assertThat(r.getTotal()).isEqualTo(45);
        assertThat(r.getPage()).isEqualTo(2);                       // 0-based 1 → 1-based 2
        assertThat(r.getSize()).isEqualTo(20);
        assertThat(r.getPages()).isEqualTo(3);
        assertThat(r.isHasNext()).isTrue();
        assertThat(r.isHasPrevious()).isTrue();
    }

    @Test
    @DisplayName("of(Page): null 入参 → empty(1, 0) 兜底")
    void of_page_null() {
        PageResult<String> r = PageResult.of((Page<String>) null);
        assertThat(r.getRecords()).isEmpty();
        assertThat(r.getTotal()).isZero();
        assertThat(r.getPage()).isEqualTo(1);
        assertThat(r.getSize()).isZero();
    }

    // ----------------------------------------------------------------
    // empty(page, size)
    // ----------------------------------------------------------------
    @Test
    @DisplayName("empty: 直接构造空结果,records=[], total=0, pages=0")
    void empty_factory() {
        PageResult<String> r = PageResult.empty(2, 20);
        assertThat(r.getRecords()).isEmpty();
        assertThat(r.getTotal()).isZero();
        assertThat(r.getPage()).isEqualTo(2);
        assertThat(r.getSize()).isEqualTo(20);
        assertThat(r.getPages()).isZero();
        assertThat(r.isHasNext()).isFalse();
        assertThat(r.isHasPrevious()).isTrue();                     // page=2 → 有上一页
    }
}
