// Local synthetic API acceptance only. Never connects to a real marketplace or NAS.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || '../../target/task-ui/node_modules/playwright')
const origin = process.env.SKU_TEST_ORIGIN || 'http://127.0.0.1:5178'
assert.equal(new URL(origin).hostname, '127.0.0.1', 'Only a local test server is allowed')
const output = new URL('../../target/competitor-ui/', import.meta.url)
await fs.mkdir(output, { recursive: true })
const browser = await chromium.launch({
  executablePath: process.env.SKU_TEST_BROWSER || 'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  headless: true, args: ['--disable-background-networking'],
})
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
await context.addInitScript(() => localStorage.setItem('xianyu_auth_token', 'synthetic-local-test'))
const items = [
  { itemId: '1085721375729', title: '合成样本 A', price: '12.70', opportunityScore: 90, riskLevel: 'LOW', matchReason: '测试数据', images: [] },
  { itemId: '698321371327', title: '合成样本 B', price: '10.99', opportunityScore: 80, riskLevel: 'LOW', matchReason: '测试数据', images: [] },
]
const snapshot = (itemId, marker = '日本') => ({
  itemId, status: 'PARTIAL', source: 'ORDER_RENDER_API', capturedAt: '2026-10-01T00:00:00Z',
  message: '合成资料；真实平台验收尚未进行', skus: [
    { skuId: '999999999999999999999', priceCents: '1270', priceStatus: 'KNOWN', quantity: null,
      properties: [{ name: '地区', value: marker }, { name: '面值', value: '1000' }] },
    { skuId: '2', priceCents: null, rawPrice: '12.7', priceStatus: 'UNIT_UNCONFIRMED', quantity: 2,
      properties: [{ name: '地区', value: '美国' }, { name: '面值', value: '2000' }] },
  ],
})
const supply = { id: 8, resourceType: 'SUPPLY', name: '保存的合成货源', xianyuAccountId: 2,
  xyGoodsId: items[0].itemId, amount: 12.70, stock: 0, status: 1,
  data: { title: items[0].title, competitorSnapshot: snapshot(items[0].itemId), priceSource: 'SEARCH_DISPLAY' } }
