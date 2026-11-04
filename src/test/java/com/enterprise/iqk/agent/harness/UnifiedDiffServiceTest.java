package com.enterprise.iqk.agent.harness;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UnifiedDiffService 的纯单元测试（architecture-agent-harness.md：
 * 「unified diff propose / apply」）。
 *
 * <p>固化两个易漏契约：
 * 1) {@code create} 是全量替换式 diff——旧行全 {@code -}、新行全 {@code +}，
 *    没有上下文行与公共前缀对齐；
 * 2) {@code apply} 支持手写局部 patch：hunk 前的旧文件行按行号拷贝，
 *    hunk 内 {@code -} 删 / {@code +} 增 / {@code ' '} 上下文取旧文件行，
 *    最后一个 hunk 之后的旧文件行原样保留到尾部。
 */
class UnifiedDiffServiceTest {

    private final UnifiedDiffService diffService = new UnifiedDiffService();

    // ── create：全量替换式 diff ──────────────────────────────────

    @Test
    void createProducesFullReplacementHeaderAndAllMinusPlusLines() {
        String patch = diffService.create("README.md", "old line", "new line\nsecond");

        assertThat(patch).isEqualTo(
                "--- a/README.md\n"
                + "+++ b/README.md\n"
                + "@@ -1,1 +1,2 @@\n"
                + "-old line\n"
                + "+new line\n"
                + "+second\n");
    }

    @Test
    void createHandlesNullAndEmptySidesWithZeroCounts() {
        assertThat(diffService.create("new.txt", null, "content"))
                .isEqualTo("--- a/new.txt\n+++ b/new.txt\n@@ -1,0 +1,1 @@\n+content\n");

        // 旧文件多行、新内容清空 → 全部为 - 行
        assertThat(diffService.create("gone.txt", "a\nb\n", ""))
                .isEqualTo("--- a/gone.txt\n+++ b/gone.txt\n@@ -1,2 +1,0 @@\n-a\n-b\n");
    }

    @Test
    void createAndApplyRoundTripForFullReplacement() {
        String oldContent = "line1\nline2\nline3\n";
        String newContent = "alpha\nbeta\ngamma\ndelta\n";

        String applied = diffService.apply(oldContent, diffService.create("f.txt", oldContent, newContent));

        // splitLines 去掉结尾换行，join 不还原末尾 \n
        assertThat(applied).isEqualTo("alpha\nbeta\ngamma\ndelta");
    }

    @Test
    void createAndApplyRoundTripForDeletionToEmpty() {
        String applied = diffService.apply("a\nb\n", diffService.create("f.txt", "a\nb\n", ""));

        assertThat(applied).isEmpty();
    }

    // ── apply：手写局部 patch 的 hunk 语义 ───────────────────────

    @Test
    void applyCopiesLeadingOldLinesThenAppliesHunkThenKeepsTail() {
        String oldContent = "keep1\ntarget\nkeep2\nkeep3\n";
        String patch = "@@ -2,1 +2,1 @@\n-target\n+replaced\n";

        assertThat(diffService.apply(oldContent, patch))
                .isEqualTo("keep1\nreplaced\nkeep2\nkeep3");
    }

    @Test
    void applyHonorsContextLinesAndShortHunkHeaderForm() {
        String oldContent = "keep1\ntarget\nkeep2\nkeep3\n";
        // 无显式计数的短 header（@@ -1 +1 @@），含上下文行与跳过空 body 行
        String patch = "@@ -1 +1 @@\n keep1\n\n-target\n+replaced\n keep2\n";

        assertThat(diffService.apply(oldContent, patch))
                .isEqualTo("keep1\nreplaced\nkeep2\nkeep3");
    }

    @Test
    void applyConsumesOldLinesBeforeSecondHunkAndCopiesRemainder() {
        String oldContent = "a1\na2\na3\na4\na5\n";
        String patch = "@@ -1,1 +1,1 @@\n-a1\n+A1\n@@ -3,1 +3,1 @@\n-a3\n+A3\n";

        assertThat(diffService.apply(oldContent, patch))
                .isEqualTo("A1\na2\nA3\na4\na5");
    }

    @Test
    void applySkipsLinesWithoutHunkHeaderAndReturnsOriginalWhenNoHunks() {
        String oldContent = "untouched\n";

        // 没有 @@ hunk 的输入被整段跳过 → 原文返回
        assertThat(diffService.apply(oldContent, "garbage\nlines\n"))
                .isEqualTo("untouched");
    }

    @Test
    void applyRejectsMalformedHunkHeader() {
        // 以 "@@ " 开头但不符合 hunk 数字格式 → 显式报错而非静默跳过
        assertThatThrownBy(() -> diffService.apply("a\n", "@@ garbage @@\n-a\n+b\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid patch hunk header");
    }

    @Test
    void applyHandlesNullAndEmptyOldContent() {
        // 空旧文件 + 新增 hunk：oldStart 钳到 0，+ 行直接成为结果
        assertThat(diffService.apply(null, "@@ -1,0 +1,1 @@\n+fresh\n")).isEqualTo("fresh");
        assertThat(diffService.apply("", "no hunks here")).isEmpty();
    }
}
