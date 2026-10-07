// Synthetic, local-only regression. No requests reach Xianyu or any external service.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || '../../target/task-ui/node_modules/playwright')
const origin = 'http://127.0.0.1:5178'
const output = new URL('../../target/search-results-review/ui/', import.meta.url)
await fs.mkdir(output, { recursive: true })
const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  headless: true, args: ['--disable-background-networking'] })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
await context.addInitScript(() => {
  if (!localStorage.getItem('xianyu_auth_token')) {
    localStorage.setItem('xianyu_auth_token', 'synthetic-only')
    localStorage.setItem('xianyu_auth_username', 'synthetic')
  }
})
const fixture = (label = '参考商品') => [
  { itemId: label + '-a', title: label + ' A', sourceUrl: '', price: '12.70', images: [], opportunityScore: 90,
    riskLevel: 'LOW', matchReason: '合成测试数据', soldCountText: '已售1.2万+', wantCountText: '879人想要' },
  { itemId: label + '-b', title: label + ' B', sourceUrl: '', price: '9.99', images: [], opportunityScore: 80,
    riskLevel: 'LOW', matchReason: '合成测试数据', soldCountText: null, wantCountText: '0人想要' },
]
const calls = []
const errors = []
let gate, releaseGate, fail = false, expire = false, accessible = [2, 3]
const page = await context.newPage()
page.on('pageerror', error => errors.push(error.message))
await context.route('**/*', async route => {
  const url = new URL(route.request().url())
  if (url.origin !== origin) return route.abort()
  if (!url.pathname.startsWith('/api/')) return route.continue()
  let data = [], code = 200
  if (url.pathname === '/api/system/currentUser') data = { username: 'synthetic', role: 'ADMIN', permissions: [] }
  else if (url.pathname === '/api/account/list') data = { accounts: accessible.map(id => ({ id, accountNote: `账号 ${id}` })) }
  else if (url.pathname.startsWith('/api/merchant/opportunities/')) {
    const request = route.request().postDataJSON()
    calls.push({ path: url.pathname, request })
    if (/search|shop/.test(url.pathname)) {
      if (request.keyword === '迟到旧查询' && gate) await gate
      if (fail) code = 500
      if (expire) code = 401
      const label = url.pathname.includes('shop') ? '店铺商品' : request.xianyuAccountId === 3 ? '账号B商品' : request.keyword === '新的查询' ? '新的商品' : '参考商品'
      data = { items: request.pageNumber > 1 ? [{ ...fixture(label)[0], itemId: 'more', title: '分页商品' }] : fixture(label),
        pageNumber: request.pageNumber, total: 3, hasMore: request.pageNumber === 1 }
    } else if (url.pathname.endsWith('/detail')) data = { itemId: request.itemId, competitorSnapshot: {
      itemId: request.itemId, status: 'AVAILABLE', source: 'ORDER_RENDER_API', capturedAt: '2026-10-07T00:00:00Z',
      skus: [{ skuId: 'a', priceCents: 1270, quantity: null, priceStatus: 'KNOWN', properties: [{ name: '地区', value: '日本' }] }] } }
    else if (url.pathname.endsWith('/import')) data = [{ id: 1, data: { title: request.candidates[0].title, description: '整理内容', images: [] } }]
  }
  try { await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code, msg: code === 200 ? '' : '合成搜索失败', data }) }) }
  catch (error) { if (/closed|cancel|intercept/i.test(error.message)) return; throw error }
})
const ready = async (path, title) => {
  await page.goto(origin + path)
  await page.getByRole('heading', { name: title, exact: true }).waitFor()
  await page.locator('select').first().locator('option').first().waitFor({ state: 'attached' })
}
const sameCalls = async (count) => {
  await page.waitForTimeout(250)
  assert.equal(calls.length, count, 'Restoration must make no search/detail/import calls')
}
const pass = name => console.log('PASS ' + name)
const screenshot = async name => page.screenshot({ path: new URL(name, output).pathname.replace(/^\/(\w:)/, '$1'), fullPage: true })
const routeTo = async name => { await page.getByRole('link', { name, exact: true }).first().click(); await page.getByRole('heading', { name, exact: true }).waitFor() }
try {
  await ready('/price-comparison', '全站比价')
  await page.getByPlaceholder('输入商品关键词，例如：iPhone 15 256G').fill('基准')
  await page.getByRole('button', { name: '开始比价' }).click()
  await page.getByRole('heading', { name: '参考商品 A', exact: true }).waitFor()
  assert.equal(calls.length, 1)
  assert.match(await page.locator('.comparison__list').innerText(), /已售1.2万\+/)
  assert.match(await page.locator('.comparison__list').innerText(), /879人想要/)
  assert.match(await page.locator('.comparison__list').innerText(), /已售：未提供/)
  assert.match(await page.locator('.comparison__list').innerText(), /0人想要/)
  await page.getByPlaceholder('不限').nth(0).fill('9')
  await page.getByPlaceholder('不限').nth(1).fill('100')
  await page.locator('.comparison__filters select').selectOption('price-desc')
  await page.getByRole('button', { name: '查看规格', exact: true }).first().click()
  await page.getByText('地区：日本', { exact: true }).waitFor()
  const retainedTime = await page.locator('.comparison__result-meta span').innerText()
  await screenshot('comparison-desktop.png')
  pass('comparison shows exact labels, zero and missing values')
  let before = calls.length
  await routeTo('商机发掘')
  await routeTo('全站比价')
  await page.getByRole('heading', { name: '参考商品 A', exact: true }).waitFor()
  await page.getByText('地区：日本', { exact: true }).waitFor()
  await sameCalls(before)
  await page.reload()
  await page.getByRole('heading', { name: '参考商品 A', exact: true }).waitFor()
  await sameCalls(before)
  assert.equal(await page.getByPlaceholder('不限').nth(0).inputValue(), '9')
  assert.equal(await page.locator('.comparison__filters select').inputValue(), 'price-desc')
  assert.equal(await page.locator('.comparison__result-meta span').innerText(), retainedTime)
  pass('route return and refresh retain filters, query, time and SKU with zero platform requests')
  await page.locator('.comparison__search select').selectOption('3')
  assert.equal(await page.locator('.comparison__item').count(), 0)
  await page.getByPlaceholder('输入商品关键词，例如：iPhone 15 256G').fill('账号B查询')
  await page.getByRole('button', { name: '开始比价' }).click()
  await page.getByRole('heading', { name: '账号B商品 A', exact: true }).waitFor()
  before = calls.length
  await page.locator('.comparison__search select').selectOption('2')
  await page.getByRole('heading', { name: '参考商品 A', exact: true }).waitFor()
  await sameCalls(before)
  await page.locator('.comparison__search select').selectOption('3')
  await page.reload()
  await page.getByRole('heading', { name: '账号B商品 A', exact: true }).waitFor()
  await sameCalls(before)
  assert.equal(await page.locator('.comparison__search select').inputValue(), '3')
  pass('account caches stay isolated and refresh restores selected accessible account')
  fail = true
  await page.getByRole('button', { name: '开始比价' }).click()
  await page.getByText('合成搜索失败', { exact: true }).waitFor()
  assert.equal(await page.locator('.comparison__item').count(), 2)
  fail = false
  pass('failed search preserves last successful results')
  gate = new Promise(resolve => { releaseGate = resolve })
  await page.getByPlaceholder('输入商品关键词，例如：iPhone 15 256G').fill('迟到旧查询')
  await page.getByRole('button', { name: '开始比价' }).click()
  await page.waitForFunction(() => document.querySelector('.comparison__search button').disabled)
  await page.getByPlaceholder('输入商品关键词，例如：iPhone 15 256G').fill('新的查询')
  await page.locator('.comparison__search select').selectOption('2')
  await page.getByPlaceholder('输入商品关键词，例如：iPhone 15 256G').fill('新的查询')
  await page.getByRole('button', { name: '开始比价' }).click()
  await page.getByRole('heading', { name: '新的商品 A', exact: true }).waitFor()
  releaseGate(); gate = undefined
  await page.waitForTimeout(300)
  assert.equal(await page.getByRole('heading', { name: '新的商品 A', exact: true }).count(), 1)
  pass('late old-account and old-query responses cannot overwrite current results')
  gate = new Promise(resolve => { releaseGate = resolve })
  await page.getByPlaceholder('输入商品关键词，例如：iPhone 15 256G').fill('迟到旧查询')
  await page.getByRole('button', { name: '开始比价' }).click()
  await page.waitForFunction(() => document.querySelector('.comparison__search button').disabled)
  await page.getByPlaceholder('输入商品关键词，例如：iPhone 15 256G').fill('新的查询')
  await page.getByRole('button', { name: '开始比价' }).click()
  await page.getByRole('heading', { name: '新的商品 A', exact: true }).waitFor()
  releaseGate(); gate = undefined
  await page.waitForTimeout(300)
  assert.equal(await page.getByRole('heading', { name: '新的商品 A', exact: true }).count(), 1)
  pass('same-account old-query response cannot overwrite a newer query')
  before = calls.length
  await page.getByRole('button', { name: '清空搜索结果' }).click()
  await page.reload()
  await page.getByText('输入关键词后开始全站比价。', { exact: true }).waitFor()
  await sameCalls(before)
  pass('clear is local and remains empty after refresh')

  await ready('/opportunities', '商机发掘')
  await page.getByPlaceholder('输入商品关键词，例如：华为 Mate 80').fill('基准')
  await page.getByRole('button', { name: '开始搜索', exact: true }).click()
  await page.locator('.opportunity__result').first().waitFor()
  await page.getByRole('button', { name: '加载更多平台商品' }).click()
  await page.getByRole('heading', { name: '分页商品', exact: true }).waitFor()
  assert.equal(await page.locator('.opportunity__result').count(), 3)
  fail = true
  await page.getByRole('button', { name: '开始搜索', exact: true }).click()
  await page.getByText('合成搜索失败', { exact: true }).waitFor()
  assert.equal(await page.locator('.opportunity__result').count(), 3)
  fail = false
  pass('opportunity failed search preserves successful paging results')
  await page.locator('.opportunity__result').first().click()
  await page.getByRole('button', { name: '查看规格', exact: true }).click()
  await page.getByText('地区：日本', { exact: true }).waitFor()
  await page.getByRole('button', { name: '下一步：整理商品' }).click()
  await page.getByRole('heading', { name: '整理商品内容' }).waitFor()
  await page.locator('.opportunity__wizard input').fill('不应保存的整理草稿')
  before = calls.length
  await routeTo('全站比价')
  await routeTo('商机发掘')
  await page.getByRole('heading', { name: '分页商品', exact: true }).waitFor()
  await sameCalls(before)
  assert.equal(await page.locator('.opportunity__result input:checked').count(), 0)
  assert.equal(await page.getByRole('heading', { name: '整理商品内容' }).count(), 0)
  assert.equal(await page.locator('.opportunity__preview h2').count(), 0)
  assert.equal(await page.getByRole('button', { name: '2 改写' }).isDisabled(), true)
  await page.reload()
  await page.getByRole('heading', { name: '分页商品', exact: true }).waitFor()
  await sameCalls(before)
  await page.locator('.opportunity__result').first().click()
  await page.getByText('地区：日本', { exact: true }).waitFor()
  await sameCalls(before)
  pass('opportunity pagination survives return/refresh while selection, active item and wizard draft reset')
  await screenshot('opportunities-desktop.png')
  await page.locator('.opportunity__account').selectOption('3')
  await page.locator('.opportunity__mode').selectOption('shop')
  await page.getByPlaceholder('粘贴闲鱼网页版店铺主页完整链接').fill('https://www.goofish.com/personal?userId=123')
  await page.getByRole('button', { name: '开始搜索', exact: true }).click()
  await page.getByRole('heading', { name: '店铺商品 A', exact: true }).first().waitFor()
  before = calls.length
  await page.reload()
  await page.getByRole('heading', { name: '店铺商品 A', exact: true }).first().waitFor()
  await sameCalls(before)
  assert.equal(await page.locator('.opportunity__mode').inputValue(), 'shop')
  await page.locator('.opportunity__account').selectOption('2')
  await page.getByRole('heading', { name: '分页商品', exact: true }).waitFor()
  await sameCalls(before)
  pass('shop query and account-specific keyword pagination both restore correctly')
  for (const [path, title, name, selector] of [
    ['/opportunities', '商机发掘', 'opportunities-mobile.png', '.opportunity__result-meta'],
    ['/price-comparison', '全站比价', 'comparison-mobile.png', '.comparison__result-meta'],
  ]) {
    await page.setViewportSize({ width: 390, height: 844 })
    await ready(path, title)
    if (path === '/price-comparison') {
      await page.getByPlaceholder('输入商品关键词，例如：iPhone 15 256G').fill('基准')
      await page.getByRole('button', { name: '开始比价' }).click()
    }
    await page.locator(selector).waitFor()
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= 390))
    assert.match(await page.locator('.product-reference-stats').first().innerText(), /879人想要/)
    await screenshot(name)
  }
  pass('390px restored lists, metrics and clear/time controls fit without page overflow')
  accessible = [3]
  before = calls.length
  await ready('/opportunities', '商机发掘')
  await page.getByRole('heading', { name: '店铺商品 A', exact: true }).waitFor()
  await sameCalls(before)
  assert.equal(await page.locator('.opportunity__account').inputValue(), '3')
  pass('revoked account cache is pruned before restoration')
  expire = true
  await page.getByRole('button', { name: '开始搜索', exact: true }).click()
  await page.waitForURL(/\/(login|dashboard)$/)
  assert.equal(await page.evaluate(() => sessionStorage.getItem('xianyu_search_results_v1')), null)
  expire = false
  pass('expired login clears session cache through the actual response interceptor')
  await ready('/opportunities', '商机发掘')
  await page.getByPlaceholder('输入商品关键词，例如：华为 Mate 80').fill('基准')
  await page.getByRole('button', { name: '开始搜索', exact: true }).click()
  await page.locator('.opportunity__result').first().waitFor()
  await page.evaluate(async () => (await import('/src/utils/request.ts')).clearAuthToken())
  assert.equal(await page.evaluate(() => sessionStorage.getItem('xianyu_search_results_v1')), null)
  await page.evaluate(async () => (await import('/src/utils/request.ts')).setAuthToken('new-synthetic-token', 'another-user'))
  await ready('/opportunities', '商机发掘')
  assert.equal(await page.locator('.opportunity__result').count(), 0)
  pass('logout and system-user changes clear session cache')
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.locator('.opportunity__account option').first().waitFor({ state: 'attached' })
  // The quota fallback must survive client-side navigation in memory.
  await page.evaluate(() => {
    const original = Storage.prototype.setItem
    Storage.prototype.setItem = function(key, value) {
      if (this === sessionStorage) throw new DOMException('Synthetic quota', 'QuotaExceededError')
      return original.call(this, key, value)
    }
  })
  await page.getByPlaceholder('输入商品关键词，例如：华为 Mate 80').fill('基准')
  await page.getByRole('button', { name: '开始搜索', exact: true }).click()
  await page.locator('.opportunity__result').first().waitFor()
  await page.getByText('浏览器无法保存搜索结果，当前页面仍会保留；刷新后可能丢失。').waitFor()
  before = calls.length
  await routeTo('全站比价')
  await routeTo('商机发掘')
  await page.locator('.opportunity__result').first().waitFor()
  await sameCalls(before)
  pass('storage failure has visible guidance and memory survives navigation')
  assert.deepEqual(errors, [])
  pass('no browser runtime errors; all platform calls were synthetic')
} finally { releaseGate?.(); await browser.close() }
