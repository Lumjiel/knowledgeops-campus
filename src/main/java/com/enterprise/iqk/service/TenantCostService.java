package com.enterprise.iqk.service;

import com.enterprise.iqk.config.properties.CostGovernanceProperties;
import com.enterprise.iqk.domain.TenantBudget;
import com.enterprise.iqk.domain.vo.TenantBudgetUpdateVO;
import com.enterprise.iqk.domain.vo.TenantCostSummaryVO;
import com.enterprise.iqk.mapper.TenantBudgetMapper;
import com.enterprise.iqk.mapper.TenantUsageDailyMapper;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Locale;

/**
 * 租户成本治理：预算校验、用量记账、成本汇总与预算配置。
 *
 * <p>面向模型调用链路提供两个前置/后置钩子——
 * {@link #assertBudget(String, String, long, long)}（调用前硬预算断言）与
 * {@link #recordUsage(String, String, long, long, String)}（调用后按日累加用量）；
 * 面向治理后台提供 {@link #summary(String)}（月/当日用量汇总）与
 * {@link #updateBudget(TenantBudgetUpdateVO)}（预算配置变更）。</p>
 *
 * <p>调用方包括 {@code ChatController}、各 Agent/rag 服务（断言与打点）以及
 * {@code CostGovernanceController}（汇总与预算管理）。所有租户标识统一经
 * {@link TenantContext#normalize} 归一；预算缺失时按 {@link CostGovernanceProperties}
 * 的默认值惰性建行。开关未启用时 {@code assertBudget}/{@code recordUsage}
 * 直接短路，统计类方法不受开关影响。</p>
 */
@Service
@RequiredArgsConstructor
public class TenantCostService {
    private final TenantBudgetMapper tenantBudgetMapper;
    private final TenantUsageDailyMapper tenantUsageDailyMapper;
    private final CostGovernanceProperties costGovernanceProperties;

    /**
     * 模型调用前的预算断言：按本次请求的估算成本叠加当月已产生成本，
     * 若租户启用了硬限制且超出月度预算则抛出异常阻断请求。
     *
     * <p>估算成本 = {@code calculateCost(costTier, inputTokens + outputTokens)}，
     * 与 {@code monthCost} 求和后与 {@code monthlyBudgetUsd} 比较（严格大于才阻断）。
     * 成本治理开关关闭时直接返回，不做任何校验。</p>
     *
     * @param tenantId     租户标识（内部会做归一化）
     * @param costTier     计费档位，决定每千 token 单价
     * @param inputTokens  本次请求预估输入 token 数
     * @param outputTokens 本次请求预估输出 token 数
     * @throws IllegalArgumentException 租户启用硬限制且预计成本将超出月度预算
     */
    public void assertBudget(String tenantId, String costTier, long inputTokens, long outputTokens) {
        if (!costGovernanceProperties.isEnabled()) {
            return;
        }
        String tenant = TenantContext.normalize(tenantId);
        TenantBudget budget = ensureBudget(tenant);
        BigDecimal estimatedCost = calculateCost(costTier, inputTokens + outputTokens);
        BigDecimal monthCost = monthCost(tenant, YearMonth.now());
        BigDecimal projected = monthCost.add(estimatedCost);
        Integer hardLimitVal = budget.getHardLimitEnabled();
        boolean hardLimit = Integer.valueOf(1).equals(hardLimitVal);
        if (hardLimit && projected.compareTo(defaultDecimal(budget.getMonthlyBudgetUsd())) > 0) {
            throw new IllegalArgumentException("tenant budget exceeded, request blocked");
        }
    }

