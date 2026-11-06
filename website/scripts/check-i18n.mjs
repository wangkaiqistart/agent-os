import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(new URL('..', import.meta.url).pathname)
const zh = fs.readdirSync(path.join(root, 'docs')).filter((x) => x.endsWith('.md')).sort()
const en = fs.readdirSync(path.join(root, 'en/docs')).filter((x) => x.endsWith('.md')).sort()
if (zh.join('|') !== en.join('|')) {
  console.error('中英文 Docs 文件不匹配')
  console.error('zh:', zh)
  console.error('en:', en)
  process.exit(1)
}
console.log(`i18n parity ok: ${zh.length} pairs`)
