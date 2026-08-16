<script setup lang="ts">
import fixtureJson from './fixtures/diff-stream-v1.json'
import { parseDiffStreamFixture, replayDiffFixture, type DiffChangeKind } from './diffProjection'

const fixture = parseDiffStreamFixture(fixtureJson)
const replay = replayDiffFixture(fixture)
const files = replay.projection.files
const additions = files.reduce((sum, file) => sum + file.additions, 0)
const deletions = files.reduce((sum, file) => sum + file.deletions, 0)
const recoveredGaps = replay.outcomes.filter(outcome => outcome === 'GAP').length
const ignoredDuplicates = replay.outcomes.filter(outcome => outcome === 'DUPLICATE').length

const labels: Record<DiffChangeKind, string> = {
  ADDED: '新增',
  MODIFIED: '修改',
  DELETED: '删除',
  RENAMED: '重命名',
  COPIED: '复制',
  TYPE_CHANGED: '类型变化',
}
</script>

<template>
  <main class="diff-fixture">
    <header class="fixture-heading">
      <div>
        <p class="eyebrow">M4 · Execution Workspace</p>
        <h1>实时变更</h1>
        <p class="description">文件事件经过 Git 对账后形成的可恢复 Diff 投影</p>
      </div>
      <div class="sync-state" aria-label="Diff 已同步">
        <span class="sync-dot" aria-hidden="true" />
        已同步 · G{{ replay.projection.generation }}
      </div>
    </header>

    <section class="summary-grid" aria-label="Diff 汇总">
      <div class="summary-card">
        <span>变更文件</span>
        <strong>{{ files.length }}</strong>
      </div>
      <div class="summary-card positive">
        <span>新增行</span>
        <strong>+{{ additions }}</strong>
      </div>
      <div class="summary-card negative">
        <span>删除行</span>
        <strong>-{{ deletions }}</strong>
      </div>
      <div class="summary-card recovered">
        <span>流恢复</span>
        <strong>{{ recoveredGaps }} 次</strong>
      </div>
    </section>

    <section class="stream-note" aria-label="事件回放状态">
      <span>乱序缺口已通过 Reset 收敛</span>
      <span>{{ ignoredDuplicates }} 个重复事件已忽略</span>
      <code>{{ replay.projection.manifestHash?.slice(0, 12) }}</code>
    </section>

    <section class="file-list" aria-label="变更文件列表">
      <article v-for="file in files" :key="file.path" class="file-card">
        <header class="file-heading">
          <div class="file-identity">
            <span class="kind" :data-kind="file.kind">{{ labels[file.kind] }}</span>
            <div>
              <h2>{{ file.path }}</h2>
              <p v-if="file.oldPath">来自 {{ file.oldPath }}</p>
            </div>
          </div>
          <div class="line-stats" aria-label="行数变化">
            <span>+{{ file.additions }}</span>
            <span>-{{ file.deletions }}</span>
          </div>
        </header>
        <pre v-if="file.patchPreview" class="patch-preview"><code>{{ file.patchPreview }}</code></pre>
        <footer class="file-footer">
          <span v-if="file.patchTruncated" class="truncated">预览已截断 · 完整 Patch 已归档</span>
          <span v-else>Patch 预览完整</span>
          <code>{{ file.patchSha256.slice(0, 10) }}</code>
        </footer>
      </article>
    </section>
  </main>
</template>

<style scoped>
.diff-fixture {
  width: min(1120px, calc(100% - 48px));
  margin: 0 auto;
  padding: 48px 0 72px;
}

.fixture-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 32px;
  margin-bottom: 28px;
}

.eyebrow {
  margin-bottom: 8px;
  color: var(--cs-brand-700);
  font-size: 12px;
  font-weight: 750;
  letter-spacing: .09em;
  text-transform: uppercase;
}

h1 {
  margin-bottom: 8px;
  font-family: var(--cs-font-display);
  font-size: clamp(30px, 4vw, 44px);
  font-weight: 600;
}

.description {
  margin: 0;
  color: var(--cs-text-muted);
  font-size: 15px;
}

