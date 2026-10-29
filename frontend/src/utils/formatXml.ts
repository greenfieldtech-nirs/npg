// ponytail: dependency-free pretty-printer; sufficient for protocol-sized documents.
export function formatXml(xml: string): string {
  const collapsed = xml.replace(/>\s+</g, '><').trim()
  const tokens = collapsed.split(/(?=<)/).map(t => t.trim()).filter(Boolean)
  const lines: string[] = []
  let depth = 0
  let i = 0

  while (i < tokens.length) {
    const token = tokens[i]

    const openWithText = /^<([A-Za-z_][\w:.-]*)[^>]*>([^<]+)$/.exec(token)
    const next = tokens[i + 1]
    if (openWithText && next && next === `</${openWithText[1]}>`) {
      lines.push('  '.repeat(depth) + token + next)
      i += 2
      continue
    }

    const isClosing = /^<\//.test(token)
    const isDeclaration = /^<[?!]/.test(token)
    const isSelfClosing = /\/>$/.test(token)
    const opens = /^<[^/!?][^>]*[^/]>$/.test(token)

    if (isClosing) depth = Math.max(0, depth - 1)

    lines.push('  '.repeat(depth) + token)

    if (opens && !isDeclaration && !isSelfClosing) depth++
    i++
  }
  return lines.join('\n')
}
