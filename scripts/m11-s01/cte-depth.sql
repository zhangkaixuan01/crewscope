-- M11-S01 WorkGraph 递归 CTE 规模基准（S01b，cte-depth.sql）
--
-- 目的：在真实 PostgreSQL 上验证 ADR-033 冻结的图阈值：
--   单点邻接查询 P95 ≤ 100ms；5000 节点规模下阻塞链 CTE（深度上界 32、
--   行上界 5000）P95 ≤ 500ms。
--
-- 数据形态（共 5100 节点、5099 边 + 可选网格 3600 节点/14160 边）：
--   - 长链 2000：item_1 被 item_2 阻塞、…、item_1999 被 item_2000 阻塞。
--     从 item_1 出发的上游链深 1999 —— 超过深度上界 32，验证截断行为；
--   - 扇出 3100：300 个 hub，各挂 10 个 leaf（leaf 被 hub 阻塞）；
--   - 菱形网格 3600（60 行 × 60 列，每节点 4 条上游边）：多父汇聚的
--     最坏形态 —— 深度×宽度组合展开。
--
-- **红线（本脚本实测得出的教训，ADR-033 已同步）**：BLOCKS 图上的递归
-- CTE 必须用 UNION（去重），绝不能用 UNION ALL —— 多父 DAG 的路径数
-- 指数膨胀（网格形态 4^32 中间行），外层 LIMIT 不阻止递归项物化，
-- 实测 UNION ALL + 网格曾把本机 PG backend 直接打崩。UNION 把节点数
-- 钳制在 |V| 上界。SPLIT_FROM 是单 parent 森林、路径唯一，两种写法
-- 等价，仍统一用 UNION。
--
-- 运行（容器内 psql；造数 + 抽查一次；批量计时见下方说明）：
--   docker exec -i crewscope-java-postgres-1 \
--     psql -U crewscope -d crewscope < scripts/m11-s01/cte-depth.sql
-- 计时口径：EXPLAIN (ANALYZE, FORMAT TEXT) 的 "Execution Time"，≥30 次取样，
--   shell 侧 grep 收集（见 spike §4 实测记录），P50/P95/P99 由样本排序得出。
-- 隔离：schema m11s01（与 cycle-race.sql 共用），跑完 DROP SCHEMA CASCADE。