.sync-state {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 9px 13px;
  border: 1px solid var(--cs-brand-200);
  border-radius: var(--cs-radius-pill);
  background: var(--cs-brand-50);
  color: var(--cs-brand-800);
  font-weight: 650;
  white-space: nowrap;
}

.sync-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--cs-brand-400);
  box-shadow: 0 0 0 4px rgb(102 182 132 / 16%);
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.summary-card {
  display: flex;
  min-height: 94px;
  flex-direction: column;
  justify-content: space-between;
  padding: 16px;
  border: 1px solid var(--cs-border);
  border-radius: var(--cs-radius-md);
  background: var(--cs-surface);
}

.summary-card span {
  color: var(--cs-text-muted);
  font-size: 12px;
  font-weight: 650;
}

.summary-card strong {
  font-size: 24px;
  font-variant-numeric: tabular-nums;
}

.summary-card.positive strong { color: #356d4b; }
.summary-card.negative strong { color: var(--cs-danger); }
.summary-card.recovered strong { color: var(--cs-info); }

.stream-note {
  display: flex;
  align-items: center;
  gap: 18px;
  margin: 14px 0 22px;
  padding: 12px 16px;
  border: 1px solid #d9e7f5;
  border-radius: var(--cs-radius-md);
  background: var(--cs-info-soft);
  color: #315a84;
  font-size: 12px;
}

.stream-note code {
  margin-left: auto;
  font-family: var(--cs-font-mono);
}

.file-list {
  display: grid;
  gap: 14px;
}

.file-card {
  overflow: hidden;
  border: 1px solid var(--cs-border);
  border-radius: var(--cs-radius-lg);
  background: var(--cs-surface);
  box-shadow: 0 8px 24px rgb(21 35 29 / 4%);
}

.file-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 16px 18px;
}

.file-identity {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  gap: 12px;
}

.file-identity h2 {
  overflow: hidden;
  margin: 1px 0 2px;
  font-family: var(--cs-font-mono);
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-identity p {
  margin: 0;
  color: var(--cs-text-muted);
  font-size: 11px;
}

.kind {
  flex: 0 0 auto;
  min-width: 48px;
  padding: 3px 7px;
  border-radius: 6px;
  background: var(--cs-surface-subtle);
  color: var(--cs-text-secondary);
  font-size: 10px;
  font-weight: 750;
  text-align: center;
}

.kind[data-kind="ADDED"] { background: var(--cs-success-soft); color: #356d4b; }
.kind[data-kind="DELETED"] { background: var(--cs-danger-soft); color: var(--cs-danger); }
.kind[data-kind="RENAMED"] { background: var(--cs-info-soft); color: #315f8f; }

.line-stats {
  display: flex;
  gap: 10px;
  font-family: var(--cs-font-mono);
  font-size: 12px;
}

.line-stats span:first-child { color: #356d4b; }
.line-stats span:last-child { color: var(--cs-danger); }

.patch-preview {
  max-height: 172px;
  overflow: hidden;
  margin: 0;
  padding: 14px 18px;
  border-block: 1px solid #e4e9e5;
  background: #f7f9f7;
  color: #344239;
  font-family: var(--cs-font-mono);
  font-size: 11px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.file-footer {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 18px;
  color: var(--cs-text-muted);
  font-size: 10px;
}

.file-footer code { font-family: var(--cs-font-mono); }
.truncated { color: var(--cs-warning); font-weight: 650; }

@media (max-width: 640px) {
  .diff-fixture {
    width: min(100% - 28px, 480px);
    padding: 28px 0 44px;
  }

  .fixture-heading {
    display: grid;
    gap: 14px;
    margin-bottom: 20px;
  }

  .sync-state { justify-self: start; }

  .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .summary-card { min-height: 82px; padding: 13px; }

  .stream-note {
    display: grid;
    gap: 4px;
    padding: 11px 13px;
  }

  .stream-note code { margin-left: 0; }

  .file-heading { display: grid; gap: 10px; padding: 14px; }
  .line-stats { padding-left: 60px; }
  .patch-preview { padding: 12px 14px; }
  .file-footer { padding: 9px 14px; }
}
</style>
