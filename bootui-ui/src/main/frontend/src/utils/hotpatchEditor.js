// mani fork: pure helpers for the Hotpatch panel's Java editor.

/**
 * Compiler diagnostics as the engine reports them ("line 12: cannot find symbol") to editor markers; a diagnostic
 * without a line number marks line 1.
 *
 * @param {string[]} diagnostics
 * @param {boolean} ok whether the compilation succeeded: its diagnostics are warnings, otherwise errors
 * @returns {{line: number, message: string, severity: 'error' | 'warning'}[]}
 */
export function parseDiagnostics(diagnostics, ok = false) {
  return (diagnostics ?? []).map((text) => {
    const match = /^line (\d+): ([\s\S]*)$/.exec(text)
    return {
      line: match ? Number(match[1]) : 1,
      message: match ? match[2] : text,
      severity: ok ? 'warning' : 'error'
    }
  })
}

/**
 * The 1-based line numbers of {@code current} that are not in a longest common subsequence with {@code original}:
 * the lines added or changed by the edit.
 *
 * @param {string} original
 * @param {string} current
 * @returns {Set<number>}
 */
export function changedLines(original, current) {
  const a = (original ?? '').split('\n')
  const b = (current ?? '').split('\n')
  if (a.length * b.length > 4_000_000) return new Set()
  const lcs = Array.from({length: a.length + 1}, () => new Uint32Array(b.length + 1))
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1])
    }
  }
  const changed = new Set()
  let i = 0
  let j = 0
  while (j < b.length) {
    if (i < a.length && a[i] === b[j]) {
      i++
      j++
    } else if (i < a.length && lcs[i + 1][j] >= lcs[i][j + 1]) {
      i++
    } else {
      changed.add(j + 1)
      j++
    }
  }
  return changed
}
