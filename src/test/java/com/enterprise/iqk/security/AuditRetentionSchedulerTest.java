package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.AuditProperties;
import com.enterprise.iqk.mapper.AuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditRetentionScheduler 的纯单元测试（architecture-enterprise.md §4：
 * 「Audit log retention scheduler」）。验证保留期换算成截止时间、
 * retentionDays 下限钳制，以及调度注解的 cron 固化（阈值随代码走）。
 */
class AuditRetentionSchedulerTest {

    private AuditLogMapper auditLogMapper;
    private AuditProperties auditProperties;
    private AuditRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        auditLogMapper = mock(AuditLogMapper.class);
        auditProperties = new AuditProperties();
        scheduler = new AuditRetentionScheduler(auditLogMapper, auditProperties);
    }

    @Test
    void cleanupDeletesRowsOlderThanConfiguredRetentionDays() {
        auditProperties.setRetentionDays(90);
        when(auditLogMapper.deleteBefore(org.mockito.ArgumentMatchers.any())).thenReturn(12);

        scheduler.cleanup();

        ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auditLogMapper, times(1)).deleteBefore(deadline.capture());
        assertThat(deadline.getValue()).isCloseTo(
                LocalDateTime.now().minusDays(90), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void cleanupClampsRetentionDaysToOneDayMinimum() {
        // retentionDays 配成 0/负数时不得把库清到"刚刚"，钳制为至少保留 1 天
        auditProperties.setRetentionDays(0);
        when(auditLogMapper.deleteBefore(org.mockito.ArgumentMatchers.any())).thenReturn(0);

        scheduler.cleanup();

        ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auditLogMapper).deleteBefore(deadline.capture());
        assertThat(deadline.getValue()).isCloseTo(
                LocalDateTime.now().minusDays(1), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void cleanupIsWiredToNightlyCronAndSurvivesZeroDeletions() throws Exception {
        // cron 固化在凌晨 3:20，避免有人无意把清理挪到业务高峰
        Method cleanup = AuditRetentionScheduler.class.getMethod("cleanup");
        Scheduled scheduled = cleanup.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 20 3 * * *");

        when(auditLogMapper.deleteBefore(org.mockito.ArgumentMatchers.any())).thenReturn(0);
        scheduler.cleanup();
        verify(auditLogMapper).deleteBefore(org.mockito.ArgumentMatchers.any());
    }
}
