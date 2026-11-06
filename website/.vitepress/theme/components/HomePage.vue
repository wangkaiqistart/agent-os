<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useData, withBase } from 'vitepress'
import HeroScene from './HeroScene.vue'
import ReactTrace from './ReactTrace.vue'
import { home } from '../i18n.mjs'

const { lang } = useData()
const locale = lang.value?.startsWith('en') ? 'en' : 'zh'
const copy = home[locale]
const repo = 'https://github.com/wangkaiqistart/agent-os'
const docs = (slug) => withBase(`${locale === 'en' ? '/en/' : '/'}docs/${slug}`)

const words = locale === 'en' ? ['run', 'govern', 'audit', 'stay private'] : ['跑起来', '管起来', '审计起来', '留在企业里']
const word = ref(words[0])

const stats = locale === 'en' ? [
  { n: 5, label: 'design capabilities' },
  { n: 9, label: 'target built-in Tools' },
  { n: 10, label: 'target REST endpoints' },
  { n: 3, label: 'shared trigger paths' }
] : [
  { n: 5, label: '设计能力' },
  { n: 9, label: '目标内置工具' },
  { n: 10, label: '目标 REST 端点' },
  { n: 3, label: '共享触发入口' }
]
const counts = ref(stats.map(() => 0))

const pains = locale === 'en' ? [
  'Every team rebuilds model calls, tool execution, memory, and audit trails',
  'Connecting Node.js / Python Agent projects to Java systems adds glue code',
  'Tool failures are hard to explain and harder to trace',
  'External SaaS data paths complicate security and compliance reviews'
] : [
  '每个业务都要自己写一遍模型调用、工具执行、记忆和审计',
  '现有开源 Agent OS 是 Node.js / Python，接 Java 系统要写大量胶水',
  '工具调用失败没人知道，出了问题无从追溯',
  '数据经过外部 SaaS，过不了安全与合规评审'
]
const gains = locale === 'en' ? [
  'Many Agents share one runtime; a new Agent starts with one directory',
  'A Spring Boot application that fits existing Java operations and audit flows',
  'Every model and tool call is designed to land in an audit record from day one',
  'Private deployment keeps data inside the enterprise and secrets in environment variables'
] : [
  '多个 Agent 共用一个底座，新增一个 Agent 只要放一个目录',
  '就是一个 Spring Boot 应用，直接纳入现有 Java 运维与审计流程',
  '每次模型调用、工具调用从第一天就写入审计表',
  '私有部署，数据不出企业，密钥只走环境变量'
]

const caps = locale === 'en' ? [
  { icon: '◎', title: 'LLM access', tag: 'Spring AI 2.0 ChatModel', desc: 'Map named Providers to ChatModels and switch between OpenAI-compatible, Anthropic, and Ollama endpoints.' },
  { icon: '⟳', title: 'ReAct loop', tag: 'ToolCallingManager', desc: 'AgentOS drives the loop with Spring AI 2.0 components, two limits, four timeouts, and one sync/stream path.' },
  { icon: '◈', title: 'Three-layer memory', tag: 'PgVectorStore', desc: 'Truncate complete Turns, separate core and archival memory, and add semantic retrieval with Embeddings.' },
  { icon: '⬡', title: 'Tool system', tag: 'ToolCallback · MCP', desc: 'Nine target built-ins plus MCP, @Tool Beans, and zero-code extensions through one governed callback.' },
  { icon: '⇄', title: 'Web Service', tag: '10 target REST endpoints', desc: 'Sessions, invocations, configuration, memory, tools, and system state with 202 and 409 semantics.' }
] : [
  { icon: '◎', title: '对接 LLM', tag: 'Spring AI 2.0 ChatModel', desc: 'provider name 显式映射到 ChatModel，OpenAI 兼容、Anthropic、Ollama 都能接，换模型不改业务。' },
  { icon: '⟳', title: 'ReAct 循环', tag: 'ToolCallingManager', desc: '循环自己驱动，组件全部来自 Spring AI 2.0。两道上限、四层超时，同步与流式共用一套循环。' },
  { icon: '◈', title: '三层记忆', tag: 'PgVectorStore', desc: '会话按轮次截断，长期记忆分核心区与归档区，配置 Embedding 后走 pgvector 语义检索。' },
  { icon: '⬡', title: 'Tool 体系', tag: 'ToolCallback · MCP', desc: '9 个内置工具，MCP、@Tool Bean、零代码三档扩展，统一由 GovernedToolCallback 审计、超时、重试。' },
  { icon: '⇄', title: 'Web Service', tag: '10 个目标 REST 端点', desc: '会话、调用、配置、记忆、工具、系统状态。同步等待超时返回 202，同一会话串行，冲突返回 409。' }
]

