package com.enterprise.iqk.service;

import com.enterprise.iqk.config.properties.CostGovernanceProperties;
import com.enterprise.iqk.domain.TenantBudget;
import com.enterprise.iqk.domain.vo.TenantBudgetUpdateVO;
import com.enterprise.iqk.domain.vo.TenantCostSummaryVO;
import com.enterprise.iqk.mapper.TenantBudgetMapper;
import com.enterprise.iqk.mapper.TenantUsageDailyMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TenantCostService 的纯单元测试：外部协作者（两个 Mapper）全部 mock，
 * {@link CostGovernanceProperties} 用真实实例（与生产默认值一致）。
 *
 * <p>与被测实现的两个隐式契约用测试固化：
 * 1) ensureBudget 对不存在的租户先 insert 再二次查询，二次查询若返回 null，
 *    assertBudget 会因 hardLimitEnabled 为 null 而按"不启用硬限制"放行（summary 则会 NPE），
 *    因此涉及 summary/updateBudget 的用例必须把二次查询 stub 成非空；
 * 2) calculateCost 的费率表按 tier 小写回退到 medium，费率表整体为空时回退到内置 0.0030。
 */
class TenantCostServiceTest {

    private final TenantBudgetMapper tenantBudgetMapper = mock(TenantBudgetMapper.class);
    private final TenantUsageDailyMapper tenantUsageDailyMapper = mock(TenantUsageDailyMapper.class);
    private final CostGovernanceProperties properties = new CostGovernanceProperties();
    private final TenantCostService service =
            new TenantCostService(tenantBudgetMapper, tenantUsageDailyMapper, properties);

    private static final LocalDate MONTH_START = YearMonth.now().atDay(1);
    private static final LocalDate MONTH_END = YearMonth.now().atEndOfMonth();
    private static final LocalDate TODAY = LocalDate.now();

    private TenantBudget budget(String tenantId, String monthlyBudgetUsd, int hardLimitEnabled) {
        return TenantBudget.builder()
                .id(1L)
                .tenantId(tenantId)
                .monthlyBudgetUsd(new BigDecimal(monthlyBudgetUsd))
                .hardLimitEnabled(hardLimitEnabled)
                .build();
    }

    /** 区分"本月区间"与"今日区间"两类 sum 调用（月初当天两区间重合，也保持一致）。 */
    private void stubUsageSums(Long monthRequestCount, Long todayRequestCount,
                               Long monthInput, Long monthOutput,
                               String monthCostUsd, String todayCostUsd) {
        when(tenantUsageDailyMapper.sumRequestCount(anyString(), any(), any())).thenAnswer(inv ->
                TODAY.equals(inv.getArgument(1)) && TODAY.equals(inv.getArgument(2))
                        ? todayRequestCount : monthRequestCount);
        when(tenantUsageDailyMapper.sumInputTokens(anyString(), any(), any())).thenReturn(monthInput);
        when(tenantUsageDailyMapper.sumOutputTokens(anyString(), any(), any())).thenReturn(monthOutput);
        when(tenantUsageDailyMapper.sumCostUsd(anyString(), any(), any())).thenAnswer(inv ->
                TODAY.equals(inv.getArgument(1)) && TODAY.equals(inv.getArgument(2))
                        ? new BigDecimal(todayCostUsd) : new BigDecimal(monthCostUsd));
    }

    // ── estimateTokens()：除数取整与码点计数 ──────────────────────

    @Test
    void estimateTokensReturnsZeroForBlankText() {
        assertThat(service.estimateTokens(null)).isZero();
        assertThat(service.estimateTokens("")).isZero();
        assertThat(service.estimateTokens("   \n\t")).isZero();
    }

    @Test
    void estimateTokensRoundsUpByDivisor() {
        // 默认 divisor=4：(10 + 3) / 4 = 3
        assertThat(service.estimateTokens("0123456789")).isEqualTo(3L);
        // 整除时不多算：(8 + 3) / 4 = 2
        assertThat(service.estimateTokens("01234567")).isEqualTo(2L);
        // 最少 1 个 token
        assertThat(service.estimateTokens("a")).isEqualTo(1L);
    }

