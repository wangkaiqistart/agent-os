import { defineConfig } from 'vitepress'

const repo = 'https://github.com/wangkaiqistart/agent-os'
const doc = (file: string) => `${repo}/blob/main/docs/${file}`

// 部署到 GitHub Pages 项目页时为 /agent-os/，本地预览和自定义域名时为 /
const base = process.env.BASE || '/'

export default defineConfig({
  base,
  lang: 'zh-CN',
  title: 'AgentOS',
  description: 'Java 原生、私有部署、可审计的企业级 Agent OS：一个目录定义一个 Agent，一个进程跑一群 Agent。',
  appearance: 'force-dark',
  cleanUrls: true,
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}logo.svg` }],
    ['meta', { name: 'theme-color', content: '#050816' }],
    ['meta', { property: 'og:title', content: 'AgentOS — 企业级 Agent OS' }],
    ['meta', { property: 'og:description', content: 'Java 原生、私有部署、可审计的 Agent 统一底座，基于 Spring AI 2.0。' }]
  ],
  themeConfig: {
    logo: '/logo.svg',
    siteTitle: 'AgentOS',
    nav: [
      { text: '首页', link: '/' },
      {
        text: '设计文档',
        items: [
          { text: '行业调研', link: doc('IndustryResearch.md') },
          { text: '需求文档', link: doc('DemandAnalysis.md') },
          { text: '技术方案', link: doc('TechnicalSolution.md') },
          { text: 'AI 编程指南', link: doc('AiProgrammingGuide.md') }
        ]
      }
    ],
    socialLinks: [{ icon: 'github', link: repo }],
    footer: {
      message: '基于 Apache License 2.0 开源',
      copyright: 'Copyright © 2026 AgentOS'
    }
  }
})
