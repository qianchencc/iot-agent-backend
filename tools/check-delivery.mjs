#!/usr/bin/env node
// Internal customer-export gate; .gitattributes remains the export authority.
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const git = (args, cwd) => execFileSync('git', args, {
  cwd, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024,
}).trimEnd()

const FORBIDDEN = [
  /^(\.scratch|\.agents|\.claude|\.codex|\.zcode|plan|tools)\//,
  /(^|\/)(AGENTS|CLAUDE|CONTEXT|GLOSSARY|README)\.md$/,
  /(^|\/)docs\//,
  /^(skills-lock\.json|compose\.business-preview\.yaml)$/,
  /(^|\/)(node_modules|dist|target|var|\.git|\.idea|\.settings)\//,
  /(^|\/)(\.env(?:\.[^/]*)?|\.classpath|\.project|\.DS_Store)$/,
]
const REQUIRED = [
  'pom.xml', 'application.example.yaml', 'agent-engine.example.yaml', 'compose.yaml',
  'docs/deployment.md', 'src/main/resources/application.yml',
  'src/main/resources/db/mysql/agent-facts.sql', 'src/main/resources/reports/LICENSE',
  'src/main/resources/reports/NotoSansSC-Regular.ttf',
  'src/test/java/ltd/qianc/iotagent/ArchitectureTest.java',
  'agent-sidecar/package.json', 'agent-sidecar/package-lock.json',
  'agent-sidecar/src/server.ts', 'web/admin/package.json', 'web/admin/package-lock.json',
  'web/admin/src/main.js',
]

export function checkPaths(paths, complete = true) {
  const files = new Set(paths.filter(path => path && !path.endsWith('/')))
  const forbidden = [...files].filter(path => path !== 'docs/deployment.md' && FORBIDDEN.some(rule => rule.test(path)))
  const missing = complete ? REQUIRED.filter(path => !files.has(path)) : []
  console.log(`客户交付检查：${files.size}个文件，禁止路径${forbidden.length}个，缺少必要文件${missing.length}个`)
  for (const path of forbidden.slice(0, 20)) console.error(`禁止：${path}`)
  for (const path of missing) console.error(`缺少：${path}`)
  if (forbidden.length || missing.length) throw new Error('交付边界未通过；检查 export-ignore 和所选提交。')
}

export function archivePaths(archive) {
  return execFileSync('tar', ['-tf', '-'], {
    input: archive, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024,
  }).split('\n')
}

function main(args) {
  const [mode, ref, ...extra] = args
  if (extra.length || !['--archive', '--tree', '--base', '--worktree'].includes(mode)
      || (mode === '--worktree' ? ref !== undefined : !ref || ref.startsWith('-'))) {
    throw new Error('用法：node tools/check-delivery.mjs --archive <ref> | --tree <ref> | --base <ref> | --worktree')
  }
  let paths
  if (mode === '--archive') {
    paths = archivePaths(execFileSync('git', ['archive', '--format=tar', ref], { maxBuffer: 256 * 1024 * 1024 }))
  } else if (mode === '--tree') {
    paths = git(['ls-tree', '-r', '--name-only', '-z', ref]).split('\0')
  } else if (mode === '--worktree') {
    paths = git(['ls-files', '--cached', '--others', '--exclude-standard', '-z']).split('\0')
  } else {
    paths = git(['diff', '--name-only', '--diff-filter=AMR', '-z', `${ref}...HEAD`]).split('\0')
  }
  checkPaths(paths, mode !== '--base')
  console.log('检查通过；本命令不推送，普通 git push 不受 export-ignore 过滤。')
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(process.argv.slice(2)) }
  catch (error) { console.error(error.message); process.exitCode = 1 }
}
