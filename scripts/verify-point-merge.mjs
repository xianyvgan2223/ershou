// 验证点位合并逻辑：直接 import 前端的 pointMerge.ts，用真实接口数据跑一遍
// 用法： node --experimental-strip-types scripts/verify-point-merge.mjs
//   （Node 22.6+ 支持剥离类型后直接跑 .ts；脚本里会检查这个开关）
import { readFileSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, join } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const api = process.env.API_BASE || 'http://127.0.0.1:8080/api/v1'

const modPath = join(root, 'ershou-web/src/utils/pointMerge.ts')
let mod
try {
  mod = await import(pathToFileURL(modPath).href)
} catch (error) {
  console.error('无法直接 import .ts：' + error.message)
  console.error('请用： node --experimental-strip-types scripts/verify-point-merge.mjs')
  console.error('（Node 22.6+ 才支持；或用 vite 的 esbuild 预编译）')
  process.exit(2)
}
const { groupPointsByDistance, mergedPointOf, groupItemCount, MERGE_RADIUS_METERS } = mod
void readFileSync

const res = await fetch(`${api}/map/points?campusId=2`)
const json = await res.json()
const points = json.data.points ?? []

console.log(`阈值 ${MERGE_RADIUS_METERS} 米｜原始点位 ${points.length} 个`)
const groups = groupPointsByDistance(points, MERGE_RADIUS_METERS)
console.log(`合并后 ${groups.length} 个气泡\n`)

let mergedCount = 0
for (const g of groups) {
  const merged = mergedPointOf(g)
  if (g.members.length > 1) mergedCount++
  const tag = g.members.length > 1 ? `  ⟵ 合并了 ${g.members.length} 个` : ''
  console.log(`· ${merged.name}  在售${groupItemCount(g)}${tag}`)
  if (g.members.length > 1) {
    for (const m of g.members) {
      console.log(`     - ${m.name}  (${m.lng}, ${m.lat})  在售${m.itemCount ?? 0}`)
    }
  }
}

console.log(`\n结果：${mergedCount} 个气泡是合并出来的，地图上少画 ${points.length - groups.length} 个标记`)

// 再验证一遍"详情仍精确"：每个组内成员的 id 都不同且未被改写
const allIds = groups.flatMap((g) => g.members.map((m) => m.id))
const unique = new Set(allIds)
console.log(`明细完整性：组内成员 id 共 ${allIds.length} 个，去重后 ${unique.size} 个（应相等）`)
process.exit(allIds.length === unique.size ? 0 : 1)
