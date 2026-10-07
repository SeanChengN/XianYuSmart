import { test } from 'node:test'
import assert from 'node:assert/strict'
import { displayPriceCents, displayPriceSummary, formatMoneyCents } from '../src/utils/competitor-price.ts'

test('search statistics use exact cents and two decimal half-up median', () => {
  const summary = displayPriceSummary(['10.99', '12.70', '21.50', 'bad', undefined])
  assert.deepEqual(summary, { lowest: 1099, median: 1270, highest: 2150 })
  assert.equal(formatMoneyCents(summary.median), '12.70')
  assert.equal(formatMoneyCents(displayPriceSummary(['0.01', '0.02']).median), '0.02')
  assert.equal(formatMoneyCents(displayPriceSummary(['10.99', '12.70']).median), '11.85')
})

test('invalid display values cannot enter statistics', () => {
  for (const value of ['12.7起', '-1', '1.001', 'Infinity', '', '9007199254740992']) {
    assert.equal(displayPriceCents(value), null)
  }
  assert.equal(displayPriceCents('￥ 12.70'), 1270)
  assert.equal(formatMoneyCents(undefined), '--')
  assert.deepEqual(displayPriceSummary([]), { lowest: null, median: null, highest: null })
})

test('serialized Long cents retain exact units and reject unsafe strings', () => {
  assert.equal(formatMoneyCents('1500'), '15.00')
  assert.equal(formatMoneyCents('730'), '7.30')
  assert.equal(formatMoneyCents('0'), '0.00')
  for (const value of ['', '15.00', '1e3', '-1', 'NaN', '9007199254740992']) {
    assert.equal(formatMoneyCents(value), '--')
  }
})
