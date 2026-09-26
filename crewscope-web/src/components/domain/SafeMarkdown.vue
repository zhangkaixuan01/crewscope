<script setup lang="ts">
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'
import { computed } from 'vue'
import { useClipboard } from '../../composables/useClipboard'

const props = defineProps<{ content: string }>()
const clipboard = useClipboard()

// Raw HTML stays disabled at the parser boundary. DOMPurify remains a second boundary for links
// and generated nodes so future Markdown extensions cannot silently widen the rendered surface.
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: true, typographer: false })
const rendered = computed(() => sanitize(markdown.render(props.content)))

function sanitize(source: string): string {
  const clean = DOMPurify.sanitize(source, {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 's', 'code', 'pre', 'blockquote', 'ul', 'ol', 'li', 'a', 'h1', 'h2', 'h3', 'h4', 'hr', 'table', 'thead', 'tbody', 'tr', 'th', 'td'],
    ALLOWED_ATTR: ['href', 'title', 'target', 'rel'],
    RETURN_TRUSTED_TYPE: false,
  })
  const template = document.createElement('template')
  template.innerHTML = clean
  template.content.querySelectorAll('a').forEach(anchor => {
    const href = anchor.getAttribute('href') ?? ''
    if (!allowedHref(href)) {
      anchor.removeAttribute('href')
      anchor.removeAttribute('target')
      anchor.removeAttribute('rel')
      return
    }
    if (/^https?:/i.test(href)) {
      anchor.setAttribute('target', '_blank')
      anchor.setAttribute('rel', 'noopener noreferrer')
    }
  })
  // R40: every fenced block gets a copy affordance. The button is built here, after both
  // sanitiser passes, so raw content can never inject attributes or handlers into it.
  template.content.querySelectorAll('pre').forEach(pre => {
    const button = document.createElement('button')
    button.type = 'button'
    button.className = 'safe-markdown__code-copy'
    button.textContent = '复制代码'
    pre.prepend(button)
  })
  return template.innerHTML
}

function allowedHref(href: string): boolean {
  return href.startsWith('#') || /^(https?:|mailto:)/i.test(href)
}

function copyCode(event: MouseEvent): void {
  const button = (event.target as HTMLElement).closest<HTMLButtonElement>('.safe-markdown__code-copy')
  if (!button) return
  const pre = button.closest('pre')
  const code = pre?.querySelector('code')?.textContent ?? pre?.textContent ?? ''
  void clipboard.copy(code).then(success => {
    if (!success) return
    button.textContent = '已复制'
    button.classList.add('safe-markdown__code-copy--done')
    window.setTimeout(() => {
      button.textContent = '复制代码'
      button.classList.remove('safe-markdown__code-copy--done')
    }, 1500)
  })
}
</script>

<template>
  <!-- The HTML is produced only by the locked-down Markdown and DOM sanitizer pipeline above. -->
  <div class="safe-markdown" v-html="rendered" @click="copyCode" />
</template>

<style scoped>
.safe-markdown { overflow-wrap: anywhere; font-size: inherit; line-height: var(--cs-leading-relaxed); }
.safe-markdown :deep(p), .safe-markdown :deep(ul), .safe-markdown :deep(ol), .safe-markdown :deep(blockquote), .safe-markdown :deep(pre), .safe-markdown :deep(table) { margin: 0 0 var(--cs-space-8); }
.safe-markdown :deep(:last-child) { margin-bottom: 0; }
.safe-markdown :deep(ul), .safe-markdown :deep(ol) { padding-left: var(--cs-space-20); }
.safe-markdown :deep(blockquote) { padding-left: var(--cs-space-12); border-left: 3px solid currentcolor; opacity: .78; }
.safe-markdown :deep(code) { padding: var(--cs-space-2) var(--cs-space-4); border-radius: 4px; background: var(--cs-code-inline-bg); font-family: var(--cs-font-mono); }
.safe-markdown :deep(pre) { position: relative; overflow-x: auto; padding: var(--cs-space-12); border-radius: 8px; background: var(--cs-code-surface); color: var(--cs-code-text); }
.safe-markdown :deep(pre code) { display: block; padding: 0; background: transparent; color: inherit; }
.safe-markdown :deep(a) { color: var(--cs-text-brand); text-decoration: underline; text-underline-offset: 2px; }
/* R40: tables keep their header/row semantics and scroll inside their own box, never the page. */
.safe-markdown :deep(table) { display: block; max-width: 100%; overflow-x: auto; border-collapse: collapse; }
.safe-markdown :deep(th), .safe-markdown :deep(td) { padding: var(--cs-space-4) var(--cs-space-8); border: 1px solid var(--cs-border); text-align: left; vertical-align: top; }
.safe-markdown :deep(th) { background: var(--cs-surface-subtle); font-weight: var(--cs-weight-semibold); }
.safe-markdown :deep(.safe-markdown__code-copy) { position: absolute; top: var(--cs-space-8); right: var(--cs-space-8); min-height: 44px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 6px; background: var(--cs-surface); color: var(--cs-text-muted); font-size: var(--cs-text-xs); cursor: pointer; }
.safe-markdown :deep(.safe-markdown__code-copy--done) { color: var(--cs-success); }
</style>
