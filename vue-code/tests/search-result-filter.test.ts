import assert from 'node:assert/strict'
import { test } from 'node:test'
import { filterSearchResults, parseWantCount, validWantMinimum } from '../src/utils/search-result-filter.ts'
import { createSearchCache } from '../src/utils/search-cache.ts'

test('counts preserve zero, displayed approximations and reject invalid text', () => {
  for (const [text, value] of [['879人想要',879], ['1.2万人想要',12000], ['约1.2万+人想要',12000], ['0人想要',0], ['500+人想要',500]] as const)
    assert.equal(parseWantCount(text), value)
  for (const value of [null, undefined, '', '想要879', '-1人想要', '1.2人想要', '已售500', '1万人想要abc', 'Infinity人想要'])
    assert.equal(parseWantCount(value), null)
  for (const value of [-1, .5, NaN, '500']) assert.equal(validWantMinimum(value), false)
})
const items = [
  { id:'a', wantCountText:'500人想要',price:'9.99' },
  { id:'missing',price:'7.00' },
  { id:'b', wantCountText:'499人想要',price:'12.70' },
  { id:'tie', wantCountText:'500+人想要',price:'9.99' },
  { id:'zero',wantCountText:'0人想要',price:'2.00' },
  { id:'large',wantCountText:'1.2万人想要',price:'99.00' },
  { id:'invalid',wantCountText:'bad',price:'4.00' }
]
test('500 boundary excludes missing, invalid and 499; zero minimum retains known zero', () => {
  assert.deepEqual(filterSearchResults(items,500,'relevance').map(x=>x.id),['a','tie','large'])
  assert.equal(filterSearchResults(items,0,'relevance').length,5)
  assert.equal(filterSearchResults(items,'','relevance').length,7)
})
test('stable sorting, missing last both ways and original relevance order', () => {
  assert.deepEqual(filterSearchResults(items,'','want-desc').map(x=>x.id),['large','a','tie','b','zero','missing','invalid'])
  assert.deepEqual(filterSearchResults(items,'','want-asc').map(x=>x.id),['zero','b','a','tie','large','missing','invalid'])
  assert.deepEqual(filterSearchResults(items,500,'price-asc').map(x=>x.id),['a','tie','large'])
  assert.deepEqual(filterSearchResults(items,'','relevance'),items)
})
test('new filters survive storage and old cache remains compatible', () => {
  let data = ''
  const storage = { getItem:()=>data, setItem:(_k:string,v:string)=>{data=v},removeItem:()=>{data=''} }
  const state = { keyword:'test',results:[],pageNumber:1,hasMore:false,total:0,fetchedAt:'2026-10-07T00:00:00Z' }
  const cache = createSearchCache(()=>storage)
  cache.save('user','opportunities',2,{...state,minWantCount:500,sortMode:'want-desc'})
  assert.equal(createSearchCache(()=>storage).read('user','opportunities',2)?.minWantCount,500)
  assert.equal(createSearchCache(()=>storage).read('user','opportunities',2)?.sortMode,'want-desc')
  cache.save('user','comparison',2,state)
  assert.equal(createSearchCache(()=>storage).read('user','comparison',2)?.minWantCount,undefined)
})