-- ============================================================ 1. 建模与造数
-- 表结构由 cycle-race.sql 建立；本脚本独立运行时先确保存在（幂等）。
CREATE SCHEMA IF NOT EXISTS m11s01;
CREATE TABLE IF NOT EXISTS m11s01.work_project (id uuid PRIMARY KEY, name text NOT NULL);
CREATE TABLE IF NOT EXISTS m11s01.work_item (id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES m11s01.work_project(id));
CREATE TABLE IF NOT EXISTS m11s01.work_item_dependency (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES m11s01.work_project(id),
    src_id uuid NOT NULL REFERENCES m11s01.work_item(id),
    dst_id uuid NOT NULL REFERENCES m11s01.work_item(id),
    kind text NOT NULL DEFAULT 'BLOCKS' CHECK (kind IN ('BLOCKS','SPLIT_FROM'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_dep_forward ON m11s01.work_item_dependency (src_id, dst_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_dep_reverse ON m11s01.work_item_dependency (dst_id, src_id);
CREATE INDEX IF NOT EXISTS ix_dep_src ON m11s01.work_item_dependency (src_id);
CREATE INDEX IF NOT EXISTS ix_dep_dst ON m11s01.work_item_dependency (dst_id);

INSERT INTO m11s01.work_project (id, name)
VALUES ('22222222-2222-2222-2222-222222222222','P2')
ON CONFLICT DO NOTHING;

-- uuid 构造：'333...' 前缀 + 12 位零填充序号，可读且稳定
-- 长链 2000（item i 的 id = 33333333-...-i）
INSERT INTO m11s01.work_item (id, project_id)
SELECT ('33333333-0000-0000-0000-'::text || lpad(i::text, 12, '0'))::uuid,
       '22222222-2222-2222-2222-222222222222'
FROM generate_series(1, 2000) AS i
ON CONFLICT DO NOTHING;

INSERT INTO m11s01.work_item_dependency (project_id, src_id, dst_id)
SELECT '22222222-2222-2222-2222-222222222222',
       ('33333333-0000-0000-0000-'::text || lpad(i::text,     12, '0'))::uuid,
       ('33333333-0000-0000-0000-'::text || lpad((i+1)::text, 12, '0'))::uuid
FROM generate_series(1, 1999) AS i
ON CONFLICT DO NOTHING;

-- 扇出 3100：hub 300（4444...-h<i>）各挂 10 leaf（5555...-<h>_<j>）
INSERT INTO m11s01.work_item (id, project_id)
SELECT ('44444444-0000-0000-0000-'::text || lpad(h::text, 12, '0'))::uuid,
       '22222222-2222-2222-2222-222222222222'
FROM generate_series(1, 300) AS h
ON CONFLICT DO NOTHING;

INSERT INTO m11s01.work_item (id, project_id)
SELECT ('55555555-0000-0000-0000-'::text || lpad((h*100+j)::text, 12, '0'))::uuid,
       '22222222-2222-2222-2222-222222222222'
FROM generate_series(1, 300) AS h, generate_series(1, 10) AS j
ON CONFLICT DO NOTHING;

INSERT INTO m11s01.work_item_dependency (project_id, src_id, dst_id)
SELECT '22222222-2222-2222-2222-222222222222',
       ('55555555-0000-0000-0000-'::text || lpad((h*100+j)::text, 12, '0'))::uuid,
       ('44444444-0000-0000-0000-'::text || lpad(h::text,        12, '0'))::uuid
FROM generate_series(1, 300) AS h, generate_series(1, 10) AS j
ON CONFLICT DO NOTHING;

-- 规模断言
SELECT (SELECT count(*) FROM m11s01.work_item)  AS items,
       (SELECT count(*) FROM m11s01.work_item_dependency) AS edges;  -- 5100 / 5099(+前场景)

-- ============================================================ 2. 被测查询
-- 2a. 单点邻接（入向：X 被谁阻塞）
--   SELECT dst_id FROM m11s01.work_item_dependency WHERE src_id = :node;
-- 2b. 阻塞链 CTE（深度 32 截断 + 行 5000 上限，与 ADR-033 冻结值一致；
--     UNION 去重是正确性要求，见文件头红线）：
WITH RECURSIVE upstream(node, depth) AS (
    SELECT dst_id, 1
      FROM m11s01.work_item_dependency
     WHERE src_id = '33333333-0000-0000-0000-000000000001'::uuid  -- 链底：上游深 1999
    UNION
    SELECT d.dst_id, u.depth + 1
      FROM m11s01.work_item_dependency d
      JOIN upstream u ON d.src_id = u.node
     WHERE u.depth < 32
)
SELECT count(*) AS chain_rows_capped FROM (SELECT node FROM upstream LIMIT 5000) t;
-- 预期 32：长链被深度上界截断（截断行为显式呈现，不静默丢行——
-- 产品层超限时要向用户呈现「链路超出展示深度」而不是假装到头）。

-- 2c. 菱形网格形态（最坏情况；60×60、每节点 4 条上游边，见 §1 红线
--     的事故记录）：同一 CTE 从网格底部节点出发，预期 32 层 × 4 新
--     节点/层 = 128 行。生成：
-- INSERT INTO m11s01.work_item (id, project_id)
-- SELECT ('66666666-0000-0000-0000-'::text || lpad((r*100+c)::text, 12, '0'))::uuid,
--        '22222222-2222-2222-2222-222222222222'
-- FROM generate_series(1, 60) AS r, generate_series(1, 60) AS c
-- ON CONFLICT DO NOTHING;
-- INSERT INTO m11s01.work_item_dependency (project_id, src_id, dst_id)
-- SELECT '22222222-2222-2222-2222-222222222222',
--        ('66666666-0000-0000-0000-'::text || lpad((r*100+c)::text,      12, '0'))::uuid,
--        ('66666666-0000-0000-0000-'::text || lpad(((r+1)*100+c2)::text, 12, '0'))::uuid
-- FROM generate_series(1, 59) AS r, generate_series(1, 60) AS c, generate_series(1, 4) AS c2p,
--      LATERAL (SELECT ((c + c2p*15 - 1) % 60 + 1) AS c2) x
-- ON CONFLICT DO NOTHING;

-- ============================================================ 3. 批量计时（宿主侧 shell）
-- for i in $(seq 1 30); do
--   docker exec crewscope-java-postgres-1 psql -U crewscope -d crewscope -c \
--     "EXPLAIN (ANALYZE, FORMAT TEXT) <2a 或 2b 的完整查询>" \
--     | grep 'Execution Time'
-- done | sort -n | awk '{ ... P50/P95/P99 ... }'
-- 实测（2026-10-09，docker desktop PG、M 系 mac、数据暖缓存）：
--   邻接（链/hub/leaf 混合，n=33）：P50=0.047 P95=0.069 P99=0.083 max=0.098 ms
--   阻塞链 CTE（链底/链中/hub 混合，n=33）：P50=0.134 P95=0.161 P99=0.162 max=0.257 ms
--   网格最坏形态（n=12）：P50=0.626 P95=1.292 max=11.665 ms（max 为首跑冷缓存）
-- 全部远低于冻结阈值（邻接 ≤100ms、CTE ≤500ms）。

-- ============================================================ 4. 清理
-- DROP SCHEMA m11s01 CASCADE;