    @Test
    void estimateTokensCountsCodePointsNotChars() {
        properties.setTokenEstimateDivisor(1);
        // 代理对：char 长度为 2，码点数为 1
        assertThat(service.estimateTokens("\uD83D\uDE00")).isEqualTo(1L);
    }

    @Test
    void estimateTokensClampsNonPositiveDivisorToOne() {
        properties.setTokenEstimateDivisor(0);
        assertThat(service.estimateTokens("abc")).isEqualTo(3L);
    }

    // ── assertBudget()：硬限制拦截与降级路径 ──────────────────────

    @Test
    void assertBudgetSkipsEverythingWhenGovernanceDisabled() {
        properties.setEnabled(false);
        service.assertBudget("t1", "high", 1000, 1000);
        verifyNoInteractions(tenantBudgetMapper, tenantUsageDailyMapper);
    }

    @Test
    void assertBudgetBlocksWhenProjectedCostExceedsHardLimit() {
        when(tenantBudgetMapper.findByTenantId("t1"))
                .thenReturn(budget("t1", "25.0000", 1));
        when(tenantUsageDailyMapper.sumCostUsd(eq("t1"), any(), any()))
                .thenReturn(new BigDecimal("10.00"));
        // high 费率 0.0100/1k：2,000,000 tokens → $20，10 + 20 > 25
        assertThatThrownBy(() -> service.assertBudget("t1", "high", 1_200_000, 800_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenant budget exceeded, request blocked");
    }

    @Test
    void assertBudgetAllowsProjectedCostExactlyAtLimit() {
        when(tenantBudgetMapper.findByTenantId("t1"))
                .thenReturn(budget("t1", "20.0000", 1));
        when(tenantUsageDailyMapper.sumCostUsd(eq("t1"), any(), any()))
                .thenReturn(new BigDecimal("10.00"));
        // 预估 10（1,000,000 tokens × 0.0100/1k）+ 已花 10 == 20，仅严格大于才拦截
        service.assertBudget("t1", "high", 500_000, 500_000);
    }

    @Test
    void assertBudgetDoesNotBlockWhenHardLimitDisabled() {
        when(tenantBudgetMapper.findByTenantId("t1"))
                .thenReturn(budget("t1", "0.0000", 0));
        when(tenantUsageDailyMapper.sumCostUsd(eq("t1"), any(), any()))
                .thenReturn(new BigDecimal("99.00"));
        service.assertBudget("t1", "high", 100000, 100000);
    }

    @Test
    void assertBudgetNormalizesTenantIdAndFallsBackToMediumRate() {
        when(tenantBudgetMapper.findByTenantId("t1"))
                .thenReturn(budget("t1", "25.0000", 1));
        when(tenantUsageDailyMapper.sumCostUsd(eq("t1"), any(), any()))
                .thenReturn(new BigDecimal("10.00"));
        // 未知名费率回退 medium（0.0030/1k）：2000 tokens → $0.006，10 + 0.006 <= 25，不拦截
        service.assertBudget(" t1 ", "ultra", 1000, 1000);
        // normalize 后的租户名才查库
        verify(tenantBudgetMapper).findByTenantId("t1");
        verify(tenantUsageDailyMapper).sumCostUsd(eq("t1"), eq(MONTH_START), eq(MONTH_END));
    }

    @Test
    void assertBudgetInsertsDefaultBudgetForUnknownTenant() {
        TenantBudget inserted = budget("new-t", "25.0000", 0);
        when(tenantBudgetMapper.findByTenantId("new-t")).thenReturn(null, inserted);

        service.assertBudget("new-t", "medium", 100, 100);

        ArgumentCaptor<TenantBudget> captor = ArgumentCaptor.forClass(TenantBudget.class);
        verify(tenantBudgetMapper).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("new-t");
        assertThat(captor.getValue().getMonthlyBudgetUsd()).isEqualByComparingTo("25.0000");
        assertThat(captor.getValue().getHardLimitEnabled()).isZero();
    }

    // ── recordUsage()：用量落库与负数防御 ─────────────────────────

    @Test
    void recordUsageSkipsDatabaseWhenGovernanceDisabled() {
        properties.setEnabled(false);
        service.recordUsage("t1", "high", 100, 100, "chat");
        verifyNoInteractions(tenantBudgetMapper, tenantUsageDailyMapper);
    }

    @Test
    void recordUsageWritesClampedTokensAndTieredCost() {
        service.recordUsage("t1", " HIGH ", 1500, -300, "chat");
        // 负数清零、tier 去空白转小写：1500 tokens * 0.0100 / 1000 = 0.015000
        ArgumentCaptor<BigDecimal> costCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(tenantUsageDailyMapper).addUsage(eq("t1"), eq(TODAY), eq(1L), eq(1500L), eq(0L), costCaptor.capture());
        assertThat(costCaptor.getValue()).isEqualByComparingTo("0.015000");
    }

    @Test
    void recordUsageFallsBackToMediumRateWhenTierMissing() {
        service.recordUsage("t1", null, 600, 400, "chat");
        ArgumentCaptor<BigDecimal> costCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(tenantUsageDailyMapper).addUsage(eq("t1"), eq(TODAY), eq(1L), eq(600L), eq(400L), costCaptor.capture());
        assertThat(costCaptor.getValue()).isEqualByComparingTo("0.003000");
    }

    // ── summary()：汇总口径与缩放 ─────────────────────────────────

    @Test
    void summaryAggregatesMonthAndTodayWithScaledCosts() {
        String tenant = "tenant-a";
        when(tenantBudgetMapper.findByTenantId(tenant))
                .thenReturn(budget(tenant, "100.00", 1));
        stubUsageSums(12L, 2L, 100L, 200L, "33.456789", "1.234567");

        TenantCostSummaryVO vo = service.summary(tenant);

        assertThat(vo.getTenantId()).isEqualTo(tenant);
        assertThat(vo.getMonth()).isEqualTo(YearMonth.now().toString());
        assertThat(vo.getMonthlyBudgetUsd()).isEqualByComparingTo("100.00");
        assertThat(vo.getHardLimitEnabled()).isTrue();
        assertThat(vo.getMonthCostUsd()).isEqualByComparingTo("33.4568"); // 4 位 HALF_UP
        assertThat(vo.getMonthRequestCount()).isEqualTo(12L);
        assertThat(vo.getMonthInputTokens()).isEqualTo(100L);
        assertThat(vo.getMonthOutputTokens()).isEqualTo(200L);
        assertThat(vo.getTodayCostUsd()).isEqualByComparingTo("1.2346");
        assertThat(vo.getTodayRequestCount()).isEqualTo(2L);
        // remaining = max(0, 100 - 33.456789) = 66.543211 → 66.5432
        assertThat(vo.getBudgetRemainingUsd()).isEqualByComparingTo("66.5432");
        assertThat(vo.getBudgetExceeded()).isFalse();
    }

    @Test
    void summaryReportsExceededAndZeroRemainingWhenCostOverBudget() {
        String tenant = "tenant-a";
        when(tenantBudgetMapper.findByTenantId(tenant))
                .thenReturn(budget(tenant, "10.00", 0));
        stubUsageSums(5L, 1L, 10L, 10L, "12.0000", "2.0000");

        TenantCostSummaryVO vo = service.summary(tenant);

        assertThat(vo.getBudgetExceeded()).isTrue();
        assertThat(vo.getBudgetRemainingUsd()).isEqualByComparingTo("0");
        assertThat(vo.getHardLimitEnabled()).isFalse();
    }

    @Test
    void summaryTreatsNullSumsAsZeroAndInsertsBudgetForUnknownTenant() {
        String tenant = "brand-new";
        TenantBudget inserted = budget(tenant, "25.0000", 0);
        when(tenantBudgetMapper.findByTenantId(tenant)).thenReturn(null, inserted);
        when(tenantUsageDailyMapper.sumRequestCount(anyString(), any(), any())).thenReturn(null);
        when(tenantUsageDailyMapper.sumInputTokens(anyString(), any(), any())).thenReturn(null);
        when(tenantUsageDailyMapper.sumOutputTokens(anyString(), any(), any())).thenReturn(null);
        when(tenantUsageDailyMapper.sumCostUsd(anyString(), any(), any())).thenReturn(null);

        TenantCostSummaryVO vo = service.summary(tenant);

        assertThat(vo.getMonthRequestCount()).isZero();
        assertThat(vo.getMonthInputTokens()).isZero();
        assertThat(vo.getMonthOutputTokens()).isZero();
        assertThat(vo.getMonthCostUsd()).isEqualByComparingTo("0");
        assertThat(vo.getTodayCostUsd()).isEqualByComparingTo("0");
        assertThat(vo.getTodayRequestCount()).isZero();
        assertThat(vo.getBudgetRemainingUsd()).isEqualByComparingTo("25.0000");
        assertThat(vo.getBudgetExceeded()).isFalse();
        verify(tenantBudgetMapper).insert(any(TenantBudget.class));
    }

    // ── updateBudget()：入参校验与字段合并 ────────────────────────

    @Test
    void updateBudgetRejectsNullPayloadBeforeTouchingMappers() {
        assertThatThrownBy(() -> service.updateBudget(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("budget payload is required");
        verifyNoInteractions(tenantBudgetMapper, tenantUsageDailyMapper);
    }

    @Test
    void updateBudgetRejectsNegativeMonthlyBudget() {
        String tenant = "t1";
        when(tenantBudgetMapper.findByTenantId(tenant)).thenReturn(budget(tenant, "25.0000", 0));
        TenantBudgetUpdateVO request = new TenantBudgetUpdateVO();
        request.setTenantId(tenant);
        request.setMonthlyBudgetUsd(new BigDecimal("-0.01"));

        assertThatThrownBy(() -> service.updateBudget(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("monthlyBudgetUsd must be non-negative");
        verify(tenantBudgetMapper, never()).updateById(any(TenantBudget.class));
    }

    @Test
    void updateBudgetMergesProvidedFieldsAndReturnsFreshSummary() {
        String tenant = "t1";
        when(tenantBudgetMapper.findByTenantId(tenant))
                .thenReturn(budget(tenant, "25.0000", 0));
        stubUsageSums(0L, 0L, 0L, 0L, "0", "0");
        TenantBudgetUpdateVO request = new TenantBudgetUpdateVO();
        request.setTenantId(tenant);
        request.setMonthlyBudgetUsd(new BigDecimal("50"));
        request.setHardLimitEnabled(Boolean.TRUE);

        TenantCostSummaryVO vo = service.updateBudget(request);

        ArgumentCaptor<TenantBudget> captor = ArgumentCaptor.forClass(TenantBudget.class);
        verify(tenantBudgetMapper).updateById(captor.capture());
        assertThat(captor.getValue().getMonthlyBudgetUsd()).isEqualByComparingTo("50");
        assertThat(captor.getValue().getHardLimitEnabled()).isEqualTo(1); // Boolean → 1/0
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
        // 返回值是更新后的最新 summary
        assertThat(vo.getTenantId()).isEqualTo(tenant);
        assertThat(vo.getMonthlyBudgetUsd()).isEqualByComparingTo("50");
        assertThat(vo.getHardLimitEnabled()).isTrue();
    }

    @Test
    void updateBudgetKeepsExistingFieldsWhenRequestFieldsAreNull() {
        String tenant = "t1";
        when(tenantBudgetMapper.findByTenantId(tenant))
                .thenReturn(budget(tenant, "25.0000", 1));
        stubUsageSums(0L, 0L, 0L, 0L, "0", "0");
        TenantBudgetUpdateVO request = new TenantBudgetUpdateVO();
        request.setTenantId(tenant);

        service.updateBudget(request);

        ArgumentCaptor<TenantBudget> captor = ArgumentCaptor.forClass(TenantBudget.class);
        verify(tenantBudgetMapper).updateById(captor.capture());
        assertThat(captor.getValue().getMonthlyBudgetUsd()).isEqualByComparingTo("25.0000");
        assertThat(captor.getValue().getHardLimitEnabled()).isEqualTo(1);
    }
}
