#!/usr/bin/env node
// Same workflow as ../mp: filtered archive -> explicitly requested clean snapshot.
// Never adds a remote, rewrites the development history, or pushes anything.
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { archivePaths, checkPaths, git } from './check-delivery.mjs'

function main(args) {
  const options = { '--ref': 'HEAD', '--out': '', '--message': '' }
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i], value = args[i + 1]
    if (!Object.hasOwn(options, key) || !value || value.startsWith('--')) {
      throw new Error('用法：node tools/deliver.mjs [--ref <commit>] [--out <empty-directory>] [--message <text>]')
    }
    options[key] = value
  }
  if (options['--ref'].startsWith('-')) throw new Error('来源必须是提交或分支。')
  const source = git(['rev-parse', '--verify', `${options['--ref']}^{commit}`])
  if (git(['status', '--porcelain'])) console.warn('工作区有未提交改动；仅导出所选提交，不夹带本地文件。')
  const archive = execFileSync('git', ['archive', '--format=tar', source], { maxBuffer: 256 * 1024 * 1024 })
  checkPaths(archivePaths(archive))

  const out = options['--out'] ? resolve(options['--out']) : mkdtempSync(join(tmpdir(), `backend-deliver-${source.slice(0, 8)}-`))
  if (existsSync(out) && readdirSync(out).length) throw new Error(`输出目录必须为空：${out}`)
  mkdirSync(out, { recursive: true })
  try {
    execFileSync('tar', ['-xf', '-', '-C', out], { input: archive })
    git(['init', '-q', '-b', 'main'], out)
    // Include the exact archive contents, even if the source .gitignore excludes a tracked resource.
    git(['add', '-f', '--all'], out)
    checkPaths(git(['ls-files', '-z'], out).split('\0'))
    git(['-c', 'commit.gpgsign=false', 'commit', '-q', '-m', options['--message'] || `deliver: ${source.slice(0, 12)} snapshot`], out)
  } catch (error) {
    // Only remove a directory that was empty before this invocation.
    rmSync(out, { recursive: true, force: true })
    throw error
  }
  console.log(`来源：${source}\n客户快照：${out}\n提交：${git(['rev-parse', 'HEAD'], out)}`)
  console.log('未设置远端、未推送。仅在核对后的客户仓库接入此快照，不直接合并开发分支历史。')
  console.log(`客户提交树复核：node ${JSON.stringify(join(dirname(fileURLToPath(import.meta.url)), 'check-delivery.mjs'))} --tree HEAD`)
}

try { main(process.argv.slice(2)) }
catch (error) { console.error(error.message); process.exitCode = 1 }
