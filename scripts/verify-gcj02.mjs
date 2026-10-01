// 验证坐标系工具（utils/coord.ts）
//
// 背景（这段是踩坑记录，别删）：
// 一开始我以为"国内 GCJ-02 与 WGS-84 相差 50~200 米"，并把这个区间写成了断言。
// 实测（浏览器里用高德自己的 `AMap.convertFrom` 做参照，见开发日志）发现——
// **桂林这一带高德的偏移就有 475~567 米**，我那条断言本身就是错的。
// 所以现在改成"验证公式的性质"（稳定、可逆、方向合理、量级合理），
// 并且生产路径**优先用高德转换**，本公式只作兜底。
//
// 用法： node --experimental-strip-types scripts/verify-gcj02.mjs
import { pathToFileURL, fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { readFileSync } from 'node:fs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const mod = await import(
  pathToFileURL(join(root, 'ershou-web/src/utils/coord.ts')).href
).catch((e) => {
  console.error('import 失败：' + e.message)
  console.error('请用： node --experimental-strip-types scripts/verify-gcj02.mjs')
  process.exit(2)
})
const { wgs84ToGcj02, gcj02ToWgs84, isOutOfChina } = mod

const R = 6371008.8
const toRad = (d) => (d * Math.PI) / 180
function haversine(lng1, lat1, lng2, lat2) {
  const dLat = toRad(lat2 - lat1)
  const dLng = toRad(lng2 - lng1)
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(a))
}

let pass = 0
let fail = 0
const check = (desc, cond, detail = '') => {
  if (cond) {
    pass++
    console.log('  [PASS] ' + desc)
  } else {
    fail++
    console.log('  [FAIL] ' + desc + '  ' + detail)
  }
}

const cases = [
  ['花江校区图书馆', 110.419531, 25.312869],
  ['中央食堂', 110.418160, 25.311820],
  ['桂电金鸡岭校区', 110.338263, 25.283121],
  ['桂林市中心', 110.290000, 25.273000],
  ['北京天安门', 116.391200, 39.906700],
]

console.log('=== 1. 偏移量级与方向（不再假定 50~200 米）===')
for (const [label, lng, lat] of cases) {
  const [glng, glat] = wgs84ToGcj02(lng, lat)
  const shift = haversine(lng, lat, glng, glat)
  console.log(
    `  ${label}: ${shift.toFixed(0)} 米  dLng=${(glng - lng).toFixed(6)} dLat=${(glat - lat).toFixed(6)}`,
  )
  check(`${label} 发生了偏移`, shift > 1, '没变')
  // 量级合理：几十米到一千米之间都可能是正常值（各地偏移不同）
  check(`${label} 偏移量级合理（1~2000 米）`, shift > 1 && shift < 2000, `实际 ${shift.toFixed(0)} 米`)
}
// 已知参照：高德 convertFrom 在桂林给出 475~567 米，公式结果应落在同一量级
const [, , glng0, glat0] = ['', '', 0, 0]
void glng0
void glat0
const [t1lng, t1lat] = wgs84ToGcj02(110.419531, 25.312869)
const t1Shift = haversine(110.419531, 25.312869, t1lng, t1lat)
check(
  `桂林样本量级与高德参照（475~567 米）同档：${t1Shift.toFixed(0)} 米`,
  t1Shift > 300 && t1Shift < 700,
  `实际 ${t1Shift.toFixed(0)} 米`,
)

console.log('\n=== 2. 幂等性 ===')
const [a1, b1] = wgs84ToGcj02(110.419531, 25.312869)
const [a2, b2] = wgs84ToGcj02(110.419531, 25.312869)
check('同样输入得到同样输出', a1 === a2 && b1 === b2, `${a1},${b1} vs ${a2},${b2}`)

console.log('\n=== 3. 境外坐标原样返回 ===')
const [tlng, tlat] = wgs84ToGcj02(139.767, 35.681) // 东京
check('东京坐标不被偏移', tlng === 139.767 && tlat === 35.681, `${tlng},${tlat}`)
check(
  'isOutOfChina 判定正确',
  isOutOfChina(139.767, 35.681) && !isOutOfChina(110.4195, 25.3129),
  '',
)

console.log('\n=== 4. 反算可逆（往返误差 < 1 米）===')
for (const [label, lng, lat] of cases) {
  const [gLng, gLat] = wgs84ToGcj02(lng, lat)
  const [bLng, bLat] = gcj02ToWgs84(gLng, gLat)
  const err = haversine(lng, lat, bLng, bLat)
  check(`${label} 往返误差 < 1 米`, err < 1, `实际 ${err.toFixed(3)} 米`)
}

console.log('\n=== 5. 公式只是兜底：生产路径要用高德转换 ===')
const amapSrc = readFileSync(join(root, 'ershou-web/src/utils/amap.ts'), 'utf8')
check(
  'amap.ts 里存在 gpsToGcj02（优先 AMap.convertFrom）',
  amapSrc.includes('export async function gpsToGcj02') && amapSrc.includes('convertFrom'),
  '未找到',
)
check(
  'gpsToGcj02 在失败时会退回本地公式',
  /gpsToGcj02[\s\S]{0,900}?wgs84ToGcj02Fallback/.test(amapSrc),
  '未找到兜底调用',
)
const pickerSrc = readFileSync(join(root, 'ershou-web/src/components/PointPickerDialog.vue'), 'utf8')
check(
  '选点弹窗用的是 gpsToGcj02（不是纯公式版本）',
  pickerSrc.includes('gpsToGcj02') && pickerSrc.includes('browserRawGeolocation'),
  '未使用高德转换',
)

console.log('')
if (fail === 0) console.log('全部通过：' + pass + ' 项断言')
else console.log('通过 ' + pass + ' 项，失败 ' + fail + ' 项')
process.exit(fail)
