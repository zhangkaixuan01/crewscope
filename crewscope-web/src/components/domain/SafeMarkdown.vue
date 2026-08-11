<script setup lang="ts">
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'
import { computed } from 'vue'

const props = defineProps<{ content: string }>()

// Raw HTML stays disabled at the parser boundary. DOMPurify remains a second boundary for links
// and generated nodes so future Markdown extensions cannot silently widen the rendered surface.
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: true, typographer: false })
const rendered = computed(() => sanitize(markdown.render(props.content)))

function sanitize(source: string): string {
  const clean = DOMPurify.sanitize(source, {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 's', 'code', 'pre', 'blockquote', 'ul', 'ol', 'li', 'a', 'h1', 'h2', 'h3', 'h4', 'hr'],
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
  return template.innerHTML
}

function allowedHref(href: string): boolean {
  return href.startsWith('#') || /^(https?:|mailto:)/i.test(href)
}
</script>

<template>
  <!-- The HTML is produced only by the locked-down Markdown and DOM sanitizer pipeline above. -->
  <div class="safe-markdown" v-html="rendered" />
</template>

<style scoped>
.safe-markdown { overflow-wrap: anywhere; font-size: inherit; line-height: 1.65; }
.safe-markdown :deep(p), .safe-markdown :deep(ul), .safe-markdown :deep(ol), .safe-markdown :deep(blockquote), .safe-markdown :deep(pre) { margin: 0 0 8px; }
.safe-markdown :deep(:last-child) { margin-bottom: 0; }
.safe-markdown :deep(ul), .safe-markdown :deep(ol) { padding-left: 19px; }
.safe-markdown :deep(blockquote) { padding-left: 10px; border-left: 3px solid currentcolor; opacity: .78; }
.safe-markdown :deep(code) { padding: 1px 4px; border-radius: 4px; background: rgb(21 35 29 / 8%); font: .9em var(--cs-font-mono); }
.safe-markdown :deep(pre) { overflow-x: auto; padding: 10px; border-radius: 8px; background: #183028; color: #e9f6ed; }
.safe-markdown :deep(pre code) { padding: 0; background: transparent; color: inherit; }
.safe-markdown :deep(a) { color: var(--cs-brand-700); text-decoration: underline; text-underline-offset: 2px; }
</style>
