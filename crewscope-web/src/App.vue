<script setup lang="ts">
const workItems = [
  { key: 'CRW-1024', title: '初始化 CrewScope 工程', status: '执行中', owner: '张三' },
  { key: 'CRW-1025', title: '接入 GitHub Provider', status: '待 Review', owner: '李四' },
  { key: 'CRW-1026', title: '建立团队协作收件箱', status: '待规划', owner: '王五' },
]
</script>

<template>
  <main class="shell">
    <aside class="sidebar">
      <div class="brand"><span>C</span> CrewScope</div>
      <nav>
        <a class="active" href="#">团队工作台</a>
        <a href="#">WorkGraph</a>
        <a href="#">协作收件箱</a>
        <a href="#">任务观测</a>
        <a href="#">Provider</a>
      </nav>
      <div class="team-card">
        <small>当前团队</small>
        <strong>Platform Engineering</strong>
        <span>6 位成员 · 4 个 Agent</span>
      </div>
    </aside>

    <section class="workspace">
      <header>
        <div>
          <p class="eyebrow">TEAM WORKSPACE</p>
          <h1>今天准备交付什么？</h1>
        </div>
        <button class="secondary">运行状态</button>
      </header>

      <section class="composer">
        <textarea placeholder="描述目标，CrewScope 会建议 WorkItem、Owner、Reviewer 和执行计划……" />
        <div class="composer-actions">
          <span>可引用 @成员、@Agent、@Provider 和文件</span>
          <button>开始协作</button>
        </div>
      </section>

      <section class="summary-grid">
        <article><span>进行中</span><strong>8</strong><small>3 个等待协作</small></article>
        <article><span>待 Review</span><strong>4</strong><small>平均等待 1.8h</small></article>
        <article><span>本周交付</span><strong>17</strong><small>包含 9 个 PR</small></article>
        <article><span>Agent 成功率</span><strong>96%</strong><small>过去 7 天</small></article>
      </section>

      <section class="work-list">
        <div class="section-title">
          <div><p class="eyebrow">SHARED WORK</p><h2>团队工作</h2></div>
          <button class="secondary">查看 WorkGraph</button>
        </div>
        <div v-for="item in workItems" :key="item.key" class="work-row">
          <code>{{ item.key }}</code>
          <strong>{{ item.title }}</strong>
          <span>{{ item.owner }}</span>
          <span class="status">{{ item.status }}</span>
        </div>
      </section>
    </section>

    <aside class="activity">
      <p class="eyebrow">LIVE ACTIVITY</p>
      <h2>团队动态</h2>
      <ol>
        <li><span class="dot green" /><div><strong>代码测试通过</strong><p>CRW-1024 · Coder Agent</p><time>2 分钟前</time></div></li>
        <li><span class="dot amber" /><div><strong>等待 Gate Review</strong><p>CRW-1025 · 李四</p><time>18 分钟前</time></div></li>
        <li><span class="dot blue" /><div><strong>收到协作贡献</strong><p>故障调查 · 王五</p><time>35 分钟前</time></div></li>
      </ol>
    </aside>
  </main>
</template>
