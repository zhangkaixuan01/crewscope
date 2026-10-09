-- M11-S01 WorkGraph 并发无环证明（S01b，cycle-race.sql）
--
-- 目的：在真实 PostgreSQL 上用两个并发会话证明 ADR-033 冻结的事务协议
-- （WorkProject 行 SELECT FOR UPDATE，按 id 排序 → 锁内递归 CTE 探测 →
-- INSERT）在三种交错下保持无环，并否证「只锁两端点行」的弱方案。
--
-- 运行方式（脚本为剧本，按段在两个 psql 会话中交错执行）：
--   会话一 / 会话二 各开一个：
--     docker exec -it crewscope-java-postgres-1 psql -U crewscope -d crewscope
--   然后按本文件各场景中 [S1]/[S2]/[S3] 标注的顺序贴入语句。
--   S2a 的否证段与 S1 的无锁对照段演示协议被绕过时的行为。
-- 隔离：所有对象建在独立 schema m11s01，绝不触碰 crewscope schema；
-- 跑完 DROP SCHEMA m11s01 CASCADE（见文件末尾）。
--
-- 边语义（与 ADR-033 一致）：work_item_dependency(src_id, dst_id) 表示
-- 「src 被 dst 阻塞」（BLOCKS 单向存储；DEPENDS_ON(X,Y) 入库前规范化为
-- BLOCKS(Y,X)，本表不出现第二种方向）。SPLIT_FROM 同表不同 kind，不参与
-- 调度环 CTE，受森林约束（UNIQUE(child) 部分索引 + 祖先链检查）。

-- ============================================================ 0. 建模
CREATE SCHEMA IF NOT EXISTS m11s01;

CREATE TABLE m11s01.work_project (
    id uuid PRIMARY KEY,
    name text NOT NULL
);

CREATE TABLE m11s01.work_item (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES m11s01.work_project(id)
);

CREATE TABLE m11s01.work_item_dependency (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES m11s01.work_project(id),
    src_id uuid NOT NULL REFERENCES m11s01.work_item(id),   -- 被阻塞项
    dst_id uuid NOT NULL REFERENCES m11s01.work_item(id),   -- 阻塞源
    kind text NOT NULL DEFAULT 'BLOCKS' CHECK (kind IN ('BLOCKS', 'SPLIT_FROM'))
);

-- 防线一（DB 兜底）：同向边唯一 + 反向边唯一 —— 相反边在索引层不可能共存。
CREATE UNIQUE INDEX uq_dep_forward ON m11s01.work_item_dependency (src_id, dst_id);
CREATE UNIQUE INDEX uq_dep_reverse ON m11s01.work_item_dependency (dst_id, src_id);
-- SPLIT_FROM 森林：每个 child 至多一条 provenance 边。
CREATE UNIQUE INDEX uq_split_child
    ON m11s01.work_item_dependency (src_id) WHERE kind = 'SPLIT_FROM';

CREATE INDEX ix_dep_src ON m11s01.work_item_dependency (src_id);
CREATE INDEX ix_dep_dst ON m11s01.work_item_dependency (dst_id);

-- 固化协议模板（产品 D01 的实现即此，BEGIN/COMMIT 由调用方控制）：
--
--   BEGIN;
--   -- 1. 锁 WorkProject 行（跨项目按 id 排序，杜绝死锁）
--   SELECT id FROM m11s01.work_project
--    WHERE id IN (:project_a, :project_b) ORDER BY id FOR UPDATE;
--   -- 2. 锁内探测：新边 (src, dst) 插入后是否成环
--   --    ⟺ dst 沿既有边传递可达 src
--   WITH RECURSIVE upstream(node) AS (
--       SELECT dst_id FROM m11s01.work_item_dependency
--        WHERE src_id = :dst AND kind = 'BLOCKS'
--       UNION
--       SELECT d.dst_id FROM m11s01.work_item_dependency d
--        JOIN upstream u ON d.src_id = u.node AND d.kind = 'BLOCKS'
--   )
--   SELECT EXISTS (SELECT 1 FROM upstream WHERE node = :src);
--   -- 3. 不存在路径才插入
--   INSERT INTO m11s01.work_item_dependency
--       (project_id, src_id, dst_id) VALUES (:project, :src, :dst);
--   COMMIT;   -- 探测见环则 ROLLBACK

-- ============================================================ S1 相反边
-- 场景：空图上，T1 插 A→B，T2 同时插 B→A（互为反向）。
-- 预期：T2 阻塞在 project 行锁上；T1 提交后 T2 的 CTE 看见 A→B，
-- 探测到路径，拒绝插入。

-- [S1-both] 共同准备（任一会话执行一次）：
CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- gen_random_uuid
INSERT INTO m11s01.work_project (id, name)
VALUES (gen_random_uuid(), 'P1') RETURNING id \gset p1_
INSERT INTO m11s01.work_item (id, project_id)
VALUES (gen_random_uuid(), :'p1_id') RETURNING id \gset a_
INSERT INTO m11s01.work_item (id, project_id)
VALUES (gen_random_uuid(), :'p1_id') RETURNING id \gset b_
SELECT :'p1_id' AS project, :'a_id' AS a, :'b_id' AS b;

