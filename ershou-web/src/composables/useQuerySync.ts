import { watch } from 'vue'
import type { Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQueryRaw } from 'vue-router'

/** 能放进 URL 的筛选值：字符串（关键词）或数字（状态 / 页码） */
type Scalar = string | number

export interface QuerySyncOptions<K extends string> {
  /** 外部改动 URL 导致筛选值真的变了时触发（组件用它重新请求） */
  onChange: () => void
  /** 字段名 -> URL 参数名。页面里有多个模块的筛选时（审核工作台同时有商品和点位），靠它缩短链接 */
  aliases?: Partial<Record<K, string>>
  /**
   * 需要按数字还原的字段。
   *
   * 默认按 `defaults` 的类型推断，但 `number | ''`（下拉「不筛」时用空串）这种联合类型的默认值是字符串，
   * 推断不出来 —— 不显式声明的话，从 URL 还原会得到字符串 `"2"`，
   * Element Plus 的 el-select 匹配不到数字 value 的选项，就会把原始值 `2` 直接显示出来。
   */
  numeric?: K[]
}

/**
 * 筛选条件 <-> 路由 query 双向同步。
 *
 * 为什么需要：运营经常要把「已经筛好的结果」发给同事，或者中途刷新页面。
 * 以前筛选只活在组件内存里，刷新、后退、分享链接一律丢状态，
 * 概览页卡片跳过来带的 `?status=2` 也只有首次进页面那一下生效（再点别的卡片没反应）。
 *
 * 规则：
 * - 进页面时以 URL 为准；URL 没有的字段用 `defaults`
 * - 内存变化写回 URL，用 `replace` 不新增历史记录，避免每敲一个字就多一条后退记录
 * - 等于默认值的字段从 URL 里删掉，链接保持干净
 * - URL 被外部改动（概览卡片、浏览器前进后退）时回灌内存，并回调 `onChange` 重新拉数据
 *
 * @param fields   筛选字段的 ref 集合
 * @param defaults 各字段的默认值，同时用来推断类型
 */
export function useQuerySync<F extends Record<string, Scalar>>(
  fields: { [K in keyof F]: Ref<F[K]> },
  defaults: F,
  options: QuerySyncOptions<keyof F & string>,
): void {
  const route = useRoute()
  const router = useRouter()
  const keys = Object.keys(defaults) as (keyof F & string)[]
  const aliases: Partial<Record<keyof F & string, string>> = options.aliases ?? {}
  const numericKeys = new Set<string>(options.numeric ?? [])
  const paramOf = (key: keyof F & string): string => aliases[key] ?? key

  const isNumeric = (key: keyof F & string): boolean =>
    numericKeys.has(key) || typeof defaults[key] === 'number'

  /** URL 里的值都是字符串，按字段类型还原；解析不出来就退回默认值 */
  function decode(key: keyof F & string, raw: string): Scalar {
    const fallback = defaults[key]
    if (isNumeric(key)) {
      const num = Number(raw)
      return Number.isFinite(num) ? num : fallback
    }
    return raw
  }

  /** URL -> 内存，返回是否真的发生了改变 */
  function applyFromUrl(): boolean {
    let changed = false
    for (const key of keys) {
      const raw = route.query[paramOf(key)]
      const next = typeof raw === 'string' && raw !== '' ? decode(key, raw) : defaults[key]
      const target = fields[key] as Ref<Scalar>
      if (target.value !== next) {
        target.value = next
        changed = true
      }
    }
    return changed
  }

  /** 内存 -> URL；顺带保留不属于本页的 query（例如审核工作台的 ?tab=point） */
  function writeToUrl(): void {
    const merged = { ...route.query } as LocationQueryRaw
    for (const key of keys) {
      const param = paramOf(key)
      const value = (fields[key] as Ref<Scalar>).value
      if (value === defaults[key] || value === '') {
        delete merged[param]
      } else {
        merged[param] = String(value)
      }
    }
    void router.replace({ query: merged })
  }

  applyFromUrl()

  for (const key of keys) {
    watch(fields[key] as Ref<Scalar>, writeToUrl)
  }

  watch(
    () => route.query,
    () => {
      if (applyFromUrl()) options.onChange()
    },
  )
}