const guards = locale === 'en' ? [
  { t: 'Sandbox contract first', d: 'Path, executable, and domain allowlists; the contract can later back containers or microVMs.' },
  { t: 'Retry only idempotent Tools', d: 'Side-effecting notify and http_post calls are not retried automatically.' },
  { t: 'Redact before audit', d: 'Webhook tokens, Authorization values, and environment secrets are removed before persistence.' },
  { t: 'Memory is data, not instructions', d: 'A marked memory block and entry limits reduce prompt-injection risk.' },
  { t: 'Bound tool results', d: 'Oversized results are truncated with their original length instead of flooding context.' },
  { t: 'Secrets stay out of files', d: 'Use ${ENV_VAR} placeholders and ConfigLoader validation; never commit .env.' }
] : [
  { t: 'Sandbox 接口先行', d: '路径、可执行文件、域名白名单；接口不变，后续可换容器或 microVM。' },
  { t: '只重试幂等工具', d: 'notify、http_post 这类有副作用的工具不自动重试，避免重复推送。' },
  { t: '审计先脱敏', d: 'webhook token、Authorization、环境变量密钥在落库和返回模型前替换。' },
  { t: '记忆是数据不是指令', d: '长期记忆放进标注过的分隔块，单条限长，防止提示词注入。' },
  { t: '结果截断', d: '工具结果超过上限就截断并注明原长，不会撑爆上下文。' },
  { t: '密钥不落盘', d: '只用 ${ENV_VAR} 占位，由 ConfigLoader 解析校验，.env 不进仓库。' }
]

const stack = ['JDK 21', 'Spring Boot 4.1', 'Spring AI 2.0', 'Spring MVC', 'PostgreSQL 16+', 'pgvector',
  'Flyway', 'ShedLock', 'MCP', 'Picocli', 'Micrometer', 'Testcontainers']

// 卡片跟随鼠标的光斑
const spot = (e) => {
  const el = e.currentTarget
  const r = el.getBoundingClientRect()
  el.style.setProperty('--mx', `${e.clientX - r.left}px`)
  el.style.setProperty('--my', `${e.clientY - r.top}px`)
}

const page = ref(null)
const statsEl = ref(null)
let wordTimer = 0
let reveal = null
let statIo = null

const countUp = () => {
  const start = performance.now()
  const tick = (now) => {
    const k = Math.min((now - start) / 1400, 1)
    const ease = 1 - Math.pow(1 - k, 3)
    counts.value = stats.map((s) => Math.round(s.n * ease))
    if (k < 1) requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
}

onMounted(() => {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches

  let wi = 0
  if (!reduce) {
    wordTimer = setInterval(() => {
      wi = (wi + 1) % words.length
      word.value = words[wi]
    }, 2200)
  }

  // 区块进入视口时淡入上移
  reveal = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (e.isIntersecting) {
        e.target.classList.add('in')
        reveal.unobserve(e.target)
      }
    }
  }, { threshold: 0.15 })
  page.value.querySelectorAll('.reveal').forEach((el) => (reduce ? el.classList.add('in') : reveal.observe(el)))

  statIo = new IntersectionObserver(([e]) => {
    if (e.isIntersecting) {
      reduce ? (counts.value = stats.map((s) => s.n)) : countUp()
      statIo.disconnect()
    }
  }, { threshold: 0.4 })
  statIo.observe(statsEl.value)
})

onBeforeUnmount(() => {
  clearInterval(wordTimer)
  reveal && reveal.disconnect()
  statIo && statIo.disconnect()
})
</script>

