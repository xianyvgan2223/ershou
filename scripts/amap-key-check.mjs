/**
 * 高德 JS API Key 自检（真实浏览器渲染）
 *
 * 用法： node scripts/amap-key-check.mjs
 *
 * 为什么必须在浏览器里测：
 *   JS API Key **无法**用 REST 接口验证。像 restapi.amap.com/v3/security/check 这类接口
 *   是给「Web服务」Key 做数字签名用的，拿 JS API Key 去打只会得到
 *   USERKEY_PLAT_NOMATCH —— 那是"这把 key 不属于我这个平台"的意思，
 *   是**正常反证**，不代表 key 有问题（本项目踩过这个坑）。
 *   唯一可靠的验证方式：把 loader.js 拉起来真的渲染一张地图，看瓦片出不出来。
 *
 * 依赖：本机 Chrome 或 Edge（headless 模式）。找不到浏览器时脚本会明确提示。
 * 前置：vite dev server 必须已启动（npm run dev，默认 http://localhost:5173）
 */
import { readFileSync } from 'node:fs'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const DEV_SERVER = process.env.AMAP_CHECK_ORIGIN || 'http://localhost:5173'
const SHOT_DIR = join(root, '.build')

/** 取值优先级与 amap.ts 保持一致：VITE_ 前缀优先 */
function readAmapEnv() {
  const env = {}
  for (const line of readFileSync(join(root, '.env'), 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const i = trimmed.indexOf('=')
    if (i > 0) env[trimmed.slice(0, i).trim()] = trimmed.slice(i + 1).trim()
  }
  return {
    key: env.VITE_AMAP_JS_KEY || env.AMAP_JS_KEY || '',
    scode: env.VITE_AMAP_JS_SECURITY_CODE || env.AMAP_JS_SECURITY_CODE || '',
  }
}

function findBrowser() {
  const candidates = [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  ]
  for (const path of candidates) {
    try {
      readFileSync(path)
      return path
    } catch {
      /* 继续找下一个 */
    }
  }
  return null
}

function run(bin, args) {
  return new Promise((resolve) => {
    const child = spawn(bin, args, { stdio: 'inherit' })
    child.on('exit', (code) => resolve(code ?? 0))
    child.on('error', () => resolve(-1))
  })
}

const mask = (v) => (!v ? '(空)' : `${v.slice(0, 6)}…${v.slice(-4)} (len=${v.length})`)

const { key, scode } = readAmapEnv()
console.log('高德 JS API Key 自检')
console.log('  VITE_AMAP_JS_KEY           =', mask(key))
console.log('  VITE_AMAP_JS_SECURITY_CODE =', mask(scode))

if (!key || !scode) {
  console.error('\n✗ .env 里缺 Key 或安全密钥，先补齐再跑')
  process.exit(1)
}

try {
  const res = await fetch(DEV_SERVER, { signal: AbortSignal.timeout(5000) })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
} catch (error) {
  console.error(`\n✗ 连不上 dev server ${DEV_SERVER}（${error.message}）`)
  console.error('  先在 ershou-web 目录执行 npm run dev，或设置 AMAP_CHECK_ORIGIN 指向已启动的地址')
  process.exit(1)
}

const browser = findBrowser()
if (!browser) {
  console.error('\n✗ 没找到 Chrome / Edge，无法做浏览器验证')
  console.error('  可手动打开地图页观察：' + DEV_SERVER + '/map')
  process.exit(1)
}

const html = `<!doctype html>
<html lang="zh-CN"><head><meta charset="UTF-8"><title>amap key check</title>
<style>#map{width:100vw;height:60vh}#log{padding:12px 16px;font:14px/1.8 system-ui;white-space:pre-wrap}</style>
</head><body>
<div id="map"></div><div id="log">加载中…</div>
<script>
var KEY=${JSON.stringify(key)}, SCODE=${JSON.stringify(scode)}, log=document.getElementById('log'), errors=[];
function finish(ok,msg){log.textContent='AMAP_RESULT_'+(ok?'OK':'FAIL')+': '+msg+'\\n捕获到的错误：'+(errors.length?'\\n- '+errors.join('\\n- '):'（无）')}
window.onerror=function(m,s){errors.push('[onerror] '+m+' @ '+String(s||'').slice(0,80))};
window.addEventListener('unhandledrejection',function(e){errors.push('[reject] '+(e.reason&&e.reason.message?e.reason.message:String(e.reason)))});
window._AMapSecurityConfig={securityJsCode:SCODE};
var s=document.createElement('script');s.src='https://webapi.amap.com/loader.js';
s.onerror=function(){finish(false,'loader.js 加载失败（网络问题）')};
s.onload=function(){
  if(!window.AMapLoader){finish(false,'loader.js 未挂载 AMapLoader');return}
  window.AMapLoader.load({key:KEY,version:'2.0',plugins:['AMap.Scale']}).then(function(AMap){
    var map=new AMap.Map('map',{zoom:15,center:[116.397428,39.90923],resizeEnable:true});
    var report=function(prefix){
      var n=document.querySelectorAll('.amap-tile, .amap-layer img, canvas').length;
      var box=document.querySelector('.amap-maps');
      finish(n>0, prefix+'：瓦片/画布节点='+n+'，地图容器='+(box?'已创建':'未创建'));
    };
    map.on('complete',function(){report('地图渲染完成')});
    setTimeout(function(){if(!/AMAP_RESULT_/.test(log.textContent))report('超时 10s')},10000);
  }).catch(function(err){finish(false,'AMapLoader.load 失败：'+(err&&err.message?err.message:String(err)))});
};
document.head.appendChild(s);
</script></body></html>`

// 由 dev server 提供这个临时页面（vite 能直接给中间件返回任意 html）
const { createServer } = await import('node:http')
const server = createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
  res.end(html)
})
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
const checkUrl = `http://127.0.0.1:${server.address().port}/`
console.log(`\n用 ${browser.includes('msedge') ? 'Edge' : 'Chrome'} 无头渲染自检页：${checkUrl}`)

const shot = join(SHOT_DIR, 'amap-key-check.png')
await run(browser, [
  '--headless=new',
  '--disable-gpu',
  '--no-first-run',
  '--no-default-browser-check',
  `--user-data-dir=${join(SHOT_DIR, 'chrome-amap-key-check')}`,
  '--window-size=1280,860',
  '--virtual-time-budget=20000',
  `--screenshot=${shot}`,
  checkUrl,
])

server.close()
console.log(`\n截图：${shot}`)
console.log('请打开截图确认：出现真实街道瓦片 = Key 可用；只有灰底或报错文字 = Key 有问题。')
console.log('（命令行无法读取渲染结果，所以用截图做最终判据）')