-- [S1-session1] 会话一（把上方输出的三个 uuid 填到下述 :p/:a/:b）：
BEGIN;
SELECT id FROM m11s01.work_project WHERE id IN ('<P1>'::uuid) ORDER BY id FOR UPDATE;
WITH RECURSIVE upstream(node) AS (
    SELECT dst_id FROM m11s01.work_item_dependency WHERE src_id = '<A>'::uuid
    UNION
    SELECT d.dst_id FROM m11s01.work_item_dependency d
     JOIN upstream u ON d.src_id = u.node
) SELECT EXISTS (SELECT 1 FROM upstream WHERE node = '<B>'::uuid);  -- false
INSERT INTO m11s01.work_item_dependency (project_id, src_id, dst_id)
VALUES ('<P1>'::uuid, '<A>'::uuid, '<B>'::uuid);
-- 保持事务打开，切到会话二执行 [S2 ... 不，S1-session2]，然后回来：
COMMIT;

-- [S1-session2] 会话二（在会话一 INSERT 之后、COMMIT 之前执行）：
BEGIN;
SELECT id FROM m11s01.work_project WHERE id IN ('<P1>'::uuid) ORDER BY id FOR UPDATE;
-- ↑ 阻塞：project 行锁被会话一持有。会话一 COMMIT 后才返回。
-- 探测方向注意：插 (src=B, dst=A) 查「dst 侧（A）的上游可达 src（B）吗」
-- —— CTE 起点是 A，目标是 B。写反（起点 B）会得到假阴性。
WITH RECURSIVE upstream(node) AS (
    SELECT dst_id FROM m11s01.work_item_dependency WHERE src_id = '<A>'::uuid
    UNION
    SELECT d.dst_id FROM m11s01.work_item_dependency d
     JOIN upstream u ON d.src_id = u.node
) SELECT EXISTS (SELECT 1 FROM upstream WHERE node = '<B>'::uuid);
-- 预期 true（读到了会话一已提交的 A→B）→ 拒绝插入，回滚：
ROLLBACK;

-- [S1-对照] 无协议时的 DB 兜底（任一会话）：两条反向边先后插入，
-- 第二条命中反向唯一索引 uq_dep_reverse，等价防线仍在：
--   INSERT INTO m11s01.work_item_dependency (project_id, src_id, dst_id)
--   VALUES ('<P1>'::uuid, '<B>'::uuid, '<A>'::uuid);
--   -- ERROR: duplicate key ... uq_dep_reverse （行序 (B,A) vs 索引列序）

-- [S1-verify] 结果断言（任一会话）：
-- 依赖表中只有 A→B 一条；反向探测存在路径：
WITH RECURSIVE upstream(node) AS (
    SELECT dst_id FROM m11s01.work_item_dependency WHERE src_id = '<A>'::uuid
    UNION
    SELECT d.dst_id FROM m11s01.work_item_dependency d
     JOIN upstream u ON d.src_id = u.node
) SELECT 'S1' AS scenario, EXISTS (SELECT 1 FROM upstream WHERE node = '<B>'::uuid)
       AS a_reaches_b,  -- true（直连）
       (SELECT count(*) FROM m11s01.work_item_dependency) AS edges;  -- 1

-- ============================================================ S2 diamond race
-- 场景：既有 B→C、D→A。T1 插 A→B，T2 插 C→D。四边合并成环
-- A→B→C→D→A。两条新边端点完全不相交 —— 「只锁两端点行」的方案
-- 对此无能为力，这正是协议锁 WorkProject 行的原因。

-- [S2-seed] 共同准备（任一会话执行一次，沿用 S1 的 P1 与 A、B）：
INSERT INTO m11s01.work_item (id, project_id)
VALUES (gen_random_uuid(), :'p1_id') RETURNING id \gset c_
INSERT INTO m11s01.work_item (id, project_id)
VALUES (gen_random_uuid(), :'p1_id') RETURNING id \gset d_
INSERT INTO m11s01.work_item_dependency (project_id, src_id, dst_id)
VALUES ('<P1>'::uuid, '<B>'::uuid, '<C>'::uuid),
       ('<P1>'::uuid, '<D>'::uuid, '<A>'::uuid);

-- [S2a-session1] 否证段：端点锁方案（故意违反协议）。会话一：
BEGIN;
SELECT id FROM m11s01.work_item WHERE id IN ('<A>'::uuid, '<B>'::uuid) ORDER BY id FOR UPDATE;
INSERT INTO m11s01.work_item_dependency (project_id, src_id, dst_id)
VALUES ('<P1>'::uuid, '<A>'::uuid, '<B>'::uuid);
-- 保持打开，去会话二跑 [S2a-session2]，然后：
COMMIT;

-- [S2a-session2] 会话二（会话一未提交时执行；端点不相交 → 互不阻塞）：
BEGIN;
SELECT id FROM m11s01.work_item WHERE id IN ('<C>'::uuid, '<D>'::uuid) ORDER BY id FOR UPDATE;
INSERT INTO m11s01.work_item_dependency (project_id, src_id, dst_id)
VALUES ('<P1>'::uuid, '<C>'::uuid, '<D>'::uuid);
COMMIT;