<template>
  <div ref="page" class="aos">
    <ClientOnly><HeroScene /></ClientOnly>

    <!-- 首屏 -->
    <section class="hero">
      <div class="hero-inner">
        <div class="badge"><span class="badge-dot"></span>{{ copy.badge }}</div>
        <h1 class="title"><span class="grad">{{ copy.title }}</span></h1>
        <p class="subtitle">
          {{ locale === 'en' ? 'Let many Agents' : '让一群 Agent 在企业里' }}
          <span class="word-wrap"><Transition name="word" mode="out-in"><span :key="word" class="word">{{ word }}</span></Transition></span>
        </p>
        <p class="desc">{{ copy.desc }}</p>
        <div class="actions">
          <a class="btn primary" :href="docs('quick-start')">{{ copy.start }}</a>
          <a class="btn ghost" :href="docs('what')">{{ copy.docs }}</a>
          <a class="btn ghost" :href="repo" target="_blank" rel="noopener">{{ copy.github }}</a>
        </div>
        <p class="note">JDK 21 · Spring Boot 4 · Spring AI 2.0 · PostgreSQL + pgvector · MCP</p>
      </div>
      <a class="scroll-hint" href="#why" :aria-label="locale === 'en' ? 'Scroll down' : '向下滚动'"><span></span></a>
    </section>

    <!-- 设计目标 -->
    <section ref="statsEl" class="stats-wrap reveal">
      <div class="stats-label">{{ copy.design }}</div>
      <section class="stats">
      <div v-for="(s, i) in stats" :key="s.label" class="stat">
        <div class="stat-n">{{ counts[i] }}</div>
        <div class="stat-l">{{ s.label }}</div>
      </div>
      </section>
      <p class="status-line">{{ copy.status }}</p>
    </section>

    <!-- 为什么需要 -->
    <section id="why" class="section">
      <div class="head reveal">
        <span class="tag">{{ copy.whyTag }}</span>
        <h2>{{ copy.whyTitle }}</h2>
        <p>{{ copy.whyDesc }}</p>
      </div>
      <div class="compare">
        <div class="cmp bad reveal">
          <div class="cmp-label">{{ locale === 'en' ? 'Without a shared runtime' : '没有统一底座' }}</div>
          <div v-for="t in pains" :key="t" class="cmp-row"><span class="ico x">✕</span>{{ t }}</div>
        </div>
        <div class="cmp good reveal">
          <div class="cmp-label">{{ locale === 'en' ? 'With AgentOS' : '有了 AgentOS' }}</div>
          <div v-for="t in gains" :key="t" class="cmp-row"><span class="ico ok">✓</span>{{ t }}</div>
        </div>
      </div>
    </section>

    <!-- 五大核心能力 -->
    <section class="section">
      <div class="head reveal">
        <span class="tag">{{ copy.capabilities }}</span>
        <h2>{{ copy.capabilityTitle }}</h2>
      </div>
      <div class="caps">
        <article v-for="(c, i) in caps" :key="c.title" class="card reveal" :style="{ '--d': i * 70 + 'ms' }" @mousemove="spot">
          <div class="card-icon">{{ c.icon }}</div>
          <h3>{{ c.title }}</h3>
          <div class="card-tag">{{ c.tag }}</div>
          <p>{{ c.desc }}</p>
        </article>
      </div>
    </section>

    <!-- 一次轮次 -->
    <section class="section split">
      <div class="split-text reveal">
        <span class="tag">{{ copy.trace }}</span>
        <h2>{{ locale === 'en' ? 'Every model and tool call has a trace' : '每一次模型调用、每一次工具调用，都有据可查' }}</h2>
        <ul class="bullets">
          <li><b>{{ locale === 'en' ? 'Session serialization' : '会话串行' }}</b>{{ locale === 'en' ? ': one Turn at a time, lease renewed before each iteration' : '：同一会话一次只跑一个轮次，每次迭代前续租' }}</li>
          <li><b>{{ locale === 'en' ? 'Two limits' : '两道上限' }}</b>{{ locale === 'en' ? ': max_iterations for model calls and spring.ai.tools.limits for tools' : '：max_iterations 限模型调用，spring.ai.tools.limits 限工具调用' }}</li>
          <li><b>{{ locale === 'en' ? 'Four timeouts' : '四层超时' }}</b>{{ locale === 'en' ? ': model, tool, Turn, and synchronous HTTP wait' : '：单次模型调用、单个工具、整轮、HTTP 同步等待' }}</li>
          <li><b>{{ locale === 'en' ? 'Audited storage' : '审计落库' }}</b>{{ locale === 'en' ? ': llm_calls and tool_invocations connect every iteration' : '：llm_calls 与 tool_invocations 串起每一次迭代' }}</li>
        </ul>
      </div>
      <div class="reveal"><ReactTrace /></div>
    </section>

    <!-- 一个目录 = 一个 Agent -->
    <section class="section split reverse">
      <div class="reveal">
        <div class="code-card">
          <div class="code-tab">.AgentOS/agents/daily-weather/AGENT.md</div>
