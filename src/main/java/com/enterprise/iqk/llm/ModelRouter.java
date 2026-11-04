package com.enterprise.iqk.llm;

import com.enterprise.iqk.config.properties.ModelRouterProperties;
import com.enterprise.iqk.domain.ModelAbExposure;
import com.enterprise.iqk.mapper.ModelAbExposureMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * File: ModelRouter.java
 * <p>
 * 模型路由器：把「逻辑档位 profile」（如 quality / balanced / economy）解析为具体模型名，
 * 屏蔽调用方对底层模型选型的感知。解析优先级：
 * <ol>
 *   <li>质量/成本 A/B 实验（{@code quality_vs_cost}）：命中触发档位时按
 *       {@code floorMod(hash(tenant|endpoint|subject), 100)} 确定性分桶，分桶只取决于
 *       三元组，同一主体稳定落在同一变体；请求档位为 {@code quality_first}/
 *       {@code cost_first} 时跳过实验、直取对应档位；</li>
 *   <li>初始档位选择：显式请求档位 → endpoint→profile 映射 → 全局默认档位；</li>
 *   <li>档位解析：路由开关关闭时直接查表（无路由则回退 {@value #DEFAULT_MODEL}）；
 *       开启时沿 fallback 链走（带环检测），链走不通再取「首个可用档位」，
 *       最终兜底 {@value #DEFAULT_MODEL}。</li>
 * </ol>
 * 每次实验命中都会尽力落一条曝光记录（{@link ModelAbExposure}），曝光失败不影响路由结果。
 * 解析结论统一封装为 {@link ModelRouteDecision}（含 reason 溯源标签）。
 */
@Service
public class ModelRouter {
    private static final String DEFAULT_MODEL = "qwen-plus";

    private final ModelRouterProperties modelRouterProperties;
    private final ModelAbExposureMapper modelAbExposureMapper;

    /**
     * 构造路由器。曝光 Mapper 通过 {@link ObjectProvider} 可选注入——
     * 缺少该 Bean（如裁剪掉 MyBatis 层的部署形态）时置为 {@code null}，
     * 路由功能照常，仅关闭曝光记录。
     */
    public ModelRouter(ModelRouterProperties modelRouterProperties,
                       ObjectProvider<ModelAbExposureMapper> modelAbExposureMapperProvider) {
        this.modelRouterProperties = modelRouterProperties;
        this.modelAbExposureMapper = initMapper(modelAbExposureMapperProvider);
    }

    /** 可选 Bean 的容错解析：provider 抛异常视为「无曝光 Mapper」而非启动失败。 */
    private static ModelAbExposureMapper initMapper(
            ObjectProvider<ModelAbExposureMapper> provider) {
        try {
            return provider.getIfAvailable();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 便捷重载：租户取 "public"、无实验分桶主体（不参与 A/B，仅走手动覆盖与档位解析）。
     *
     * @see #resolve(String, String, String, String)
     */
    public ModelRouteDecision resolve(String requestedProfile, String endpoint) {
        return resolve(requestedProfile, endpoint, "public", "");
    }

    /**
     * 主入口：解析路由决策。流程为 实验分桶 → 初始档位选择 → 按路由开关解析具体模型
     * → 拼装决策（保留实验元数据）→ 尽力记录曝光。
     *
     * @param requestedProfile 请求的档位名（大小写不敏感，可空；空则按 endpoint 映射或默认档位）
     * @param endpoint         调用方端点标识（如 rag.answer），用于 endpoint→profile 映射与实验分桶
     * @param tenantId         租户 ID，空取 "public"
     * @param subjectKey       实验分桶主体（通常为用户/会话 ID），决定 A/B 归属的稳定性
     * @return 路由决策，含 profile、模型名、成本档、是否发生回退及 reason 标签
     */
    public ModelRouteDecision resolve(String requestedProfile,
                                      String endpoint,
                                      String tenantId,
                                      String subjectKey) {
        String normalizedTenant = normalizeTenant(tenantId);
        ExperimentRouting experiment = applyExperiment(requestedProfile, endpoint, normalizedTenant, subjectKey);
        String initialProfile = normalizeProfile(selectInitialProfile(experiment.routedProfile(), endpoint));

        ModelRouteDecision baseDecision;
        if (!modelRouterProperties.isEnabled()) {
            baseDecision = resolveDisabled(initialProfile);
        } else {
            baseDecision = resolveEnabled(initialProfile);
        }

        ModelRouteDecision decision = new ModelRouteDecision(
                baseDecision.profile(),
                baseDecision.model(),
                baseDecision.costTier(),
                baseDecision.fallbackApplied(),
                baseDecision.reason(),
                experiment.experimentKey(),
                experiment.variant(),
                experiment.bucket()
        );
        saveExposure(normalizedTenant, endpoint, subjectKey, decision);
        return decision;
    }

    /**
     * 尽力记录实验曝光：仅在实验命中（experimentKey 非空）且曝光 Mapper 可用时落库；
     * 任何异常静默吞掉——曝光是旁路分析数据，绝不能影响路由主路径。
     */
    private void saveExposure(String tenantId, String endpoint, String subjectKey, ModelRouteDecision decision) {
        if (modelAbExposureMapper == null || !StringUtils.hasText(decision.experimentKey())) {
            return;
        }
        try {
            modelAbExposureMapper.insert(ModelAbExposure.builder()
                    .tenantId(normalizeTenant(tenantId))
                    .experimentKey(decision.experimentKey())
                    .subjectKey(StringUtils.hasText(subjectKey) ? subjectKey : "na")
                    .endpoint(StringUtils.hasText(endpoint) ? endpoint : "unknown")
                    .bucket(decision.experimentBucket() != null ? decision.experimentBucket() : -1)
                    .variant(StringUtils.hasText(decision.experimentVariant()) ? decision.experimentVariant() : "unknown")
                    .routedProfile(decision.profile())
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception ignored) {
            // exposure logging should never break routing path
        }
    }

    /**
     * 实验/手动覆盖路由：
     * <ul>
     *   <li>请求档位为 {@code quality_first} / {@code cost_first}（含连字符变体）时跳过实验，
     *       手动锁定 quality / economy 档位（bucket 固定 100/0 便于曝光分析）；</li>
     *   <li>实验开关开启且请求档位命中配置的触发档位时，按
     *       {@code floorMod(hash(tenant|endpoint|subject), 100) < qualityPercent} 确定性分桶，
     *       分出 quality 或 economy 变体；</li>
     *   <li>其余情况原样返回请求档位（无实验元数据）。</li>
     * </ul>
     */
    private ExperimentRouting applyExperiment(String requestedProfile,
                                              String endpoint,
                                              String tenantId,
                                              String subjectKey) {
        String normalizedRequested = normalizeProfile(requestedProfile);
        if ("quality_first".equals(normalizedRequested) || "quality-priority".equals(normalizedRequested)) {
            return new ExperimentRouting("quality", "manual_quality_first", "quality", 100);
        }
        if ("cost_first".equals(normalizedRequested) || "cost-priority".equals(normalizedRequested)) {
            return new ExperimentRouting("economy", "manual_cost_first", "cost", 0);
        }

        ModelRouterProperties.AbExperiment experiment = modelRouterProperties.getQualityCostExperiment();
        String triggerProfile = normalizeProfile(experiment.getTriggerProfile());
        boolean triggerMatched = StringUtils.hasText(triggerProfile) && triggerProfile.equals(normalizedRequested);
        if (!experiment.isEnabled() || !triggerMatched) {
            return new ExperimentRouting(normalizedRequested, "", "", null);
        }

        int qualityPercent = Math.min(100, Math.max(0, experiment.getQualityPercent()));
        String normalizedEndpoint = StringUtils.hasText(endpoint) ? normalizeProfile(endpoint) : "na";
        String normalizedSubject = StringUtils.hasText(subjectKey) ? subjectKey.trim() : "na";
        int rawHash = (tenantId + "|" + normalizedEndpoint + "|" + normalizedSubject).hashCode();
        int bucket = Math.floorMod(rawHash, 100);
        boolean qualityVariant = bucket < qualityPercent;
        String variant = qualityVariant ? "quality" : "cost";
        String routedProfile = qualityVariant ? "quality" : "economy";
        return new ExperimentRouting(
                routedProfile,
                StringUtils.hasText(experiment.getExperimentKey()) ? experiment.getExperimentKey() : "quality_vs_cost",
                variant,
                bucket
        );
    }

    /**
     * 路由开关关闭时的解析：直接查档位表（不校验 enabled）；无该档位或未配模型时
     * 回退 {@value #DEFAULT_MODEL}（reason: router_disabled_profile_direct / router_disabled_default）。
     */
    private ModelRouteDecision resolveDisabled(String initialProfile) {
        if (hasRoute(initialProfile)) {
            ModelRouterProperties.RouteProfile profile = modelRouterProperties.getProfiles().get(initialProfile);
            return new ModelRouteDecision(initialProfile,
                    profile.getModel(),
                    safeCostTier(profile.getCostTier()),
                    false,
                    "router_disabled_profile_direct",
                    "",
                    "",
                    null);
        }
        return new ModelRouteDecision(initialProfile, DEFAULT_MODEL, "balanced", false, "router_disabled_default", "", "", null);
    }

    /**
     * 路由开关开启时的解析，按三级降级：
     * <ol>
     *   <li>沿 fallback 链迭代（LinkedHashSet 记录已访问档位防环），取首个 enabled 且已配模型的档位；
     *       离开初始档位即视为回退（reason: fallback_chain / profile_match）；</li>
     *   <li>链断裂（fallback 为空或成环）时遍历档位表取第一个可用档位（reason: first_enabled_fallback）；</li>
     *   <li>仍无可用档位则兜底 {@value #DEFAULT_MODEL}（reason: default_model_fallback）。</li>
     * </ol>
     */
    private ModelRouteDecision resolveEnabled(String initialProfile) {
        Set<String> visited = new LinkedHashSet<>();
        String current = initialProfile;
        while (StringUtils.hasText(current) && !visited.contains(current)) {
            visited.add(current);
            ModelRouterProperties.RouteProfile route = modelRouterProperties.getProfiles().get(current);
            if (route != null && route.isEnabled() && StringUtils.hasText(route.getModel())) {
                return new ModelRouteDecision(current,
                        route.getModel(),
                        safeCostTier(route.getCostTier()),
                        !current.equals(initialProfile),
                        !current.equals(initialProfile) ? "fallback_chain" : "profile_match",
                        "",
                        "",
                        null);
            }
            String fallback = route == null ? "" : normalizeProfile(route.getFallbackProfile());
            if (!StringUtils.hasText(fallback)) {
                fallback = normalizeProfile(modelRouterProperties.getDefaultProfile());
            }
            if (!StringUtils.hasText(fallback) || visited.contains(fallback)) {
                break;
            }
            current = fallback;
        }
        for (Map.Entry<String, ModelRouterProperties.RouteProfile> entry : modelRouterProperties.getProfiles().entrySet()) {
            ModelRouterProperties.RouteProfile route = entry.getValue();
            if (route != null && route.isEnabled() && StringUtils.hasText(route.getModel())) {
                return new ModelRouteDecision(entry.getKey(),
                        route.getModel(),
                        safeCostTier(route.getCostTier()),
                        !entry.getKey().equals(initialProfile),
                        "first_enabled_fallback",
                        "",
                        "",
                        null);
            }
        }
        return new ModelRouteDecision(initialProfile, DEFAULT_MODEL, "balanced", true, "default_model_fallback", "", "", null);
    }

    /** 初始档位三级选择：显式请求档位 → endpoint→profile 配置映射 → 全局默认档位。 */
    private String selectInitialProfile(String requestedProfile, String endpoint) {
        if (StringUtils.hasText(requestedProfile)) {
            return requestedProfile;
        }
        if (StringUtils.hasText(endpoint)) {
            String endpointProfile = modelRouterProperties.getEndpointProfiles().get(normalizeProfile(endpoint));
            if (StringUtils.hasText(endpointProfile)) {
                return endpointProfile;
            }
        }
        return modelRouterProperties.getDefaultProfile();
    }

    /** 档位是否存在且已配置模型名（供关闭开关时的直接查表判断）。 */
    private boolean hasRoute(String profile) {
        return StringUtils.hasText(profile)
                && modelRouterProperties.getProfiles().containsKey(profile)
                && StringUtils.hasText(modelRouterProperties.getProfiles().get(profile).getModel());
    }

    /** 档位/endpoint 名归一化：trim + 小写；空返回空串。 */
    private String normalizeProfile(String profile) {
        if (!StringUtils.hasText(profile)) {
            return "";
        }
        return profile.trim().toLowerCase(Locale.ROOT);
    }

    /** 成本档空值兜底为 "balanced"。 */
    private String safeCostTier(String costTier) {
        return StringUtils.hasText(costTier) ? costTier : "balanced";
    }

    /** 租户归一化：trim，空取 "public"（仅本地 trim，不做大小写折叠）。 */
    private String normalizeTenant(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return "public";
        }
        return tenantId.trim();
    }

    /**
     * 路由决策（对外返回值）：profile/model/costTier 为最终选型；fallbackApplied + reason
     * 记录回退情况与原因标签（profile_match / fallback_chain / first_enabled_fallback /
     * default_model_fallback / router_disabled_*）；experimentKey/variant/bucket 为 A/B 实验
     * 元数据，未参与实验时 key/variant 为空串、bucket 为 {@code null}。
     */
    public record ModelRouteDecision(String profile,
                                     String model,
                                     String costTier,
                                     boolean fallbackApplied,
                                     String reason,
                                     String experimentKey,
                                     String experimentVariant,
                                     Integer experimentBucket) {
    }

    /** 实验分桶中间结果：routedProfile 为实验后档位，key/variant/bucket 为空值时表示未参与实验。 */
    private record ExperimentRouting(String routedProfile,
                                     String experimentKey,
                                     String variant,
                                     Integer bucket) {
    }
}