let detailRequests = [], gate, block = false, blockReason = 'USER_VALIDATE'
let releaseGate
const errors = []
const page = await context.newPage()
page.on('pageerror', error => errors.push(error.message))
await context.route('**/*', async route => {
  const url = new URL(route.request().url())
  if (url.origin !== origin) return route.abort()
  if (!url.pathname.startsWith('/api/')) return route.continue()
  let data = []
  if (url.pathname === '/api/system/currentUser') data = { id: 1, username: 'synthetic', role: 'ADMIN', permissions: [] }
  else if (url.pathname === '/api/account/list') data = { accounts: [{ id: 2, accountNote: '测试账号 A' }, { id: 3, accountNote: '测试账号 B' }] }
  else if (url.pathname === '/api/merchant/opportunities/search') data = { items, pageNumber: 1, pageSize: 30, total: 2, hasMore: false }
  else if (url.pathname === '/api/merchant/opportunities/detail') {
    const request = route.request().postDataJSON()
    detailRequests.push(request)
    if (gate) { const pending = gate; await pending }
    data = { itemId: request.itemId, competitorSnapshot: block
      ? { itemId: request.itemId, status: 'BLOCKED', reason: blockReason, message: blockReason === 'USER_VALIDATE' ? '平台要求账号验证' : '平台拒绝了商品详情访问（RGV587）', skus: [] }
      : snapshot(request.itemId, request.itemId === items[0].itemId ? '日本' : '切换后 B') }
  }
  else if (url.pathname === '/api/merchant/opportunities/import') data = [{ ...supply, data: { ...supply.data, description: '合成商品说明', images: [] } }]
  else if (url.pathname === '/api/merchant/resources') data = url.searchParams.get('type') === 'SUPPLY' ? [supply] : []
  else if (url.pathname === '/api/merchant/overview') data = { resourceCounts: { SUPPLY: 1 }, taskCount: 0, failedTaskCount: 0 }
  try { await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) }) }
  catch (error) { if (!route.request().isNavigationRequest() && /closed|cancel|intercept/i.test(error.message)) return; throw error }
})
const ready = async (path, title) => { await page.goto(origin + path); await page.getByRole('heading', { name: title, exact: true }).waitFor() }
const check = (name) => console.log('PASS ' + name)
try {
  await ready('/price-comparison', '全站比价')
  await page.getByPlaceholder('输入商品关键词，例如：iPhone 15 256G').fill('合成')
  await page.getByRole('button', { name: '开始比价', exact: true }).click()
  await page.getByRole('heading', { name: '合成样本 A', exact: true }).waitFor()
  assert.equal(detailRequests.length, 0)
  assert.match(await page.locator('.comparison__metrics').innerText(), /11\.85/)
  check('search remains on demand and median has two decimals')
  await page.getByRole('button', { name: '查看规格', exact: true }).first().click()
  await page.getByText('地区：日本 / 面值：1000', { exact: true }).waitFor()
  assert.match(await page.locator('.competitor-sku').first().innerText(), /单位待确认/)
  assert.match(await page.locator('.competitor-sku').first().innerText(), /¥ 12\.70/)
  assert.match(await page.locator('.competitor-sku').first().innerText(), /来源：闲鱼下单确认页/)
  await page.screenshot({ path: new URL('comparison-desktop.png', output).pathname.replace(/^\/(\w:)/, '$1'), fullPage: true })
  block = true
  await page.getByRole('button', { name: '刷新规格', exact: true }).click()
  await page.getByText('本次更新失败，下方保留上次成功资料，不代表当前售价。').waitFor()
  assert.equal(detailRequests.at(-1).forceRefresh, true)
  assert.match(await page.locator('.competitor-sku').first().innerText(), /地区：日本/)
  check('manual refresh respects endpoint and keeps previous snapshot on validation failure')
  await page.getByRole('link', { name: '前往连接管理' }).waitFor()
  assert.match(await page.locator('.competitor-sku').first().innerText(), /等待人工验证/)
  blockReason = 'RGV587'
  await page.getByRole('button', { name: '刷新规格', exact: true }).click()
  await page.getByText('平台访问受限', { exact: true }).waitFor()
  assert.doesNotMatch(await page.locator('.competitor-sku').first().innerText(), /等待人工验证/)
  assert.match(await page.locator('.competitor-sku').first().innerText(), /地区：日本/)
  assert.match(await page.getByRole('link', { name: '打开闲鱼商品页' }).getAttribute('href'), /1085721375729/)
  const blockedCount = detailRequests.length
  await page.waitForTimeout(3200)
  assert.equal(detailRequests.length, blockedCount)
  check('RGV587 has accurate guidance and no automatic retry while explicit verification remains distinct')
  await page.screenshot({ path: new URL('rgv-guidance-desktop.png', output).pathname.replace(/^\/(\w:)/, '$1'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await ready('/price-comparison', '全站比价')
  await page.getByPlaceholder('输入商品关键词，例如：iPhone 15 256G').fill('合成')
  await page.getByRole('button', { name: '开始比价', exact: true }).click()
  await page.getByRole('button', { name: '查看规格', exact: true }).first().click()
  await page.getByText('平台访问受限', { exact: true }).waitFor()
  assert.ok(await page.locator('.competitor-sku').first().evaluate(element => element.getBoundingClientRect().right <= 390))
  await page.getByRole('link', { name: '前往连接管理' }).waitFor()
  await page.screenshot({ path: new URL('rgv-guidance-mobile.png', output).pathname.replace(/^\/(\w:)/, '$1'), fullPage: true })
  check('390px mobile restriction guidance and links stay within the page')
  await page.setViewportSize({ width: 1440, height: 1000 })
  block = false

  await ready('/opportunities', '商机发掘')
  await page.getByPlaceholder('输入商品关键词，例如：华为 Mate 80').fill('合成')
  await page.getByRole('button', { name: '开始搜索', exact: true }).click()
  await page.getByRole('heading', { name: '合成样本 A', exact: true }).first().waitFor()
  gate = new Promise(resolve => { releaseGate = resolve })
  await page.getByRole('button', { name: '查看规格', exact: true }).click()
  await page.locator('.opportunity__result').nth(1).click()
  releaseGate(); gate = undefined
  await page.getByRole('button', { name: '查看规格', exact: true }).waitFor()
  assert.doesNotMatch(await page.locator('.competitor-sku').innerText(), /地区：日本/)
  await page.getByRole('button', { name: '查看规格', exact: true }).click()
  await page.getByText('地区：切换后 B / 面值：1000', { exact: true }).waitFor()
  check('late detail response cannot appear in a newly selected item')
  await page.getByRole('button', { name: '下一步：整理商品', exact: true }).click()
  await page.getByRole('button', { name: '下一步', exact: true }).click()
  assert.equal(await page.locator('input[type=number]').nth(0).inputValue(), '')
  assert.equal(await page.locator('input[type=number]').nth(1).inputValue(), '')
  check('own publishing amount and stock are blank after import')

  await ready('/supplies', '货源库')
  const before = detailRequests.length
  await page.getByRole('button', { name: '查看规格', exact: true }).click()
  await page.getByText('地区：日本 / 面值：1000', { exact: true }).waitFor()
  assert.equal(detailRequests.length, before)
  check('saved supply SKU snapshot opens without external detail request')
  await page.setViewportSize({ width: 390, height: 844 })
  // The application changes its layout component at the mobile breakpoint.
  await ready('/supplies', '货源库')
  await page.getByRole('button', { name: '查看规格', exact: true }).click()
  await page.getByText('地区：日本 / 面值：1000', { exact: true }).waitFor()
  const layout = await page.locator('.competitor-sku__scroll').evaluate(element => ({
    width: element.clientWidth, scroll: element.scrollWidth, right: element.getBoundingClientRect().right,
  }))
  await page.screenshot({ path: new URL('supply-mobile.png', output).pathname.replace(/^\/(\w:)/, '$1'), fullPage: true })
  assert.ok(layout.scroll > layout.width)
  assert.ok(layout.right <= 390)
  check('390px mobile SKU table scrolls within the dialog')
  await page.getByRole('button', { name: '关闭规格', exact: true }).click()

  await page.setViewportSize({ width: 1440, height: 1000 })
  await ready('/price-comparison', '全站比价')
  await page.getByPlaceholder('输入商品关键词，例如：iPhone 15 256G').fill('合成')
  await page.getByRole('button', { name: '开始比价', exact: true }).click()
  await page.getByRole('button', { name: '查看规格', exact: true }).first().waitFor()
  gate = new Promise(resolve => { releaseGate = resolve })
  await page.getByRole('button', { name: '查看规格', exact: true }).first().click()
  await page.locator('.comparison__search select').selectOption('3')
  releaseGate(); gate = undefined
  await page.waitForFunction(() => document.querySelectorAll('.comparison__item').length === 0)
  assert.equal(await page.locator('.competitor-sku').count(), 0)
  check('account switch clears pending old-account results')
  assert.deepEqual(errors, [])
  console.log('PASS no browser runtime errors; all requests were synthetic')
} finally { releaseGate?.(); await browser.close() }
