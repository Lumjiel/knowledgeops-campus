package com.enterprise.iqk.llm;

import com.enterprise.iqk.config.properties.ModelRouterProperties;
import com.enterprise.iqk.domain.ModelAbExposure;
import com.enterprise.iqk.mapper.ModelAbExposureMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelRouterTest {

    @Test
    void shouldResolveByRequestedProfile() {
        ModelRouter router = new ModelRouter(buildProperties(true), emptyMapperProvider());

        ModelRouter.ModelRouteDecision decision = router.resolve("quality", "chat");

        assertEquals("quality", decision.profile());
        assertEquals("qwen-max", decision.model());
        assertEquals("high", decision.costTier());
        assertTrue(!decision.fallbackApplied());
    }

    @Test
    void shouldFallbackToBalancedWhenProfileDisabled() {
        ModelRouterProperties properties = buildProperties(true);
        properties.getProfiles().get("quality").setEnabled(false);
        ModelRouter router = new ModelRouter(properties, emptyMapperProvider());

        ModelRouter.ModelRouteDecision decision = router.resolve("quality", "chat");

        assertEquals("balanced", decision.profile());
        assertEquals("qwen-plus", decision.model());
        assertTrue(decision.fallbackApplied());
    }

    @Test
    void shouldUseEndpointDefaultWhenNoRequestedProfile() {
        ModelRouter router = new ModelRouter(buildProperties(true), emptyMapperProvider());

        ModelRouter.ModelRouteDecision decision = router.resolve("", "service");

        assertEquals("quality", decision.profile());
        assertEquals("qwen-max", decision.model());
    }

    // ── 手动 profile 覆盖 ────────────────────────────────────────

    @Test
    void shouldForceQualityProfileForManualQualityFirstRequest() {
        ModelRouter router = new ModelRouter(buildProperties(true), emptyMapperProvider());

        ModelRouter.ModelRouteDecision decision = router.resolve("QUALITY-PRIORITY ", "chat");

        assertEquals("quality", decision.profile());
        assertEquals("qwen-max", decision.model());
        assertEquals("manual_quality_first", decision.experimentKey());
        assertEquals("quality", decision.experimentVariant());
        assertEquals(100, decision.experimentBucket());
    }

    @Test
    void shouldForceEconomyProfileForManualCostFirstRequest() {
        ModelRouter router = new ModelRouter(buildProperties(true), emptyMapperProvider());

        ModelRouter.ModelRouteDecision decision = router.resolve("cost_first", "chat");

        assertEquals("economy", decision.profile());
        assertEquals("qwen-turbo", decision.model());
        assertEquals("manual_cost_first", decision.experimentKey());
        assertEquals("cost", decision.experimentVariant());
        assertEquals(0, decision.experimentBucket());
    }

    // ── A/B 实验桶 ───────────────────────────────────────────────

    /**
     * 行为钉住：桶位由 (tenant|endpoint|subject).hashCode() 的 floorMod(100) 决定，
     * 这是曝光对账与实验复现所依赖的稳定性契约——若分桶公式变更，请同步修改本测试。
     */
    @Test
    void shouldRouteAbExperimentByStableBucket() {
        ModelRouter router = new ModelRouter(buildProperties(true), emptyMapperProvider());

        int expectedBucket = Math.floorMod("tenant-a|chat|user-1".hashCode(), 100);
        ModelRouter.ModelRouteDecision decision = router.resolve("ab_auto", "chat", "tenant-a", "user-1");

        assertEquals(expectedBucket, decision.experimentBucket());
        assertEquals("quality_vs_cost", decision.experimentKey());
        assertEquals(expectedBucket < 50 ? "quality" : "cost", decision.experimentVariant());
        assertEquals(expectedBucket < 50 ? "quality" : "economy", decision.profile());
    }

    @Test
    void shouldRouteAllSubjectsToQualityWhenQualityPercentIs100() {
        ModelRouterProperties properties = buildProperties(true);
        properties.getQualityCostExperiment().setQualityPercent(100);
        ModelRouter router = new ModelRouter(properties, emptyMapperProvider());

        ModelRouter.ModelRouteDecision decision = router.resolve("ab_auto", "chat", "tenant-a", "user-1");

        assertEquals("quality", decision.experimentVariant());
        assertEquals("quality", decision.profile());
    }

    @Test
    void shouldRouteAllSubjectsToCostWhenQualityPercentIs0() {
        ModelRouterProperties properties = buildProperties(true);
        properties.getQualityCostExperiment().setQualityPercent(0);
        ModelRouter router = new ModelRouter(properties, emptyMapperProvider());

        ModelRouter.ModelRouteDecision decision = router.resolve("ab_auto", "chat", "tenant-a", "user-1");

        assertEquals("cost", decision.experimentVariant());
        assertEquals("economy", decision.profile());
    }

    @Test
    void shouldSkipExperimentWhenDisabledOrTriggerProfileMismatched() {
        ModelRouterProperties disabledExperiment = buildProperties(true);
        disabledExperiment.getQualityCostExperiment().setEnabled(false);
        ModelRouter disabledRouter = new ModelRouter(disabledExperiment, emptyMapperProvider());
        ModelRouter.ModelRouteDecision disabled = disabledRouter.resolve("ab_auto", "chat", "tenant-a", "u");

        assertEquals("", disabled.experimentKey());
        assertEquals("", disabled.experimentVariant());
        assertNull(disabled.experimentBucket());
        // 实验关闭时 ab_auto 不命中任何 profile → 走 defaultProfile("balanced") 链
        assertEquals("balanced", disabled.profile());
        assertTrue(disabled.fallbackApplied());

        // 实验开启但请求 profile 不是 triggerProfile("ab_auto")
        ModelRouter router = new ModelRouter(buildProperties(true), emptyMapperProvider());
        ModelRouter.ModelRouteDecision mismatched = router.resolve("quality", "chat", "tenant-a", "u");

        assertEquals("", mismatched.experimentKey());
        assertNull(mismatched.experimentBucket());
        assertEquals("quality", mismatched.profile());
    }

    // ── fallback 链 ──────────────────────────────────────────────

    @Test
    void shouldFollowFallbackChainAcrossMultipleDisabledProfiles() {
        ModelRouterProperties properties = buildProperties(true);
        properties.getProfiles().get("quality").setEnabled(false);
        properties.getProfiles().get("balanced").setEnabled(false);
        ModelRouter router = new ModelRouter(properties, emptyMapperProvider());

        ModelRouter.ModelRouteDecision decision = router.resolve("quality", "chat");

        // quality(禁用) -> balanced(禁用) -> economy(启用)
        assertEquals("economy", decision.profile());
        assertEquals("qwen-turbo", decision.model());
        assertEquals("low", decision.costTier());
        assertTrue(decision.fallbackApplied());
        assertEquals("fallback_chain", decision.reason());
    }

    @Test
    void shouldRouteUnknownProfileThroughDefaultProfileChain() {
        ModelRouter router = new ModelRouter(buildProperties(true), emptyMapperProvider());

        ModelRouter.ModelRouteDecision decision = router.resolve("nonexistent", "chat");

        // 未知 profile 落 defaultProfile("balanced")，且视为链式回退
        assertEquals("balanced", decision.profile());
        assertEquals("qwen-plus", decision.model());
        assertTrue(decision.fallbackApplied());
        assertEquals("fallback_chain", decision.reason());
    }

    @Test
    void shouldUseDefaultModelWhenWholeChainAndAllProfilesDisabled() {
        ModelRouterProperties properties = buildProperties(true);
        properties.getProfiles().values().forEach(p -> p.setEnabled(false));
        ModelRouter router = new ModelRouter(properties, emptyMapperProvider());

        // 全链路禁用且 profiles 里没有任何可用项 → 兜底默认模型
        ModelRouter.ModelRouteDecision defaulted = router.resolve("quality", "chat");
        assertEquals("quality", defaulted.profile());
        assertEquals("qwen-plus", defaulted.model());
        assertEquals("balanced", defaulted.costTier());
        assertTrue(defaulted.fallbackApplied());
        assertEquals("default_model_fallback", defaulted.reason());
    }

    @Test
    void shouldUseFirstEnabledProfileWhenChainBrokenButProfilesAvailable() {
        // 链路（quality→balanced→economy）全部禁用，但 profiles 表中还有链外的启用项：
        // "first enabled fallback" 按 profiles 插入顺序取第一个可用项（premium）
        ModelRouterProperties properties = buildProperties(true);
        properties.setProfiles(orderedProfilesWithChainDisabledAndExtraEnabled());
        ModelRouter router = new ModelRouter(properties, emptyMapperProvider());

        ModelRouter.ModelRouteDecision decision = router.resolve("quality", "chat");

        assertEquals("premium", decision.profile());
        assertEquals("qwen-long", decision.model());
        assertTrue(decision.fallbackApplied());
        assertEquals("first_enabled_fallback", decision.reason());
    }

    // ── 路由器禁用 ───────────────────────────────────────────────

    @Test
    void shouldUseDirectProfileRouteWhenRouterDisabled() {
        ModelRouter router = new ModelRouter(buildProperties(false), emptyMapperProvider());

        ModelRouter.ModelRouteDecision decision = router.resolve("quality", "chat");

        assertEquals("quality", decision.profile());
        assertEquals("qwen-max", decision.model());
        assertEquals("high", decision.costTier());
        assertEquals(false, decision.fallbackApplied());
        assertEquals("router_disabled_profile_direct", decision.reason());
        assertEquals("", decision.experimentKey());
    }

    /**
     * 行为钉住：路由器禁用时 hasRoute 只看 profile 是否有 model，不看 isEnabled——
     * 即使 profile 被禁用也直连其模型。若未来收紧该语义，请同步修改本测试。
     */
    @Test
    void shouldIgnoreProfileDisabledFlagWhenRouterDisabled() {
        ModelRouterProperties properties = buildProperties(false);
        properties.getProfiles().get("quality").setEnabled(false);
        ModelRouter router = new ModelRouter(properties, emptyMapperProvider());

        ModelRouter.ModelRouteDecision decision = router.resolve("quality", "chat");

        assertEquals("qwen-max", decision.model());
        assertEquals("router_disabled_profile_direct", decision.reason());
    }

    @Test
    void shouldUseDefaultModelWhenRouterDisabledAndProfileUnknown() {
        ModelRouter router = new ModelRouter(buildProperties(false), emptyMapperProvider());

        ModelRouter.ModelRouteDecision decision = router.resolve("nonexistent", "chat");

        assertEquals("nonexistent", decision.profile());
        assertEquals("qwen-plus", decision.model());
        assertEquals("balanced", decision.costTier());
        assertEquals(false, decision.fallbackApplied());
        assertEquals("router_disabled_default", decision.reason());
    }

    // ── 曝光落库 ─────────────────────────────────────────────────

    @Test
    void shouldSaveExposureForExperimentRoutingWithDefaults() {
        ModelAbExposureMapper mapper = mock(ModelAbExposureMapper.class);
        ModelRouter router = new ModelRouter(buildProperties(true), mapperProvider(mapper));

        // subjectKey/endpoint 均为空白 → 分桶键取 "na"，但命中实验后 bucket/variant 是真实计算值
        int expectedBucket = Math.floorMod("tenant-a|na|na".hashCode(), 100);
        router.resolve("ab_auto", "", "tenant-a", "  ");

        ArgumentCaptor<ModelAbExposure> captor = ArgumentCaptor.forClass(ModelAbExposure.class);
        verify(mapper).insert(captor.capture());
        ModelAbExposure exposure = captor.getValue();
        assertEquals("tenant-a", exposure.getTenantId());
        assertEquals("quality_vs_cost", exposure.getExperimentKey());
        assertEquals("na", exposure.getSubjectKey());
        assertEquals("unknown", exposure.getEndpoint());
        assertEquals(expectedBucket, exposure.getBucket());
        assertEquals(expectedBucket < 50 ? "quality" : "cost", exposure.getVariant());
        assertEquals(expectedBucket < 50 ? "quality" : "economy", exposure.getRoutedProfile());
    }

    @Test
    void shouldSaveExposureForManualOverrideWithNormalizedTenant() {
        ModelAbExposureMapper mapper = mock(ModelAbExposureMapper.class);
        ModelRouter router = new ModelRouter(buildProperties(true), mapperProvider(mapper));

        router.resolve("quality_first", "chat", "  ", "user-7");

        ArgumentCaptor<ModelAbExposure> captor = ArgumentCaptor.forClass(ModelAbExposure.class);
        verify(mapper).insert(captor.capture());
        ModelAbExposure exposure = captor.getValue();
        assertEquals("public", exposure.getTenantId());
        assertEquals("manual_quality_first", exposure.getExperimentKey());
        assertEquals("user-7", exposure.getSubjectKey());
        assertEquals("chat", exposure.getEndpoint());
        assertEquals(100, exposure.getBucket());
        assertEquals("quality", exposure.getVariant());
        assertEquals("quality", exposure.getRoutedProfile());
    }

    @Test
    void shouldNotSaveExposureWithoutExperiment() {
        ModelAbExposureMapper mapper = mock(ModelAbExposureMapper.class);
        ModelRouter router = new ModelRouter(buildProperties(true), mapperProvider(mapper));

        router.resolve("quality", "chat", "tenant-a", "user-1");

        verifyNoInteractions(mapper);
    }

    @Test
    void shouldSwallowExposureSaveFailure() {
        ModelAbExposureMapper mapper = mock(ModelAbExposureMapper.class);
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(ModelAbExposure.class));
        ModelRouter router = new ModelRouter(buildProperties(true), mapperProvider(mapper));

        // 曝光落库失败不得阻断路由
        ModelRouter.ModelRouteDecision decision = router.resolve("quality_first", "chat", "tenant-a", "user-1");

        assertEquals("quality", decision.profile());
        verify(mapper).insert(any(ModelAbExposure.class));
    }

    @Test
    void shouldSurviveMapperProviderFailureAtConstruction() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ModelAbExposureMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenThrow(new RuntimeException("bean init failed"));

        ModelRouter router = new ModelRouter(buildProperties(true), provider);

        // mapper 初始化失败 → 路由照常工作，只是不落曝光
        ModelRouter.ModelRouteDecision decision = router.resolve("quality", "chat");
        assertEquals("qwen-max", decision.model());
    }

    // ── 构造辅助 ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ObjectProvider<ModelAbExposureMapper> emptyMapperProvider() {
        ObjectProvider<ModelAbExposureMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ModelAbExposureMapper> mapperProvider(ModelAbExposureMapper mapper) {
        ObjectProvider<ModelAbExposureMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);
        return provider;
    }

    private ModelRouterProperties buildProperties(boolean enabled) {
        ModelRouterProperties properties = new ModelRouterProperties();
        properties.setEnabled(enabled);
        properties.setDefaultProfile("balanced");
        properties.setEndpointProfiles(Map.of(
                "chat", "balanced",
                "service", "quality",
                "rag", "balanced"
        ));
        properties.setProfiles(defaultProfiles());
        return properties;
    }

    private Map<String, ModelRouterProperties.RouteProfile> defaultProfiles() {
        ModelRouterProperties.RouteProfile economy = new ModelRouterProperties.RouteProfile();
        economy.setModel("qwen-turbo");
        economy.setCostTier("low");

        ModelRouterProperties.RouteProfile balanced = new ModelRouterProperties.RouteProfile();
        balanced.setModel("qwen-plus");
        balanced.setCostTier("medium");
        balanced.setFallbackProfile("economy");

        ModelRouterProperties.RouteProfile quality = new ModelRouterProperties.RouteProfile();
        quality.setModel("qwen-max");
        quality.setCostTier("high");
        quality.setFallbackProfile("balanced");

        return Map.of(
                "economy", economy,
                "balanced", balanced,
                "quality", quality
        );
    }

    /** 链路（quality→balanced→economy）全禁用，但插一个链外启用项 premium 在最前。 */
    private LinkedHashMap<String, ModelRouterProperties.RouteProfile> orderedProfilesWithChainDisabledAndExtraEnabled() {
        ModelRouterProperties.RouteProfile premium = new ModelRouterProperties.RouteProfile();
        premium.setModel("qwen-long");
        premium.setCostTier("high");
        ModelRouterProperties.RouteProfile economy = new ModelRouterProperties.RouteProfile();
        economy.setModel("qwen-turbo");
        economy.setCostTier("low");
        economy.setEnabled(false);
        ModelRouterProperties.RouteProfile balanced = new ModelRouterProperties.RouteProfile();
        balanced.setModel("qwen-plus");
        balanced.setCostTier("medium");
        balanced.setFallbackProfile("economy");
        balanced.setEnabled(false);
        ModelRouterProperties.RouteProfile quality = new ModelRouterProperties.RouteProfile();
        quality.setModel("qwen-max");
        quality.setCostTier("high");
        quality.setFallbackProfile("balanced");
        quality.setEnabled(false);
        LinkedHashMap<String, ModelRouterProperties.RouteProfile> profiles = new LinkedHashMap<>();
        profiles.put("premium", premium);
        profiles.put("economy", economy);
        profiles.put("balanced", balanced);
        profiles.put("quality", quality);
        return profiles;
    }
}
