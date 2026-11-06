export const docs = [
  {
    group: { zh: '开始使用', en: 'Getting started' },
    items: [
      { slug: 'what', title: { zh: '什么是 AgentOS', en: 'What is AgentOS' } },
      { slug: 'quick-start', title: { zh: '快速开始', en: 'Quick start' } }
    ]
  },
  {
    group: { zh: '深入理解', en: 'Deep dives' },
    items: [
      { slug: 'architecture', title: { zh: '总体架构', en: 'Architecture' } },
      { slug: 'react-loop', title: { zh: 'ReAct 与轮次', en: 'ReAct and turns' } },
      { slug: 'agent', title: { zh: '定义业务 Agent', en: 'Define an Agent' } },
      { slug: 'tools', title: { zh: '工具与安全', en: 'Tools and security' } },
      { slug: 'memory', title: { zh: '记忆与检索', en: 'Memory and retrieval' } },
      { slug: 'models', title: { zh: '模型与配置', en: 'Models and configuration' } }
    ]
  },
  {
    group: { zh: '参考与规划', en: 'Reference and roadmap' },
    items: [
      { slug: 'reference', title: { zh: 'CLI 与 REST 参考', en: 'CLI and REST reference' } },
      { slug: 'roadmap', title: { zh: '路线图与贡献', en: 'Roadmap and contributing' } }
    ]
  }
]

export function sidebarFor(locale) {
  return docs.map((section) => ({
    text: section.group[locale],
    items: section.items.map((item) => ({ text: item.title[locale], link: `/${locale === 'en' ? 'en/' : ''}docs/${item.slug}` }))
  }))
}

export function navFor(locale) {
  const prefix = locale === 'en' ? '/en/' : '/'
  return [
    { text: locale === 'en' ? 'Home' : '首页', link: prefix },
    { text: locale === 'en' ? 'Docs' : '文档', link: `${prefix}docs/what` },
    {
      text: locale === 'en' ? 'Design docs' : '设计文档',
      items: [
        { text: locale === 'en' ? 'Industry research (Chinese)' : '行业调研', link: 'https://github.com/wangkaiqistart/agent-os/blob/main/docs/IndustryResearch.md' },
        { text: locale === 'en' ? 'Requirements (Chinese)' : '需求文档', link: 'https://github.com/wangkaiqistart/agent-os/blob/main/docs/DemandAnalysis.md' },
        { text: locale === 'en' ? 'Technical solution (Chinese)' : '技术方案', link: 'https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md' },
        { text: locale === 'en' ? 'AI programming guide (Chinese)' : 'AI 编程指南', link: 'https://github.com/wangkaiqistart/agent-os/blob/main/docs/AiProgrammingGuide.md' }
      ]
    }
  ]
}
