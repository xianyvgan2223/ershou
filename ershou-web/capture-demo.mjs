import puppeteer from 'puppeteer-core';
import fs from 'fs';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const BASE = 'http://127.0.0.1:5173';
const API = 'http://127.0.0.1:8080';
const OUT = 'E:\\ershou\\.build\\demo\\web';
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function login(phone) {
  await fetch(API + '/api/v1/auth/sms-code', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone }),
  });
  const r = await fetch(API + '/api/v1/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone, code: '123456' }),
  });
  const j = await r.json();
  if (j.code !== 0) throw new Error('login failed: ' + JSON.stringify(j));
  return j.data.token;
}

const token = await login('13800000009');
console.log('token ok', token.slice(0, 8) + '...');

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-gpu', '--lang=zh-CN', '--window-size=1440,900'],
});
const page = await browser.newPage();
await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 });
await page.setExtraHTTPHeaders({ 'Accept-Language': 'zh-CN,zh;q=0.9' });

// 登录页（未登录状态）
await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 40000 }).catch(() => {});
await sleep(1500);
await page.screenshot({ path: OUT + '\\00-登录页.png' });
console.log('00-登录页');

// 注入 token
await page.evaluate((t) => localStorage.setItem('ershou_token', t), token);
await page.goto(BASE + '/', { waitUntil: 'networkidle2', timeout: 40000 }).catch(() => {});
await sleep(2000);

const shots = [
  ['/', '01-首页', 2000],
  ['/platform', '02-平台严选', 2500],
  ['/map', '03-校内地图', 4000],
  ['/item/24', '04-商品详情', 2500],
  ['/publish', '05-发布闲置', 2000],
  ['/publish-list', '06-我的发布', 2000],
  ['/chat', '07-私聊消息', 2000],
  ['/cart', '08-购物车', 2000],
  ['/orders', '09-我的面交', 2500],
  ['/favorites', '10-我的收藏', 2000],
  ['/profile', '11-个人主页', 2000],
  ['/blocked', '12-黑名单', 1800],
  ['/user/6', '13-用户公开主页', 2000],
  ['/admin', '14-管理后台-运营概览', 2500],
  ['/admin/audit', '15-管理后台-上架审核', 2500],
  ['/admin/review', '16-管理后台-审核工作台', 2500],
  ['/admin/items', '17-管理后台-商品管理', 2500],
  ['/admin/orders', '18-管理后台-面交单管理', 2500],
  ['/admin/users', '19-管理后台-用户管理', 2500],
  ['/admin/logs', '20-管理后台-操作日志', 2500],
  ['/admin/platform-points', '21-管理后台-平台代卖点', 2500],
  ['/console/platform', '22-平台运营台-概览', 3000],
  ['/console/platform/items', '23-平台运营台-商品', 2500],
  ['/console/platform/points', '24-平台运营台-代卖点', 2500],
  ['/console/platform/coupons', '25-平台运营台-优惠券', 2500],
  ['/console/platform/flash-sales', '26-平台运营台-秒杀', 2500],
  ['/console/platform/orders', '27-平台运营台-平台订单', 2500],
  ['/console/platform/cache', '28-平台运营台-缓存', 2500],
];
for (const [path, name, wait] of shots) {
  await page.goto(BASE + path, { waitUntil: 'networkidle2', timeout: 40000 }).catch((e) => console.log('goto fail', path, e.message));
  await sleep(wait);
  await page.screenshot({ path: OUT + '\\' + name + '.png' });
  const url = page.url();
  console.log(name, '->', url);
}

await browser.close();
console.log('DONE');
