function escapeHtml(value: string) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

function inline(value: string) {
  return escapeHtml(value)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\*([^*]+)\*/g, '<em>$1</em>')
}

export function renderSafeMarkdown(source: string) {
  const output: string[] = []
  let code: string[] | null = null
  let language = ''
  for (const rawLine of (source ?? '').replaceAll('\r\n', '\n').split('\n')) {
    const fence = rawLine.match(/^```([A-Za-z0-9_-]*)\s*$/)
    if (fence) {
      if (code) {
        output.push(`<pre class="markdown-code" data-language="${escapeHtml(language)}"><code>${escapeHtml(code.join('\n'))}</code></pre>`)
        code = null
        language = ''
      } else {
        code = []
        language = fence[1] ?? ''
      }
      continue
    }
    if (code) {
      code.push(rawLine)
      continue
    }
    const heading = rawLine.match(/^(#{1,4})\s+(.+)$/)
    if (heading) {
      const level = heading[1]?.length ?? 1
      output.push(`<h${level + 1}>${inline(heading[2] ?? '')}</h${level + 1}>`)
    } else if (rawLine.startsWith('> ')) {
      output.push(`<blockquote>${inline(rawLine.slice(2))}</blockquote>`)
    } else if (/^[-*]\s+/.test(rawLine)) {
      output.push(`<p class="markdown-list-item">• ${inline(rawLine.slice(2))}</p>`)
    } else if (rawLine.trim()) {
      output.push(`<p>${inline(rawLine)}</p>`)
    }
  }
  if (code) output.push(`<pre class="markdown-code"><code>${escapeHtml(code.join('\n'))}</code></pre>`)
  return output.join('')
}
