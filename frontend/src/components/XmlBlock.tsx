import { Box, Typography } from '@mui/material'
import { ReactNode } from 'react'
import { formatXml } from '../utils/formatXml'

function colorizeXmlLine(line: string, key: number): ReactNode {
  const parts: ReactNode[] = []
  let keySeq = 0
  const re = /(<\/?[A-Za-z_][\w:.-]*|\/?>|[\w:.-]+="[^"]*")/g
  let last = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(line)) !== null) {
    if (m.index > last) {
      parts.push(<span key={keySeq++}>{line.slice(last, m.index)}</span>)
    }
    const token = m[0]
    let color = 'text.primary'
    if (token.startsWith('<')) color = 'primary.main'
    else if (token === '>' || token === '/>') color = 'primary.main'
    else if (token.includes('=')) color = 'secondary.dark'
    parts.push(
      <Box component="span" key={keySeq++} sx={{ color }}>
        {token}
      </Box>
    )
    last = m.index + token.length
  }
  if (last < line.length) {
    parts.push(<span key={keySeq++}>{line.slice(last)}</span>)
  }
  return <div key={key}>{parts}</div>
}

export default function XmlBlock({ xml }: { xml: string }) {
  return (
    <Typography
      component="pre"
      sx={{
        fontFamily: 'monospace',
        fontSize: 13,
        lineHeight: 1.6,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        m: 0
      }}
    >
      {formatXml(xml).split('\n').map((line, i) => colorizeXmlLine(line, i))}
    </Typography>
  )
}
