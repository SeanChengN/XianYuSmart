// Synthetic, local-only regression. No requests reach Xianyu or any external service.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || '../../target/task-ui/node_modules/playwright')
const origin = 'http://127.0.0.1:5178'
const output = new URL('../../target/direct-supply-review/ui/', import.meta.url)
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
    riskLevel: 'LOW', matchReason: '合成测试数据', soldCountText: '已售1.2万+', wantCountText: '500人想要' },
  { itemId: label + '-b', title: label + ' B', sourceUrl: '', price: '9.99', images: [], opportunityScore: 80,
    riskLevel: 'LOW', matchReason: '合成测试数据', soldCountText: null, wantCountText: '0人想要' },
]
const calls = []
const errors = []
let gate, releaseGate, fail = false, expire = false, accessible = [2, 3], supplyFail = false, supplyGate, releaseSupply
const page = await context.newPage()
page.on('pageerror', error => errors.push(error.message))
await context.route('**/*', async route => {
  const url = new URL(route.request().url())
  if (url.origin !== origin) return route.abort()
  if (!url.pathname.startsWith('/api/')) return route.continue()
  let data = [], code = 200
  if (url.pathname === '/api/system/currentUser') data = { username: 'synthetic', role: 'ADMIN', permissions: [] }
  else if (url.pathname === '/api/account/list') data = { accounts: accessible.map(id => ({ id, accountNote: `账号 ${id}` })) }
  else if (url.pathname === '/api/merchant/resources') data = [{ id: 42, resourceType: 'SUPPLY', name: '直接入库无库存', stock: 0,
    amount: '12.70', xyGoodsId: '1085721375729', xianyuAccountId: 2, data: { detailStatus: 'SEARCH_ONLY', priceSource: 'SEARCH_DISPLAY' } },
    { id: 43, resourceType: 'SUPPLY', name: '明确填写零库存', stock: 0, amount: '12.70', data: { stock: 0 } }]
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
    else if (url.pathname.endsWith('/supply')) {
      if (supplyGate) await supplyGate
      if (supplyFail) code = 500
      data = { addedCount: request.candidates.length - 1, existingCount: 1, items: [] }
    }
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
  await ready('/opportunities', '商机发掘')
  await page.getByPlaceholder('输入商品关键词，例如：华为 Mate 80').fill('测试')
  await page.getByRole('button',{name:'开始搜索',exact:true}).click()
  await page.getByRole('heading',{name:'参考商品 A',exact:true}).first().waitFor()
  const initial = calls.length
  await page.locator('.opportunity__result input[type=checkbox]').nth(0).click()
  await page.locator('.opportunity__result input[type=checkbox]').nth(1).click()
  await page.getByLabel('最低想要人数', {exact:true}).fill('500')
  assert.equal(await page.locator('.opportunity__result').count(),1)
  assert.match(await page.locator('.opportunity__preview').innerText(),/已选择 1 件/)
  await page.locator('.opportunity__filters select').selectOption('want-desc')
  assert.equal(await page.locator('.opportunity__result input').isChecked(),true)
  assert.equal(calls.length,initial)
  pass('500 boundary removes hidden selections, sorting preserves visible selections, zero platform calls')
  await page.getByLabel('最低想要人数',{exact:true}).fill('')
  await page.locator('.opportunity__result input').nth(1).click()
  supplyGate = new Promise(resolve => {releaseSupply=resolve})
  await page.getByRole('button',{name:'加入货源库',exact:true}).click()
  await page.getByRole('button',{name:'入库中…',exact:true}).waitFor()
  assert.equal(await page.getByRole('button',{name:'入库中…',exact:true}).isDisabled(),true)
  assert.equal(await page.getByRole('button',{name:'下一步：整理商品',exact:true}).isDisabled(),true)
  releaseSupply(); supplyGate=undefined
  await page.getByText('新增 1 件，已有 1 件',{exact:true}).waitFor()
  assert.equal(calls.at(-1).path,'/api/merchant/opportunities/supply')
  assert.equal(calls.at(-1).request.candidates.length,2)
  assert.equal(calls.length,initial+1)
  assert.equal(await page.locator('.opportunity__result').count(),2)
  assert.equal(await page.getByRole('heading',{name:'整理商品内容',exact:true}).count(),0)
  pass('bulk direct addition stays at search step and uses only new supply endpoint')
  supplyFail=true
  await page.getByRole('button',{name:'加入货源库',exact:true}).click()
  await page.getByText('合成搜索失败',{exact:true}).waitFor()
  assert.equal(await page.locator('.opportunity__result').count(),2)
  assert.match(await page.locator('.opportunity__preview').innerText(),/已选择 2 件/)
  supplyFail=false
  pass('failed direct addition retains results and selections')
  await page.getByLabel('最低想要人数',{exact:true}).fill('500')
  let before=calls.length
  await screenshot('opportunities-desktop.png')
  await page.reload()
  await page.getByRole('heading',{name:'参考商品 A',exact:true}).waitFor()
  assert.equal(await page.getByLabel('最低想要人数',{exact:true}).inputValue(),'500')
  assert.equal(await page.locator('.opportunity__filters select').inputValue(),'want-desc')
  assert.equal(await page.locator('.opportunity__result input').isChecked(),false)
  await sameCalls(before)
  pass('refresh restores new filters but no selection or wizard')
  await page.setViewportSize({width:390,height:844})
  await screenshot('opportunities-mobile.png')
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true)
  await ready('/price-comparison', '全站比价')
  await page.getByPlaceholder('输入商品关键词，例如：iPhone 15 256G').fill('测试')
  await page.getByRole('button',{name:'开始比价',exact:true}).click()
  await page.getByRole('heading',{name:'参考商品 A',exact:true}).waitFor()
  before=calls.length
  await page.getByLabel('最低想要人数',{exact:true}).fill('500')
  await page.locator('.comparison__filters select').selectOption('want-asc')
  assert.equal(await page.locator('.comparison__item').count(),1)
  assert.match(await page.locator('.comparison__metrics').innerText(),/12.70/)
  await sameCalls(before)
  await page.getByRole('button',{name:'加入货源库',exact:true}).click()
  await page.getByText('新增 0 件，已有 1 件',{exact:true}).waitFor()
  assert.equal(calls.at(-1).request.candidates.length,1)
  assert.equal(calls.length,before+1)
  await screenshot('comparison-mobile.png')
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true)
  pass('comparison single addition, filtered statistics and 390px layout')
  before=calls.length
  await page.reload()
  await page.getByRole('heading',{name:'参考商品 A',exact:true}).waitFor()
  assert.equal(await page.getByLabel('最低想要人数',{exact:true}).inputValue(),'500')
  assert.equal(await page.locator('.comparison__filters select').inputValue(),'want-asc')
  await sameCalls(before)
  supplyFail=true
  await page.getByRole('button',{name:'加入货源库',exact:true}).click()
  await page.getByText('合成搜索失败',{exact:true}).waitFor()
  assert.equal(await page.locator('.comparison__item').count(),1)
  pass('comparison refresh restoration and failed single-add keep list')
  await page.setViewportSize({width:1440,height:1000})
  await screenshot('comparison-desktop.png')
  await page.goto(origin+'/supplies')
  await page.getByRole('heading',{name:'货源库',exact:true}).waitFor()
  await page.getByRole('heading',{name:'直接入库无库存',exact:true}).waitFor()
  assert.match(await page.locator('article').filter({hasText:'直接入库无库存'}).innerText(),/库存：未提供/)
  assert.match(await page.locator('article').filter({hasText:'明确填写零库存'}).innerText(),/库存 0/)
  pass('unknown source stock is distinct from explicitly configured zero')
  assert.deepEqual(errors,[])
  console.log('PASS desktop/mobile complete, no runtime errors')
} finally {await browser.close()}
