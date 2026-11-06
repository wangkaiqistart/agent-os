<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useData } from 'vitepress'
import { ui } from '../i18n.mjs'

const { lang } = useData()
const locale = lang.value?.startsWith('en') ? 'en' : 'zh'
const title = locale === 'en' ? 'agentos · turn trace (design)' : 'agentos · turn trace（设计示意）'

// 一次轮次（turn）的示意回放：滚动到可见时逐行出现，播完稍停再重播。
// 内容对应技术方案 12.1 的"每日天气" Demo，数字仅为示意。
const lines = locale === 'en' ? [
  { k: 'meta', t: '▶ turn 7f3a · trigger=scheduler · agent=daily-weather · deadline 300s' },
  { k: 'sys', t: '  lease acquired · prompt = AGENT.md + Bootstrap + memory + 20 Turns' },
  { k: 'llm', t: '[iter 1] ChatModel.call  provider=primary · 2.1s · 4,512 tokens' },
  { k: 'tool', t: '  ↳ tool_call  http_get  {"url":"https://api.weather…/beijing"}' },
  { k: 'ok', t: '    sandbox ✓ domain allowlist · 0.4s · tool_invocations #1' },
  { k: 'llm', t: '[iter 2] ChatModel.call  provider=primary · 1.8s · 4,960 tokens' },
  { k: 'tool', t: '  ↳ tool_call  notify  {"channel":"team-lark","content":"12°C today…"}' },
  { k: 'ok', t: '    sandbox ✓ · redactor ✓ webhook token redacted · tool_invocations #2' },
  { k: 'llm', t: '[iter 3] ChatModel.call  provider=primary · 0.9s · finish_reason=stop' },
  { k: 'done', t: '✔ SUCCEEDED · 3 iterations · 2 tool calls · llm_calls ×3 · 5.2s' }
] : [
  { k: 'meta', t: '▶ turn 7f3a · trigger=scheduler · agent=daily-weather · deadline 300s' },
  { k: 'sys', t: '  lease acquired · prompt = AGENT.md + Bootstrap + 记忆 + 最近 20 轮' },
  { k: 'llm', t: '[iter 1] ChatModel.call  provider=primary · 2.1s · 4,512 tokens' },
  { k: 'tool', t: '  ↳ tool_call  http_get  {"url":"https://api.weather…/beijing"}' },
  { k: 'ok', t: '    sandbox ✓ 域名白名单 · 0.4s · tool_invocations #1' },
  { k: 'llm', t: '[iter 2] ChatModel.call  provider=primary · 1.8s · 4,960 tokens' },
  { k: 'tool', t: '  ↳ tool_call  notify  {"channel":"team-lark","content":"今天 12°C…"}' },
  { k: 'ok', t: '    sandbox ✓ · redactor ✓ webhook token 已脱敏 · tool_invocations #2' },
  { k: 'llm', t: '[iter 3] ChatModel.call  provider=primary · 0.9s · finish_reason=stop' },
  { k: 'done', t: '✔ SUCCEEDED · 3 次迭代 · 2 次工具调用 · llm_calls ×3 · 5.2s' }
]

const shown = ref(0)
const root = ref(null)
let timer = 0
let io = null

const play = () => {
  clearTimeout(timer)
  const step = () => {
    if (shown.value < lines.length) {
      shown.value++
      timer = setTimeout(step, lines[shown.value - 1].k === 'llm' ? 650 : 380)
    } else {
      timer = setTimeout(() => {
        shown.value = 0
        step()
      }, 4200)
    }
  }
  step()
}

onMounted(() => {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    shown.value = lines.length
    return
  }
  io = new IntersectionObserver(([e]) => {
    if (e.isIntersecting) {
      play()
      io.disconnect()
    }
  }, { threshold: 0.35 })
  io.observe(root.value)
})

onBeforeUnmount(() => {
  clearTimeout(timer)
  io && io.disconnect()
})
</script>

<template>
  <div ref="root" class="term">
    <div class="term-bar">
      <span class="dot r"></span><span class="dot y"></span><span class="dot g"></span>
      <span class="term-title">{{ title }}</span>
    </div>
    <pre class="term-body"><code><span
      v-for="(l, i) in lines.slice(0, shown)" :key="i" :class="['ln', l.k]">{{ l.t }}
</span><span class="cursor">▍</span></code></pre>
  </div>
</template>

<style scoped>
.term {
  border-radius: 16px;
  border: 1px solid var(--aos-line);
  background: rgba(3, 7, 18, 0.82);
  backdrop-filter: blur(12px);
  box-shadow: 0 30px 80px -30px rgba(45, 212, 191, 0.35);
  overflow: hidden;
}
.term-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--aos-line);
  background: rgba(255, 255, 255, 0.03);
}
.dot { width: 11px; height: 11px; border-radius: 50%; }
.dot.r { background: #ff5f57; }
.dot.y { background: #febc2e; }
.dot.g { background: #28c840; }
.term-title { margin-left: 8px; font-size: 12px; color: var(--aos-muted); }
.term-body {
  margin: 0;
  padding: 18px 20px 22px;
  min-height: 300px;
  font-family: var(--vp-font-family-mono);
  font-size: 13px;
  line-height: 1.85;
  white-space: pre-wrap;
  word-break: break-all;
  color: #cbd5e1;
}
.ln { display: inline; animation: ln-in 0.35s ease both; }
.meta { color: #94a3b8; }
.sys { color: #64748b; }
.llm { color: var(--aos-sky); }
.tool { color: var(--aos-amber); }
.ok { color: var(--aos-teal); }
.done { color: #4ade80; font-weight: 600; }
.cursor { color: var(--aos-teal); animation: blink 1s steps(1) infinite; }
@keyframes ln-in { from { opacity: 0; } to { opacity: 1; } }
@keyframes blink { 50% { opacity: 0; } }
</style>
