export const ui = {
  zh: {
    outline: '本页目录', prev: '上一篇', next: '下一篇', lastUpdated: '最后更新',
    editLink: '在 GitHub 编辑此页', menu: '菜单', returnToTop: '返回顶部',
    close: '关闭', notFound: '页面不存在', search: '搜索', basedOn: '设计依据'
  },
  en: {
    outline: 'On this page', prev: 'Previous', next: 'Next', lastUpdated: 'Last updated',
    editLink: 'Edit this page on GitHub', menu: 'Menu', returnToTop: 'Back to top',
    close: 'Close', notFound: 'Page not found', search: 'Search', basedOn: 'Design basis'
  }
}

export const home = {
  zh: {
    badge: '设计阶段 · Maven 骨架已建立 · Apache-2.0',
    title: 'AgentOS',
    subtitle: '让一群 Agent 在企业里跑起来、管起来、审计起来',
    desc: 'Java 原生、私有部署、可审计的企业级 Agent OS。一个目录定义一个 Agent，一个进程承载一群业务 Agent，共享模型接入、ReAct 引擎、记忆、工具、沙箱与审计。',
    start: '快速开始 →', docs: '了解 AgentOS', github: 'GitHub',
    design: '1.0 设计目标', status: '当前状态：Maven 多模块骨架与 CLI 版本入口已建立；业务能力按 US-1 → US-5 实施中。',
    whyTag: '为什么需要', whyTitle: '难点不在做出一个 Agent，而在让它在企业里可控地跑起来',
    whyDesc: '每个团队都在重复建设模型调用、工具执行、记忆与审计。AgentOS 把这些公共能力收进一个私有底座。',
    capabilities: '核心能力', capabilityTitle: '循环自己驱动，组件全部来自 Spring AI 2.0',
    trace: '一次轮次 · 设计示意', agent: '一个目录就是一个 Agent', security: '安全与审计',
    ctaTitle: '从读懂底座开始', ctaDesc: '先了解 AgentOS 的边界，再进入快速开始和核心概念。',
    footer: '一个底座，承载多个业务 Agent。'
  },
  en: {
    badge: 'Design stage · Maven skeleton established · Apache-2.0',
    title: 'AgentOS',
    subtitle: 'Run, govern, and audit a fleet of Agents in your enterprise',
    desc: 'A Java-native, private, auditable Agent OS. Define an Agent with one directory and run many business Agents in one process, sharing model access, ReAct, memory, tools, sandboxing, and audit trails.',
    start: 'Quick start →', docs: 'Explore AgentOS', github: 'GitHub',
    design: '1.0 design targets', status: 'Current status: the Maven skeleton and CLI version entry are ready; runtime capabilities are being built through US-1 → US-5.',
    whyTag: 'Why AgentOS', whyTitle: 'The hard part is not making one Agent. It is running Agents safely in an enterprise.',
    whyDesc: 'Every team rebuilds model calls, tool execution, memory, and audit trails. AgentOS brings those shared capabilities into one private runtime.',
    capabilities: 'Core capabilities', capabilityTitle: 'A self-owned loop, powered by Spring AI 2.0 components',
    trace: 'One turn · design trace', agent: 'One directory defines one Agent', security: 'Security and audit',
    ctaTitle: 'Start with the runtime model', ctaDesc: 'Understand the boundary first, then move through the quick start and core concepts.',
    footer: 'One runtime. Many business Agents.'
  }
}
