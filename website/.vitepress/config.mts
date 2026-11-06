import { defineConfig } from 'vitepress'
import { docs, navFor, sidebarFor } from './docs-navigation.mjs'
import { ui } from './theme/i18n.mjs'

const repo = 'https://github.com/wangkaiqistart/agent-os'
const base = process.env.BASE || '/'
const siteUrl = process.env.SITE_URL || 'https://wangkaiqistart.github.io/agent-os/'
const doc = (file: string) => `${repo}/blob/main/docs/${file}`

const localeConfig = (locale: 'zh' | 'en') => {
  const isEn = locale === 'en'
  return {
    lang: isEn ? 'en-US' : 'zh-CN',
    title: isEn ? 'AgentOS — Enterprise Agent OS' : 'AgentOS — 企业级 Agent OS',
    description: isEn
      ? 'A Java-native, private, auditable runtime for many business Agents.'
      : 'Java 原生、私有部署、可审计的企业级 Agent OS。',
    themeConfig: {
      nav: navFor(locale),
      sidebar: { [`/${isEn ? 'en/' : ''}docs/`]: sidebarFor(locale) },
      outline: { level: [2, 3], label: ui[locale].outline },
      docFooter: { prev: ui[locale].prev, next: ui[locale].next },
      lastUpdated: { text: ui[locale].lastUpdated },
      editLink: { pattern: `${repo}/edit/main/website/:path`, text: ui[locale].editLink },
      sidebarMenuLabel: ui[locale].menu,
      returnToTopLabel: ui[locale].returnToTop,
      langMenuLabel: isEn ? 'Languages' : '语言',
      darkModeSwitchLabel: isEn ? 'Appearance' : '外观',
      socialLinks: [{ icon: 'github', link: repo }],
      footer: {
        message: isEn ? 'Released under the Apache License 2.0' : '基于 Apache License 2.0 开源',
        copyright: 'Copyright © 2026 AgentOS'
      }
    }
  }
}

export default defineConfig({
  base,
  cleanUrls: true,
  appearance: 'dark',
  title: 'AgentOS',
  description: 'AgentOS',
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}logo.svg` }],
    ['meta', { name: 'theme-color', content: '#050816' }],
    ['meta', { property: 'og:site_name', content: 'AgentOS' }]
  ],
  locales: {
    root: localeConfig('zh'),
    en: { ...localeConfig('en'), link: '/en/' }
  },
  themeConfig: {
    logo: '/logo.svg',
    siteTitle: 'AgentOS',
    nav: navFor('zh'),
    sidebar: { '/docs/': sidebarFor('zh') },
    outline: { level: [2, 3], label: ui.zh.outline },
    docFooter: { prev: ui.zh.prev, next: ui.zh.next },
    lastUpdated: { text: ui.zh.lastUpdated },
    editLink: { pattern: `${repo}/edit/main/website/:path`, text: ui.zh.editLink },
    sidebarMenuLabel: ui.zh.menu,
    returnToTopLabel: ui.zh.returnToTop,
    langMenuLabel: ui.zh.langMenuLabel,
    darkModeSwitchLabel: ui.zh.darkModeSwitchLabel,
    socialLinks: [{ icon: 'github', link: repo }],
    footer: { message: '基于 Apache License 2.0 开源', copyright: 'Copyright © 2026 AgentOS' },
    search: { provider: 'local' }
  },
  sitemap: { hostname: siteUrl },
  transformHead({ pageData }) {
    const relative = pageData.relativePath.replace(/\\/g, '/')
    const isEn = relative.startsWith('en/')
    const counterpart = isEn ? relative.slice(3) : `en/${relative}`
    const currentPath = relative.replace(/\.md$/, '')
    const counterpartPath = counterpart.replace(/\.md$/, '')
    const publicUrl = (p: string) => `${siteUrl.replace(/\/$/, '')}/${p}`
    return [
      ['link', { rel: 'canonical', href: publicUrl(currentPath === 'index' ? '' : currentPath) }],
      ['link', { rel: 'alternate', hreflang: isEn ? 'en' : 'zh-CN', href: publicUrl(currentPath === 'index' ? '' : currentPath) }],
      ['link', { rel: 'alternate', hreflang: isEn ? 'zh-CN' : 'en', href: publicUrl(counterpartPath === 'index' ? '' : counterpartPath) }],
      ['link', { rel: 'alternate', hreflang: 'x-default', href: publicUrl(isEn ? '' : currentPath === 'index' ? '' : currentPath) }]
    ]
  }
})
