/** 默认学校 / 校区（学生端暂未做学校选择页，登录时使用默认值）
 *  campus 2 = 桂林电子科技大学 花江校区（主校区）；1=金鸡岭校区 3=六合路校区，见 sql/migration-guet.sql */
export const DEFAULT_SCHOOL_ID = 1
export const DEFAULT_CAMPUS_ID = 2

/** 开发环境固定验证码（后端在 dev profile 下固定下发/接受 123456） */
export const DEV_SMS_CODE = '123456'

export interface SelectOption<T = number> {
  label: string
  value: T
}

/** 成色选项，与后端 conditionLevel 约定一致 */
export const CONDITION_OPTIONS: SelectOption<number>[] = [
  { label: '全新', value: 1 },
  { label: '几乎全新', value: 2 },
  { label: '轻微使用痕迹', value: 3 },
  { label: '明显使用痕迹', value: 4 },
  { label: '较重使用痕迹', value: 5 },
]

/** 面交时间窗默认时长（小时） */
export const DEFAULT_MEETUP_HOURS = 2
