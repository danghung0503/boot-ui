import {describe, expect, it} from 'vitest'
import {changedLines, parseDiagnostics} from './hotpatchEditor.js'

describe('hotpatchEditor', () => {
  it('maps compiler diagnostics to lines and severities', () => {
    expect(parseDiagnostics(['line 12: cannot find symbol', 'note without line'])).toEqual([
      {line: 12, message: 'cannot find symbol', severity: 'error'},
      {line: 1, message: 'note without line', severity: 'error'}
    ])
    expect(parseDiagnostics(['line 3: deprecated'], true)[0].severity).toBe('warning')
    expect(parseDiagnostics(null)).toEqual([])
  })

  it('marks only the lines an edit added or changed', () => {
    const original = 'a\nb\nc\nd'
    expect([...changedLines(original, original)]).toEqual([])
    expect([...changedLines(original, 'a\nB\nc\nd')]).toEqual([2])
    expect([...changedLines(original, 'a\nb\nnew\nc\nd')]).toEqual([3])
    expect([...changedLines(original, 'a\nc\nd')]).toEqual([])
  })
})
