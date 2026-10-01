/**
 * 高德地点搜索的本地缓存。
 *
 * 为什么要缓存：个人认证开发者的「基础搜索服务」只有 **5000 次/月**（地图初始化是 150 万次），
 * 而"相思湖""图书馆"这类名字会被反复搜。搜过一次就把名称+坐标记下来，
 * 以后直接命中本地，**不再消耗配额**。
 *
 * 存哪儿：localStorage（跨会话有效，刷新/重开浏览器都还在）。
 * 失效策略：30 天 TTL + 按校区隔离 + 最多 200 条 LRU 淘汰。
 *   —— 校园里的地名几乎不会变，30 天足够；即使略旧，也比再花一次配额强。
 */

import type { PlaceHit } from './amap'

const CACHE_KEY = 'ershou:place-search:v1'
/** 缓存有效期（天）：校园 POI 变化很慢 */
const TTL_DAYS = 30
/** 最多存多少条，超出按最后使用时间淘汰最旧的 */
const MAX_ENTRIES = 200

interface CacheEntry {
  /** 缓存键：`{campusId}::{keyword}`，同一名字在不同校区结果不同，必须隔离 */
  key: string
  hits: PlaceHit[]
  /** 写入时间（ms） */
  at: number
  /** 最后一次命中时间（ms），用于 LRU 淘汰 */
  usedAt: number
}

type CacheMap = Record<string, CacheEntry>

function loadAll(): CacheMap {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as CacheMap
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    // 解析失败（手改坏了/配额满）就当空缓存，别让功能挂掉
    return {}
  }
}

function saveAll(map: CacheMap): void {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(map))
  } catch {
    // localStorage 满或被禁用：缓存是锦上添花，失败不影响主流程
  }
}

function entryKey(campusId: number, keyword: string): string {
  return `${campusId}::${keyword.trim().toLowerCase()}`
}

function isExpired(entry: CacheEntry, now: number): boolean {
  return now - entry.at > TTL_DAYS * 24 * 60 * 60 * 1000
}

/** 按最后使用时间淘汰，保留 MAX_ENTRIES 条 */
function prune(map: CacheMap): CacheMap {
  const entries = Object.values(map)
  if (entries.length <= MAX_ENTRIES) return map
  const keep = entries.sort((a, b) => b.usedAt - a.usedAt).slice(0, MAX_ENTRIES)
  const next: CacheMap = {}
  keep.forEach((entry) => {
    next[entry.key] = entry
  })
  return next
}

/** 读缓存：命中就返回（并更新 usedAt），没有或过期返回 null */
export function readPlaceCache(campusId: number, keyword: string): PlaceHit[] | null {
  const key = entryKey(campusId, keyword)
  const map = loadAll()
  const entry = map[key]
  if (!entry) return null
  const now = Date.now()
  if (isExpired(entry, now)) {
    delete map[key]
    saveAll(map)
    return null
  }
  entry.usedAt = now
  saveAll(map)
  return entry.hits
}

/** 写缓存：只缓存**有结果**的搜索；空结果不缓存，避免"这个名字以后都搜不到" */
export function writePlaceCache(campusId: number, keyword: string, hits: PlaceHit[]): void {
  if (!hits.length) return
  const key = entryKey(campusId, keyword)
  const map = loadAll()
  const now = Date.now()
  map[key] = { key, hits, at: now, usedAt: now }
  saveAll(prune(map))
}

/** 缓存里的条目数（界面上提示用） */
export function placeCacheSize(): number {
  const now = Date.now()
  return Object.values(loadAll()).filter((entry) => !isExpired(entry, now)).length
}

/** 清空缓存（预留：设置页/调试用） */
export function clearPlaceCache(): void {
  try {
    localStorage.removeItem(CACHE_KEY)
  } catch {
    /* 忽略 */
  }
}