<pre v-pre class="code"><span class="m">---</span>
<span class="k">name</span>: daily-weather
<span class="k">description</span>: 每天早上查天气并推送穿搭建议
<span class="k">provider</span>:
  <span class="k">name</span>: primary
  <span class="k">reasoning_effort</span>: low
<span class="k">tools</span>: [http_get, notify]
<span class="k">schedules</span>:
  - <span class="k">key</span>: morning
    <span class="k">cron</span>: <span class="s">"0 0 8 * * *"</span>
    <span class="k">zone</span>: Asia/Shanghai
    <span class="k">message</span>: 生成今天的天气穿搭建议
<span class="m">---</span>
<span class="c">查询北京当天天气，给出简短的穿搭建议，
然后通过通知渠道 team-lark 推送。</span></pre>
        </div>
      </div>
      <div class="split-text reveal">
        <span class="tag">定义一个 Agent</span>
        <h2>一个目录就是一个 Agent，不写一行后端代码</h2>
        <p>frontmatter 是运行配置，正文是任务指令。需要更多能力时，在同一目录里继续加：</p>
        <ul class="tree">
          <li><b>AGENT.md</b><span>配置 + 指令，正文每轮现读，改完立即生效</span></li>
          <li><b>skills/</b><span>指向公共 Skill 的软连接，只注入名称和描述，正文按需读</span></li>
          <li><b>scripts/</b><span>捆绑脚本，产出进上下文，代码不进</span></li>
          <li><b>REFERENCE.md</b><span>参考资料，按需读取</span></li>
        </ul>
      </div>
    </section>

    <!-- 三个入口 -->
    <section class="section">
      <div class="head reveal">
        <span class="tag">三个入口，一条链路</span>
        <h2>人推、钟推，走的都是同一个 AgentService</h2>
        <p>CLI 对话、业务系统调用 REST、cron 到点触发，ReAct 循环不感知消息从哪里来。</p>
      </div>
      <div class="flow reveal">
        <svg viewBox="0 0 960 300" role="img" aria-label="CLI、REST、定时任务三个入口汇入 AgentService，再经 ReAct 循环调度 Provider、Memory、Tool">
          <defs>
            <linearGradient id="fg" x1="0" x2="1">
              <stop offset="0" stop-color="#38bdf8" /><stop offset="1" stop-color="#2dd4bf" />
            </linearGradient>
          </defs>
          <g class="flow-lines">
            <path d="M200 60 C 290 60, 290 150, 380 150" />
            <path d="M200 150 L 380 150" />
            <path d="M200 240 C 290 240, 290 150, 380 150" />
            <path d="M580 150 C 670 150, 670 60, 760 60" />
            <path d="M580 150 L 760 150" />
            <path d="M580 150 C 670 150, 670 240, 760 240" />
          </g>
          <g class="flow-node">
            <rect x="40" y="36" width="160" height="48" rx="12" /><text x="120" y="66">CLI · chat</text>
            <rect x="40" y="126" width="160" height="48" rx="12" /><text x="120" y="156">REST · invoke</text>
            <rect x="40" y="216" width="160" height="48" rx="12" /><text x="120" y="246">cron · 定时</text>
          </g>
          <g class="flow-core">
            <rect x="380" y="106" width="200" height="88" rx="18" />
            <text x="480" y="144">AgentService</text>
            <text x="480" y="170" class="sub">ReActLoop</text>
          </g>
          <g class="flow-node out">
            <rect x="760" y="36" width="160" height="48" rx="12" /><text x="840" y="66">Provider</text>
            <rect x="760" y="126" width="160" height="48" rx="12" /><text x="840" y="156">Memory</text>
            <rect x="760" y="216" width="160" height="48" rx="12" /><text x="840" y="246">Tool</text>
          </g>
        </svg>
      </div>
    </section>

    <!-- 安全与审计 -->
    <section class="section">
      <div class="head reveal">
        <span class="tag">{{ copy.security }}</span>
        <h2>{{ locale === 'en' ? 'Designed for governed enterprise runtime' : '为严监管企业设计，安全是第一天就有的' }}</h2>
      </div>
      <div class="guards">
        <div v-for="(g, i) in guards" :key="g.t" class="guard reveal" :style="{ '--d': i * 60 + 'ms' }" @mousemove="spot">
          <h4>{{ g.t }}</h4>
          <p>{{ g.d }}</p>
        </div>
      </div>
      <div class="stack reveal">
        <span v-for="s in stack" :key="s" class="chip">{{ s }}</span>
      </div>
    </section>

    <!-- 快速开始 -->
    <section id="quickstart" class="section cta reveal">
      <h2>{{ copy.ctaTitle }}</h2>
      <p>{{ copy.ctaDesc }}</p>
