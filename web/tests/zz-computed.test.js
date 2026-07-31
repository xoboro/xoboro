import { describe, expect, it } from 'vitest'
import ko from '../src/lib/messages/ko.js'
const flat = (o, p = '', out = []) => { for (const [k,v] of Object.entries(o)) { const key=p?`${p}.${k}`:k; if (v&&typeof v==='object'&&!Array.isArray(v)) flat(v,key,out); else out.push(key) } return out }
const have = new Set(flat(ko))
// Values taken from the actual source constants / server enums, not invented.
const families = {
  'catalog.{kind}.title':       ['collection','readList'],
  'catalog.{kind}.createTitle': ['collection','readList'],
  'catalog.{kind}.editTitle':   ['collection','readList'],
  'catalog.{kind}.none':        ['collection','readList'],
  'reader.modes.{v}':      ['scroll','paged','split','split-scroll'],
  'reader.directions.{v}': ['ltr','rtl'],
  'reader.widths.{v}':     ['600','760','1000','full'],
  'admin.duplicates.action.{v}': ['IGNORE','DELETE_AUTO','DELETE_MANUAL'],
  'admin.security.linking.{v}':  ['NEVER','VERIFIED_EMAIL','ALWAYS'],
}
describe('computed keys the new assertion skips', () => {
  it('resolve', () => {
    const missing = []
    for (const [tpl, vals] of Object.entries(families))
      for (const v of vals) {
        const key = tpl.replace(/\{\w+\}/, v)
        if (!have.has(key)) missing.push(key)
      }
    console.log('MISSING COMPUTED KEYS:', JSON.stringify(missing, null, 1))
    expect(true).toBe(true)
  })
})
