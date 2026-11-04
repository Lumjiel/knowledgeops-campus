package com.enterprise.iqk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * File: IngestionJobMapper.java
 * <p>
 * {@code ingestion_job} 表的 MyBatis-Plus Mapper。除常规查询外提供可靠性关键 SQL：
 * 幂等键查询、worker 乐观认领（{@link #claimForRun}）、终态写回、到期重试扫描与
 * CAS 式重投（{@link #requeueRetry}）。
 */
@Mapper
public interface IngestionJobMapper extends BaseMapper<IngestionJob> {

    /** 幂等去重查询：租户内按幂等键取任务（LIMIT 1）。 */
    @Select("""
            SELECT * FROM ingestion_job
            WHERE tenant_id = #{tenantId}
              AND idempotency_key = #{idempotencyKey}
            LIMIT 1
            """)
    IngestionJob findByIdempotencyKey(@Param("tenantId") String tenantId,
                                      @Param("idempotencyKey") String idempotencyKey);

    /** 按 jobId 查询（不带租户条件，仅限后台 worker / listener 内部使用）。 */
    @Select("SELECT * FROM ingestion_job WHERE job_id = #{jobId} LIMIT 1")
    IngestionJob findByJobId(@Param("jobId") String jobId);

    /** 按租户 + jobId 查询，用于对外查询的租户隔离。 */
    @Select("""
            SELECT * FROM ingestion_job
            WHERE tenant_id = #{tenantId}
              AND job_id = #{jobId}
            LIMIT 1
            """)
    IngestionJob findByJobIdAndTenant(@Param("tenantId") String tenantId, @Param("jobId") String jobId);

    /** 取下一个就绪任务（PENDING/RETRY 且重试时间已到），供 db_polling 轮询。 */
    @Select("""
            SELECT * FROM ingestion_job
            WHERE status IN ('PENDING','RETRY')
              AND (next_retry_at IS NULL OR next_retry_at <= #{now})
            ORDER BY created_at ASC
            LIMIT 1
            """)
    IngestionJob findNextReadyJob(@Param("now") LocalDateTime now);

    /**
     * worker 乐观认领：仅当任务仍处于 PENDING/RETRY 时置 RUNNING、自增 attempt_count、
     * 清空 error_message。多 worker 并发下只有一行受影响，防止重复消费；
     * WHERE 带租户条件，跨租户认领直接失败（影响行数 0）。
     *
     * @return 影响行数，0 表示已被抢占 / 状态不符 / 租户不匹配
     */
    @Update("""
            UPDATE ingestion_job
            SET status = #{toStatus},
                started_at = #{startedAt},
                updated_at = #{updatedAt},
                attempt_count = attempt_count + 1,
                error_message = NULL
            WHERE job_id = #{jobId}
              AND tenant_id = #{tenantId}
              AND status IN ('PENDING','RETRY')
            """)
    int claimForRun(@Param("jobId") String jobId,
                    @Param("tenantId") String tenantId,
                    @Param("toStatus") IngestionJobStatus toStatus,
                    @Param("startedAt") LocalDateTime startedAt,
                    @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 终态/阶段写回：SUCCEEDED / RETRY / FAILED 共用；RETRY 时 errorMessage 与
     * nextRetryAt（到期时间）一并写入，SUCCEEDED 时两者为空。
     *
     * @return 影响行数
     */
    @Update("""
            UPDATE ingestion_job
            SET status = #{status},
                finished_at = #{finishedAt},
                updated_at = #{updatedAt},
                error_message = #{errorMessage},
                next_retry_at = #{nextRetryAt}
            WHERE job_id = #{jobId}
            """)
    int updateTerminalState(@Param("jobId") String jobId,
                            @Param("status") IngestionJobStatus status,
                            @Param("finishedAt") LocalDateTime finishedAt,
                            @Param("updatedAt") LocalDateTime updatedAt,
                            @Param("errorMessage") String errorMessage,
                            @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /** 查询会话下最近任务列表（按创建时间倒序），供任务列表查询与溯源展开。 */
    @Select("""
            SELECT * FROM ingestion_job
            WHERE tenant_id = #{tenantId}
              AND chat_id = #{chatId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<IngestionJob> findLatestByChatId(@Param("tenantId") String tenantId,
                                          @Param("chatId") String chatId,
                                          @Param("limit") int limit);

    /** 扫描重试时间已到的 RETRY 任务（按到期时间升序），供重投调度使用。 */
    @Select("""
            SELECT * FROM ingestion_job
            WHERE status = 'RETRY'
              AND next_retry_at IS NOT NULL
              AND next_retry_at <= #{now}
            ORDER BY next_retry_at ASC
            LIMIT #{limit}
            """)
    List<IngestionJob> findReadyRetries(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * CAS 式重投：仅当任务仍处于 RETRY 时改回 PENDING 并清空重试时间。
     * 调用方仅在该 SQL 影响行数 &gt; 0 时发布队列消息，防止并发重复投递。
     *
     * @return 影响行数，0 表示已被其他调度者处理或状态不符
     */
    @Update("""
            UPDATE ingestion_job
            SET status = 'PENDING',
                updated_at = #{updatedAt},
                next_retry_at = NULL
            WHERE job_id = #{jobId}
              AND status = 'RETRY'
            """)
    int requeueRetry(@Param("jobId") String jobId, @Param("updatedAt") LocalDateTime updatedAt);
}