<pre v-if="locale !== 'en'" class="code cta-code"><span class="c"># 配置模型与 Embedding（.env 不提交）</span>
cp .env.example .env

<span class="c"># 构建并运行版本入口</span>
mvn -B clean package
java -jar AgentOS-boot/target/AgentOS.jar --version</pre>
<pre v-else class="code cta-code"><span class="c"># Configure models and Embeddings (.env is never committed)</span>
cp .env.example .env

<span class="c"># Build and run the version entry point</span>
mvn -B clean package
java -jar AgentOS-boot/target/AgentOS.jar --version</pre>
      <div class="actions">
        <a class="btn primary" :href="docs('quick-start')">{{ locale === 'en' ? 'Read the quick start' : '阅读快速开始' }}</a>
        <a class="btn ghost" :href="docs('architecture')">{{ locale === 'en' ? 'Architecture' : '总体架构' }}</a>
        <a class="btn ghost" :href="repo" target="_blank" rel="noopener">GitHub</a>
      </div>
    </section>
  </div>
</template>

<style scoped>
.aos { position: relative; z-index: 1; overflow-x: clip; }
.aos > section { position: relative; z-index: 1; }

/* ---------- 首屏 ---------- */
.hero {
  min-height: calc(100vh - var(--vp-nav-height));
  display: flex;
  align-items: center;
  padding: 40px 24px 96px;
}
.hero-inner { width: 100%; max-width: 1200px; margin: 0 auto; }
.hero-inner > * { max-width: 640px; }
.badge {
  display: inline-flex; align-items: center; gap: 10px;
  padding: 6px 16px; border-radius: 999px;
  border: 1px solid var(--aos-line); background: rgba(15, 23, 42, 0.6);
  backdrop-filter: blur(8px);
  font-size: 13px; color: var(--aos-muted);
  animation: rise 0.8s ease both;
}
.badge-dot {
  width: 8px; height: 8px; border-radius: 50%; background: var(--aos-teal);
  box-shadow: 0 0 0 0 rgba(45, 212, 191, 0.7); animation: ping 2s infinite;
}
.title {
  margin: 28px 0 8px;
  font-size: clamp(64px, 11vw, 132px);
  line-height: 1;
  font-weight: 800;
  letter-spacing: -0.04em;
  animation: rise 0.8s 0.1s ease both;
}
.grad {
  background: linear-gradient(110deg, #e0f2fe 0%, #38bdf8 35%, #2dd4bf 60%, #fbbf24 100%);
  background-size: 200% 100%;
  -webkit-background-clip: text; background-clip: text; color: transparent;
  animation: shine 8s linear infinite;
  filter: drop-shadow(0 0 30px rgba(56, 189, 248, 0.35));
}
.subtitle {
  margin: 0 0 22px;
  font-size: clamp(22px, 3vw, 34px); font-weight: 700; color: #e2e8f0;
  animation: rise 0.8s 0.2s ease both;
}
.word-wrap { display: inline-block; min-width: 5.5em; }
.word { display: inline-block; color: var(--aos-amber); }
.word-enter-active, .word-leave-active { transition: all 0.35s ease; }
.word-enter-from { opacity: 0; transform: translateY(14px); filter: blur(6px); }
.word-leave-to { opacity: 0; transform: translateY(-14px); filter: blur(6px); }
.desc {
  margin: 0 0 34px; font-size: 17px; line-height: 1.8; color: #a5b4c8;
  animation: rise 0.8s 0.3s ease both;
}
.actions { display: flex; gap: 12px; flex-wrap: wrap; animation: rise 0.8s 0.4s ease both; }
.btn {
  display: inline-flex; align-items: center; padding: 12px 26px; border-radius: 12px;
  font-size: 15px; font-weight: 600; text-decoration: none !important;
  transition: transform 0.2s, box-shadow 0.2s, background 0.2s, border-color 0.2s;
}
.btn.primary {
  color: #04111d !important;
  background: linear-gradient(120deg, #38bdf8, #2dd4bf);
  box-shadow: 0 10px 40px -10px rgba(45, 212, 191, 0.8);
}
.btn.primary:hover { transform: translateY(-2px); box-shadow: 0 16px 50px -10px rgba(45, 212, 191, 0.95); }
.btn.ghost {
  color: #e2e8f0 !important; border: 1px solid var(--aos-line);
  background: rgba(15, 23, 42, 0.55); backdrop-filter: blur(8px);
}
.btn.ghost:hover { border-color: rgba(56, 189, 248, 0.6); transform: translateY(-2px); }
.note { margin-top: 22px; font-size: 13px; color: #64748b; animation: rise 0.8s 0.5s ease both; }

.scroll-hint {
  position: absolute; left: 50%; bottom: 28px; transform: translateX(-50%);
  width: 26px; height: 42px; border-radius: 14px; border: 2px solid rgba(148, 163, 184, 0.5);
}
.scroll-hint span {
  position: absolute; left: 50%; top: 8px; width: 4px; height: 8px; margin-left: -2px;
  border-radius: 2px; background: var(--aos-teal); animation: wheel 1.8s infinite;
}

/* ---------- 数字 ---------- */
.stats-wrap { max-width: 1100px; margin: 0 auto 40px; text-align: center; }
.stats-label { margin-bottom: 12px; font-size: 12px; letter-spacing: 0.12em; text-transform: uppercase; color: var(--aos-amber); }
.status-line { max-width: 760px; margin: -22px auto 0; padding: 0 24px; font-size: 13px; line-height: 1.7; color: var(--aos-muted); }
.stats {
  max-width: 1100px; margin: 0 auto 40px; padding: 28px 24px;
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px;
  border-radius: 20px; border: 1px solid var(--aos-line);
  background: var(--aos-panel); backdrop-filter: blur(14px);
}
.stat { text-align: center; }
.stat-n {
  font-size: 46px; font-weight: 800; line-height: 1.1;
  background: linear-gradient(180deg, #fff, #7dd3fc);
  -webkit-background-clip: text; background-clip: text; color: transparent;
  font-variant-numeric: tabular-nums;
}
.stat-l { margin-top: 6px; font-size: 13px; color: var(--aos-muted); }

/* ---------- 通用区块 ---------- */
.section { max-width: 1200px; margin: 0 auto; padding: 110px 24px 20px; }
.head { max-width: 760px; margin: 0 auto 48px; text-align: center; }
.tag {
  display: inline-block; margin-bottom: 16px; padding: 5px 14px; border-radius: 999px;
  font-size: 12px; font-weight: 700; letter-spacing: 0.08em; color: var(--aos-teal);
  border: 1px solid rgba(45, 212, 191, 0.35); background: rgba(45, 212, 191, 0.08);
}
.section h2 {
  margin: 0 0 16px; padding: 0; border: 0;
  font-size: clamp(28px, 3.6vw, 42px); line-height: 1.25; font-weight: 800; letter-spacing: -0.02em;
  color: #f1f5f9;
}
.head p, .split-text > p { font-size: 16px; line-height: 1.8; color: #94a3b8; margin: 0; }

.reveal { opacity: 0; transform: translateY(28px); transition: opacity 0.8s ease, transform 0.8s ease; transition-delay: var(--d, 0ms); }
.reveal.in { opacity: 1; transform: none; }

/* ---------- 对比 ---------- */
.compare { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.cmp {
  padding: 28px; border-radius: 20px; border: 1px solid var(--aos-line);
  background: var(--aos-panel); backdrop-filter: blur(14px);
}
.cmp.good { border-color: rgba(45, 212, 191, 0.4); box-shadow: 0 0 60px -20px rgba(45, 212, 191, 0.45) inset; }
.cmp-label { margin-bottom: 14px; font-size: 13px; font-weight: 700; letter-spacing: 0.08em; color: var(--aos-muted); }
.cmp.good .cmp-label { color: var(--aos-teal); }
.cmp-row { display: flex; gap: 12px; padding: 11px 0; font-size: 15px; line-height: 1.6; color: #cbd5e1; border-top: 1px dashed rgba(148, 163, 184, 0.15); }
.cmp-row:first-of-type { border-top: 0; }
.ico { flex: none; width: 22px; height: 22px; border-radius: 50%; display: grid; place-items: center; font-size: 12px; margin-top: 1px; }
.ico.x { color: #f87171; background: rgba(248, 113, 113, 0.12); }
.ico.ok { color: var(--aos-teal); background: rgba(45, 212, 191, 0.14); }

/* ---------- 能力卡片（鼠标光斑） ---------- */
.caps { display: grid; grid-template-columns: repeat(6, 1fr); gap: 18px; }
.caps .card { grid-column: span 2; }
.caps .card:nth-child(4) { grid-column: 2 / span 2; }
.card, .guard {
  position: relative; overflow: hidden;
  padding: 26px; border-radius: 20px; border: 1px solid var(--aos-line);
  background: var(--aos-panel); backdrop-filter: blur(14px);
  transition: transform 0.3s ease, border-color 0.3s ease, opacity 0.8s ease;
}
.card::before, .guard::before {
  content: ''; position: absolute; inset: 0; opacity: 0; transition: opacity 0.3s;
  background: radial-gradient(360px circle at var(--mx, 50%) var(--my, 50%), rgba(56, 189, 248, 0.16), transparent 60%);
  pointer-events: none;
}
.card:hover, .guard:hover { border-color: rgba(56, 189, 248, 0.5); transform: translateY(-4px); }
.card:hover::before, .guard:hover::before { opacity: 1; }
.card-icon {
  width: 46px; height: 46px; border-radius: 14px; display: grid; place-items: center;
  font-size: 22px; color: #04111d; background: linear-gradient(135deg, #38bdf8, #2dd4bf);
  box-shadow: 0 8px 30px -8px rgba(45, 212, 191, 0.8);
}
.card h3 { margin: 18px 0 6px; font-size: 20px; font-weight: 700; color: #f1f5f9; }
.card-tag { font-family: var(--vp-font-family-mono); font-size: 12px; color: var(--aos-amber); }
.card p { margin: 12px 0 0; font-size: 14.5px; line-height: 1.75; color: #94a3b8; }

/* ---------- 左右分栏 ---------- */
.split { display: grid; grid-template-columns: 1fr 1.15fr; gap: 56px; align-items: center; }
.split.reverse { grid-template-columns: 1.1fr 1fr; }
.bullets, .tree { list-style: none; margin: 24px 0 0; padding: 0; }
.bullets li { position: relative; padding: 10px 0 10px 22px; font-size: 15px; line-height: 1.7; color: #cbd5e1; }
.bullets li::before { content: ''; position: absolute; left: 0; top: 19px; width: 8px; height: 8px; border-radius: 50%; background: var(--aos-teal); box-shadow: 0 0 12px var(--aos-teal); }
.bullets b, .tree b { color: #f1f5f9; }
.tree li { display: flex; flex-direction: column; gap: 2px; padding: 12px 16px; margin-bottom: 10px; border-radius: 12px; border: 1px solid var(--aos-line); background: rgba(15, 23, 42, 0.5); }
.tree b { font-family: var(--vp-font-family-mono); font-size: 14px; color: var(--aos-sky); }
.tree span { font-size: 14px; color: #94a3b8; }

/* ---------- 代码 ---------- */
.code-card { border-radius: 16px; border: 1px solid var(--aos-line); background: rgba(3, 7, 18, 0.82); backdrop-filter: blur(12px); overflow: hidden; box-shadow: 0 30px 80px -30px rgba(56, 189, 248, 0.35); }
.code-tab { padding: 12px 18px; font-family: var(--vp-font-family-mono); font-size: 12px; color: var(--aos-muted); border-bottom: 1px solid var(--aos-line); background: rgba(255, 255, 255, 0.03); }
.code { margin: 0; padding: 20px 22px; font-family: var(--vp-font-family-mono); font-size: 13.5px; line-height: 1.8; color: #e2e8f0; white-space: pre; overflow-x: auto; background: transparent; }
.code .k { color: var(--aos-sky); }
.code .s { color: var(--aos-amber); }
.code .m { color: #64748b; }
.code .c { color: #7c8aa0; }

/* ---------- 三个入口（SVG 流动线） ---------- */
.flow { padding: 24px; border-radius: 24px; border: 1px solid var(--aos-line); background: var(--aos-panel); backdrop-filter: blur(14px); }
.flow svg { display: block; width: 100%; height: auto; }
.flow-lines path { fill: none; stroke: url(#fg); stroke-width: 2.5; stroke-dasharray: 8 10; opacity: 0.85; animation: dash 1.6s linear infinite; }
.flow-node rect { fill: rgba(15, 23, 42, 0.9); stroke: rgba(56, 189, 248, 0.55); stroke-width: 1.5; }
.flow-node.out rect { stroke: rgba(251, 191, 36, 0.6); }
.flow svg text { fill: #e2e8f0; font-size: 16px; font-weight: 600; text-anchor: middle; dominant-baseline: middle; font-family: var(--vp-font-family-base); }
.flow-core rect { fill: rgba(45, 212, 191, 0.12); stroke: #2dd4bf; stroke-width: 2; filter: drop-shadow(0 0 18px rgba(45, 212, 191, 0.6)); }
.flow-core text { font-size: 19px; font-weight: 800; }
.flow-core text.sub { font-size: 14px; font-weight: 500; fill: #7dd3fc; font-family: var(--vp-font-family-mono); }

/* ---------- 安全 ---------- */
.guards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 18px; }
.guard h4 { margin: 0 0 8px; font-size: 17px; font-weight: 700; color: #f1f5f9; }
.guard h4::before { content: '◆'; margin-right: 10px; color: var(--aos-teal); font-size: 12px; vertical-align: 2px; }
.guard p { margin: 0; font-size: 14.5px; line-height: 1.75; color: #94a3b8; }
.stack { display: flex; flex-wrap: wrap; justify-content: center; gap: 10px; margin-top: 44px; }
.chip { padding: 7px 16px; border-radius: 999px; font-size: 13px; color: #cbd5e1; border: 1px solid var(--aos-line); background: rgba(15, 23, 42, 0.6); transition: border-color 0.2s, color 0.2s; }
.chip:hover { border-color: rgba(251, 191, 36, 0.6); color: var(--aos-amber); }

/* ---------- 收尾 ---------- */
.cta {
  max-width: 860px; margin: 110px auto 80px; padding: 56px 32px; text-align: center;
  border-radius: 28px; border: 1px solid rgba(45, 212, 191, 0.35);
  background: radial-gradient(80% 120% at 50% 0%, rgba(45, 212, 191, 0.16), transparent 70%), var(--aos-panel);
  backdrop-filter: blur(14px);
}
.cta > p { margin: 0 0 26px; color: #94a3b8; font-size: 16px; }
.cta-code { display: inline-block; max-width: 100%; text-align: left; margin: 0 auto 30px; border-radius: 14px; border: 1px solid var(--aos-line); background: rgba(3, 7, 18, 0.8); }
.cta .actions { justify-content: center; animation: none; }

/* ---------- 动画 ---------- */
@keyframes rise { from { opacity: 0; transform: translateY(18px); } to { opacity: 1; transform: none; } }
@keyframes shine { to { background-position: -200% 0; } }
@keyframes ping { 70% { box-shadow: 0 0 0 10px rgba(45, 212, 191, 0); } 100% { box-shadow: 0 0 0 0 rgba(45, 212, 191, 0); } }
@keyframes wheel { 0% { opacity: 0; transform: translateY(0); } 30% { opacity: 1; } 100% { opacity: 0; transform: translateY(14px); } }
@keyframes dash { to { stroke-dashoffset: -36; } }

@media (prefers-reduced-motion: reduce) {
  .grad, .flow-lines path, .badge-dot, .scroll-hint span { animation: none; }
}

/* ---------- 响应式 ---------- */
@media (max-width: 1099px) {
  .hero-inner { text-align: center; }
  .hero-inner > * { margin-left: auto; margin-right: auto; }
  .actions { justify-content: center; }
  .hero { background: radial-gradient(70% 60% at 50% 45%, rgba(5, 8, 22, 0.55), transparent 80%); }
}
@media (max-width: 900px) {
  .compare, .split, .split.reverse { grid-template-columns: 1fr; }
  .caps { grid-template-columns: 1fr 1fr; }
  .caps .card, .caps .card:nth-child(4) { grid-column: auto; }
  .guards { grid-template-columns: 1fr 1fr; }
  .stats { grid-template-columns: repeat(2, 1fr); row-gap: 24px; margin: 0 16px 40px; }
  .section { padding-top: 80px; }
}
@media (max-width: 600px) {
  .caps, .guards { grid-template-columns: 1fr; }
  .stat-n { font-size: 38px; }
}
</style>