-- [S2a-verify] 双方都成功提交了 —— 但现在图里有环（端点锁方案失败实证；
-- 且 (A,B) 与 (C,D) 不互为反向，双唯一索引对 diamond 组合不兜底）：
-- （walk 与探测 CTE 同为 UNION 去重 —— 见 cte-depth.sql 头部的 UNION ALL
--   指数爆炸红线；本段节点少不致爆炸，统一写法防复制扩散）
WITH RECURSIVE walk(node, depth) AS (
    SELECT '<A>'::uuid, 0
    UNION
    SELECT d.dst_id, w.depth + 1 FROM m11s01.work_item_dependency d
     JOIN walk w ON d.src_id = w.node
    WHERE w.depth < 8
) SELECT 'S2a' AS scenario, EXISTS (
    SELECT 1 FROM walk WHERE node = '<A>'::uuid AND depth > 0) AS cycle_present;  -- true

-- [S2a-cleanup] 清理失败演示，换协议重跑：
DELETE FROM m11s01.work_item_dependency
WHERE (src_id, dst_id) IN (('<A>'::uuid, '<B>'::uuid), ('<C>'::uuid, '<D>'::uuid));

-- [S2b-session1] 协议正确段。会话一（同 S1 手法：锁 project 行）：
BEGIN;
SELECT id FROM m11s01.work_project WHERE id IN ('<P1>'::uuid) ORDER BY id FOR UPDATE;
-- CTE 探测 A 是否可达（沿既有 B→C、D→A）：A 的上游 = D，D 的上游 = 空 → false
INSERT INTO m11s01.work_item_dependency (project_id, src_id, dst_id)
VALUES ('<P1>'::uuid, '<A>'::uuid, '<B>'::uuid);
-- 保持打开，去会话二；回来后 COMMIT。

-- [S2b-session2] 会话二：锁同一 project 行 → 阻塞至会话一提交 →
-- CTE 探测：C 的上游 = D？既有 D→A + 新提交的 A→B → C 的上游链
-- D→A→B …… 探测目标 C：从 src=C 出发 dst=D，再 A，再 B；
-- 新边 (C,D) 的探测是「D 传递可达 C 吗」：
-- D 的上游 = A（D→A），A 的上游 = B（新 A→B），B 的上游 = C（既有 B→C）
-- → true → 拒绝插入，ROLLBACK：
BEGIN;
SELECT id FROM m11s01.work_project WHERE id IN ('<P1>'::uuid) ORDER BY id FOR UPDATE;
WITH RECURSIVE upstream(node) AS (
    SELECT dst_id FROM m11s01.work_item_dependency WHERE src_id = '<D>'::uuid
    UNION
    SELECT d.dst_id FROM m11s01.work_item_dependency d
     JOIN upstream u ON d.src_id = u.node
) SELECT EXISTS (SELECT 1 FROM upstream WHERE node = '<C>'::uuid);  -- true
ROLLBACK;

-- ============================================================ S3 SPLIT_FROM 祖先环
-- 场景：provenance 森林约束。既有 X SPLIT_FROM Y、Y SPLIT_FROM Z；
-- 尝试插 Z SPLIT_FROM X 会让 X→Y→Z→X 成祖先环，必须拒绝。
-- SPLIT_FROM 不参与调度环 CTE（kind 过滤），由独立的祖先链检查守护。

-- [S3-seed] 任一会话：
INSERT INTO m11s01.work_item_dependency (project_id, src_id, dst_id, kind)
VALUES ('<P1>'::uuid, '<X>'::uuid, '<Y>'::uuid, 'SPLIT_FROM'),
       ('<P1>'::uuid, '<Y>'::uuid, '<Z>'::uuid, 'SPLIT_FROM');

-- [S3-attempt] 插 Z SPLIT_FROM X 前的祖先链检查（锁 project 行后）：
BEGIN;
SELECT id FROM m11s01.work_project WHERE id IN ('<P1>'::uuid) ORDER BY id FOR UPDATE;
WITH RECURSIVE ancestors(node) AS (
    SELECT dst_id FROM m11s01.work_item_dependency
     WHERE src_id = '<X>'::uuid AND kind = 'SPLIT_FROM'
    UNION
    SELECT d.dst_id FROM m11s01.work_item_dependency d
     JOIN ancestors a ON d.src_id = a.node AND d.kind = 'SPLIT_FROM'
) SELECT EXISTS (SELECT 1 FROM ancestors WHERE node = '<Z>'::uuid);
-- true：X 的祖先链是 Y→Z，Z 在其中 —— 插入会成祖先环 → 不插入：
ROLLBACK;

-- [S3-forest] 森林约束兜底（UNIQUE(child) 对链式环的第二防线有限：
-- 它只保证单 parent；祖先环必须靠上述检查，正如钻石环必须靠 project 锁）。

-- ============================================================ 清理
-- 跑完全部场景后（任一会话）：
-- DROP SCHEMA m11s01 CASCADE;