    /**
     * 模型调用后的用量记账：按「租户 + 当日」维度累加请求次数（+1）、
     * 输入/输出 token 与折算成本（USD），底层为按日 upsert（
     * {@link TenantUsageDailyMapper#addUsage}，ON DUPLICATE KEY 累加）。
     *
     * <p>负数 token 会被钳制为 0；成本治理开关关闭时直接返回、不记账。</p>
     *
     * @param tenantId    租户标识（内部会做归一化）
     * @param costTier    计费档位，决定每千 token 单价
     * @param inputTokens 实际输入 token 数
     * @param outputTokens 实际输出 token 数
     * @param endpointTag 调用来源标识；当前版本仅入参记录，未参与落库
     */
    public void recordUsage(String tenantId,
                            String costTier,
                            long inputTokens,
                            long outputTokens,
                            String endpointTag) {
        if (!costGovernanceProperties.isEnabled()) {
            return;
        }
        String tenant = TenantContext.normalize(tenantId);
        long safeInput = Math.max(0, inputTokens);
        long safeOutput = Math.max(0, outputTokens);
        BigDecimal cost = calculateCost(costTier, safeInput + safeOutput);
        tenantUsageDailyMapper.addUsage(
                tenant,
                LocalDate.now(),
                1,
                safeInput,
                safeOutput,
                cost
        );
    }

    /**
     * 汇总指定租户当月与当日的成本/用量，并叠加预算视图：
     * {@code budgetRemainingUsd = max(0, monthlyBudgetUsd - monthCostUsd)}，
     * {@code budgetExceeded = monthCostUsd > monthlyBudgetUsd}。
     *
     * <p>统计口径来自 {@code tenant_usage_daily} 按日期区间的 SUM（聚合 SQL
     * 已 COALESCE 兜底，此处再做 null 归零）；金额统一保留 4 位小数。
     * 该方法不受成本治理开关影响，且会触发 {@link #ensureBudget} 惰性建行。</p>
     *
     * @param tenantId 租户标识（内部会做归一化）
     * @return 当月 + 当日汇总视图，永不为 {@code null}
     */
    public TenantCostSummaryVO summary(String tenantId) {
        String tenant = TenantContext.normalize(tenantId);
        TenantBudget budget = ensureBudget(tenant);
        YearMonth now = YearMonth.now();
        LocalDate monthStart = now.atDay(1);
        LocalDate monthEnd = now.atEndOfMonth();
        LocalDate today = LocalDate.now();

        Long monthRequestCount = safeLong(tenantUsageDailyMapper.sumRequestCount(tenant, monthStart, monthEnd));
        Long monthInput = safeLong(tenantUsageDailyMapper.sumInputTokens(tenant, monthStart, monthEnd));
        Long monthOutput = safeLong(tenantUsageDailyMapper.sumOutputTokens(tenant, monthStart, monthEnd));
        BigDecimal monthCost = defaultDecimal(tenantUsageDailyMapper.sumCostUsd(tenant, monthStart, monthEnd));

        Long todayRequestCount = safeLong(tenantUsageDailyMapper.sumRequestCount(tenant, today, today));
        BigDecimal todayCost = defaultDecimal(tenantUsageDailyMapper.sumCostUsd(tenant, today, today));

        BigDecimal budgetLimit = defaultDecimal(budget.getMonthlyBudgetUsd());
        BigDecimal remaining = budgetLimit.subtract(monthCost).max(BigDecimal.ZERO);
        boolean exceeded = monthCost.compareTo(budgetLimit) > 0;

        return TenantCostSummaryVO.builder()
                .tenantId(tenant)
                .month(now.toString())
                .monthlyBudgetUsd(budgetLimit)
                .hardLimitEnabled(Integer.valueOf(1).equals(budget.getHardLimitEnabled()))
                .monthCostUsd(scale(monthCost))
                .monthRequestCount(monthRequestCount)
                .monthInputTokens(monthInput)
                .monthOutputTokens(monthOutput)
                .todayCostUsd(scale(todayCost))
                .todayRequestCount(todayRequestCount)
                .budgetRemainingUsd(scale(remaining))
                .budgetExceeded(exceeded)
                .build();
    }

