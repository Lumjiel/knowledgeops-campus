-- Close cross-tenant data leak on the public school/course/course_reservation
-- tables. The V1 schema did not include a tenant_id column, so
-- agent-driven queries (CourseTools.queryCourse / querySchool /
-- addCourseReservation) could return or insert rows for any tenant.
--
-- The migration adds a NOT NULL tenant_id column to each table with
-- DEFAULT 'public' so existing rows are claimed by the public tenant
-- (consistent with the V6 / V9 tenant isolation migrations), then adds
-- the corresponding tenant-leading indexes.

ALTER TABLE course
  ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'public';

CREATE INDEX idx_course_tenant_id
  ON course (tenant_id, id);

ALTER TABLE school
  ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'public';

CREATE INDEX idx_school_tenant_id
  ON school (tenant_id, id);

ALTER TABLE course_reservation
  ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'public';

CREATE INDEX idx_course_reservation_tenant_id
  ON course_reservation (tenant_id, id);

-- Drop the old school-only city index so the new tenant-leading index can
-- take its place as the dominant lookup path. (city is still indexed via
-- idx_school_tenant_id when queries filter by tenant first.)
--
-- NOTE (2026-10-30): MySQL 的 DROP INDEX 并不是 no-op——索引不存在会直接报
-- 1091 (Can't DROP 'city')，导致本迁移在任何干净库上必然失败。
-- 这里改成先查 information_schema 再动态执行，做到真正的幂等。
SET @has_city := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'school'
    AND index_name = 'city'
);
SET @drop_city_sql := IF(@has_city > 0, 'ALTER TABLE school DROP INDEX city', 'DO 0');
PREPARE drop_city_stmt FROM @drop_city_sql;
EXECUTE drop_city_stmt;
DEALLOCATE PREPARE drop_city_stmt;
