<script setup lang="ts">
import { useSlots } from 'vue'

/**
 * M13b-F09（S2）：扫读工具栏的统一骨架——单行、常态高度封顶 --cs-scan-toolbar-height。
 *
 * 左起三段：结果数（#count，可省）、内联筛选（#filters，≤3 个 label+控件，label 横排在控件
 * 左侧）、右侧操作（#side：排序菜单/视图切换/主行动，自动贴右）。骨架只承诺布局、高度与筛选
 * 控件的基础形态；控件本身经槽透传，超出 3 个的筛选由调用方收进高级筛选 popover（BaseMenu）。
 * 窄屏不再整卡折叠，槽内内容自然换行（M9b-R16 的折叠+badge 由 wrap 替代）。
 */
const slots = useSlots()
</script>

<template>
  <section class="scan-toolbar panel">
    <p v-if="slots.count" class="scan-toolbar__count"><slot name="count" /></p>
    <div class="scan-toolbar__filters"><slot name="filters" /></div>
    <div class="scan-toolbar__side"><slot name="side" /></div>
  </section>
</template>

<style scoped>
.scan-toolbar { display: flex; min-height: var(--cs-scan-toolbar-height); align-items: center; gap: var(--cs-space-16); padding: 0 var(--cs-space-20); }
.scan-toolbar__count { flex: 0 0 auto; margin: 0; color: var(--cs-text-muted); font: var(--cs-text-xs) var(--cs-font-mono); white-space: nowrap; }
.scan-toolbar__filters { display: flex; flex: 1 1 auto; min-width: 0; align-items: center; gap: var(--cs-space-16); }
/* label 横排是 ≤56px 的前提：竖排 label+select 是两层高度，一行装不下。
 * label 经 #filters 槽传入，作用域属于调用方——必须 :deep 才能命中（契约 §7 的 56px 靠它）。 */
.scan-toolbar__filters :deep(label) { display: inline-flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); white-space: nowrap; }
.scan-toolbar__filters :deep(select) { min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 8px; background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }
.scan-toolbar__side { display: flex; flex: 0 0 auto; align-items: center; gap: var(--cs-space-8); margin-left: auto; }
@media (max-width: 767px) {
  .scan-toolbar { flex-wrap: wrap; min-height: 0; padding: var(--cs-space-8) var(--cs-space-16); }
  .scan-toolbar__filters { flex-basis: 100%; flex-wrap: wrap; }
  /* side 作为 flex:0 0 auto 的 item 不会被父级 wrap 压缩（实测 390 视口整段 616px 溢出）——
   * 独占一行后宽度被容器约束，内部按钮才有换行的前提。 */
  .scan-toolbar__side { flex: 1 1 100%; margin-left: 0; flex-wrap: wrap; }
}
</style>
