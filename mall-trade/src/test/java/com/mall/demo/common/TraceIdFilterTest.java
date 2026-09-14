package com.mall.demo.common;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * traceId 接收端（P8-4）的单元测试。
 *
 * <p>守三件事：
 * <ol>
 *   <li>网关给的值（形状合规）**原样接住**并写进 MDC（链路才串得起来）；</li>
 *   <li>形状不合规（空/带空格换行/超长）⇒ **丢弃并重新生成**（防日志注入）；</li>
 *   <li>请求处理完 **MDC 必须清空** —— Tomcat 线程复用，留着就会把 id 串到下一个请求上。</li>
 * </ol>
 */
@DisplayName("traceId 接收端（MDC）")
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private String run(String header) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/product/1");
        if (header != null) {
            request.addHeader(TraceIdFilter.TRACE_ID_HEADER, header);
        }
        AtomicReference<String> insideChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> insideChain.set(MDC.get(TraceIdFilter.MDC_KEY));
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return insideChain.get();
    }

    @Test
    @DisplayName("网关传来的合规 id：原样进 MDC")
    void trustedShapeIsReused() throws Exception {
        assertThat(run("abc12345DEF_6789")).isEqualTo("abc12345DEF_6789");
    }

    @Test
    @DisplayName("没有头：自己生成一个（直连/探针路径也要有关联 id）")
    void missingHeaderGenerates() throws Exception {
        String id = run(null);
        assertThat(id).isNotBlank().matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("形状不合规：丢弃并重新生成（防日志注入）")
    void unsafeValuesAreReplaced() throws Exception {
        assertThat(run("bad value with spaces")).matches("[0-9a-f]{16}");
        assertThat(run("line1\nline2-injected")).matches("[0-9a-f]{16}");
        assertThat(run("short")).matches("[0-9a-f]{16}");            // < 8 位
        assertThat(run("x".repeat(65))).matches("[0-9a-f]{16}");     // > 64 位
        assertThat(run("中文id不允许")).matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("请求处理完 MDC 必须清空（线程复用，否则 id 会串到下一个请求）")
    void mdcIsClearedAfterRequest() throws Exception {
        run("abc12345DEF_6789");
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("链里抛异常也要清 MDC（finally 而不是正常路径）")
    void mdcIsClearedEvenOnFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/product/1");
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "abc12345DEF_6789");
        FilterChain boom = (req, res) -> {
            throw new IllegalStateException("模拟业务异常");
        };
        try {
            filter.doFilter(request, new MockHttpServletResponse(), boom);
        } catch (Exception expected) {
            // 预期
        }
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).as("异常路径也要清").isNull();
    }
}