    /**
     * 部分更新租户预算配置：{@code monthlyBudgetUsd} 与 {@code hardLimitEnabled}
     * 均为可选字段，传 null 表示不修改；金额必须非负。
     *
     * <p>更新成功后返回刷新后的 {@link #summary}，便于前端一次拿到最新预算视图。</p>
     *
     * @param request 预算变更载荷，不可为 null
     * @return 更新后该租户的最新成本汇总
     * @throws IllegalArgumentException 载荷为 null，或 monthlyBudgetUsd 为负数
     */
    public TenantCostSummaryVO updateBudget(TenantBudgetUpdateVO request) {
        if (request == null) {
            throw new IllegalArgumentException("budget payload is required");
        }
        String tenant = TenantContext.normalize(request.getTenantId());
        TenantBudget budget = ensureBudget(tenant);
        if (request.getMonthlyBudgetUsd() != null) {
            if (request.getMonthlyBudgetUsd().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("monthlyBudgetUsd must be non-negative");
            }
            budget.setMonthlyBudgetUsd(request.getMonthlyBudgetUsd());
        }
        if (request.getHardLimitEnabled() != null) {
            budget.setHardLimitEnabled(Boolean.TRUE.equals(request.getHardLimitEnabled()) ? 1 : 0);
        }
        budget.setUpdatedAt(LocalDateTime.now());
        tenantBudgetMapper.updateById(budget);
        return summary(tenant);
    }

    /**
     * 粗估文本 token 数：按 Unicode code point 计数后除以配置的
     * {@code tokenEstimateDivisor}（向上取整，最小为 1）。
     * 用于调用前的预算断言输入；非文本输入返回 0。
     *
     * @param text 待估算文本，可为空
     * @return 估算 token 数；文本空白时为 0，非空白时至少为 1
     */
    public long estimateTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int divisor = Math.max(1, costGovernanceProperties.getTokenEstimateDivisor());
        int length = text.codePointCount(0, text.length());
        return Math.max(1L, (length + divisor - 1L) / divisor);
    }

    /**
     * 读取租户预算配置，缺失时按 {@link CostGovernanceProperties} 的默认
     * 月度预算与硬限制开关惰性建行并回读。
     */
    private TenantBudget ensureBudget(String tenantId) {
        TenantBudget existing = tenantBudgetMapper.findByTenantId(tenantId);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        TenantBudget inserted = TenantBudget.builder()
                .tenantId(tenantId)
                .monthlyBudgetUsd(defaultDecimal(costGovernanceProperties.getDefaultMonthlyBudgetUsd()))
                .hardLimitEnabled(costGovernanceProperties.isDefaultHardLimitEnabled() ? 1 : 0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        tenantBudgetMapper.insert(inserted);
        return tenantBudgetMapper.findByTenantId(tenantId);
    }

    /** 汇总租户在指定自然月的累计成本（USD），无记录时返回 0。 */
    private BigDecimal monthCost(String tenantId, YearMonth month) {
        BigDecimal total = tenantUsageDailyMapper.sumCostUsd(tenantId, month.atDay(1), month.atEndOfMonth());
        return defaultDecimal(total);
    }

    /**
     * 按「每千 token 单价」折算成本（USD，保留 6 位小数，HALF_UP）：
     * 档位 trim + 小写，未配置的档位回退 medium 单价，medium 也未配置时
     * 使用内置兜底单价 0.0030；token 数为负时按 0 计算。
     */
    private BigDecimal calculateCost(String costTier, long totalTokens) {
        String tier = StringUtils.hasText(costTier) ? costTier.trim().toLowerCase(Locale.ROOT) : "medium";
        BigDecimal unit = costGovernanceProperties.getUsdPer1kTokens().getOrDefault(
                tier,
                costGovernanceProperties.getUsdPer1kTokens().getOrDefault("medium", new BigDecimal("0.0030"))
        );
        BigDecimal tokens = BigDecimal.valueOf(Math.max(0, totalTokens));
        return tokens.multiply(defaultDecimal(unit))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    /** null 归 0 的 Long 包装。 */
    private Long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    /** null 归 0 的 BigDecimal 包装。 */
    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 金额展示口径：null 归 0 后保留 4 位小数（HALF_UP）。 */
    private BigDecimal scale(BigDecimal value) {
        return defaultDecimal(value).setScale(4, RoundingMode.HALF_UP);
    }
}
