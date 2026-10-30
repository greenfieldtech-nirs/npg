import { useMemo, useState } from 'react'
import {
  Box, Button, Card, Container, Grid, Link, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  ToggleButton, ToggleButtonGroup, Typography, useTheme
} from '@mui/material'
import { SectionLabel, SiteFooter, SiteHeader } from '../components/site/SiteChrome'
import { brand, MONO_FONT } from '../theme/brand'
import { families, protocolSource } from './compatibility/data'
import { ProtocolMessage, SupportLevel } from './compatibility/types'

const LEVELS: Record<SupportLevel, { label: string; glyph: string; color: string; onDark: string; description: string }> = {
  full: {
    label: 'Fully supported', glyph: '●', color: brand.ported.light, onDark: brand.ported.dark,
    description: 'Sent and received in every role the protocol gives an operator, acted on end to end, and covered by tests.'
  },
  partial: {
    label: 'Partially supported', glyph: '◐', color: brand.pending.light, onDark: brand.pending.dark,
    description: 'Works, with a stated limitation: a role not covered, an action not taken, or a wire detail still to confirm.'
  },
  development: {
    label: 'Under development', glyph: '○', color: brand.slate, onDark: brand.mist,
    description: 'Not processed yet, or only accepted and acknowledged. Planned work.'
  }
}

const WIRE = {
  confirmed: { label: 'trace-confirmed', title: 'Body shape seen in a live operator trace' },
  inferred: { label: 'inferred', title: 'Body shape inferred from the specification; confirm with your peers' }
} as const

function WireTag({ wire }: { wire: ProtocolMessage['wire'] }) {
  return (
    <Box component="span" title={WIRE[wire].title} sx={{
      display: 'inline-block', px: 1, py: '1px', borderRadius: '4px', whiteSpace: 'nowrap',
      fontFamily: MONO_FONT, fontSize: 11.5, fontWeight: 500,
      color: wire === 'confirmed' ? 'primary.main' : 'text.secondary',
      border: 1, borderColor: wire === 'confirmed' ? 'primary.main' : 'divider'
    }}>
      {WIRE[wire].label}
    </Box>
  )
}

/** Status colors have a light and a dark variant in the brand kit. */
function useLevelColor() {
  const isDark = useTheme().palette.mode === 'dark'
  return (level: SupportLevel, onDark = false) => (onDark || isDark ? LEVELS[level].onDark : LEVELS[level].color)
}

const anchor = (name: string) => `msg-${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`

function LevelBadge({ level, dark = false }: { level: SupportLevel; dark?: boolean }) {
  const l = LEVELS[level]
  const color = useLevelColor()(level, dark)
  return (
    <Box component="span" sx={{
      display: 'inline-flex', alignItems: 'center', gap: 0.75, px: 1.25, py: 0.25, borderRadius: '999px', whiteSpace: 'nowrap',
      fontSize: 13, fontWeight: 600, color, border: `1px solid ${color}`
    }}>
      <span aria-hidden>{l.glyph}</span>{l.label}
    </Box>
  )
}

/** Headers in Mist, XML tags in Signal, values in Paper — the same terminal look as the landing page. */
function WireExample({ code, name }: { code: string; name: string }) {
  return (
    <Box sx={{ borderRadius: '12px', overflow: 'hidden', bgcolor: brand.ink2, border: 1, borderColor: theme => theme.palette.mode === 'dark' ? 'divider' : brand.ink2, height: '100%' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, px: 1.75, py: 1, bgcolor: brand.ink }}>
        {[0, 1, 2].map(d => <Box key={d} sx={{ width: 9, height: 9, borderRadius: '50%', bgcolor: brand.slate }} />)}
        <Box sx={{ ml: 1, fontFamily: MONO_FONT, fontSize: 12, color: brand.mist }}>example · {name}</Box>
      </Box>
      <Box component="pre" sx={{
        m: 0, p: 2, fontFamily: MONO_FONT, fontSize: 12.5, lineHeight: 1.7, color: brand.paper,
        whiteSpace: 'pre', overflowX: 'auto'
      }}>
        {code.split('\n').map((line, i) => {
          const header = /^(X-NP-[A-Z-]+|Content-Type):(.*)$/.exec(line)
          if (header) return <div key={i}><Box component="span" sx={{ color: brand.mist }}>{header[1]}:</Box>{header[2]}</div>
          if (line.startsWith('#')) return <div key={i}><Box component="span" sx={{ color: brand.mist }}>{line}</Box></div>
          const parts = line.split(/(<\/?[A-Za-z_][\w:.-]*\/?>)/g)
          return (
            <div key={i}>
              {parts.map((p, j) => (p.startsWith('<')
                ? <Box component="span" key={j} sx={{ color: brand.signal }}>{p}</Box>
                : <span key={j}>{p}</span>))}
              {line === '' && ' '}
            </div>
          )
        })}
      </Box>
    </Box>
  )
}

function Role({ title, text }: { title: string; text: string | null }) {
  return (
    <Box sx={{ mb: 1.5 }}>
      <Typography variant="overline" component="div" sx={{ color: 'text.secondary', lineHeight: 1.6 }}>{title}</Typography>
      <Typography variant="body2" sx={{ color: text ? 'text.primary' : 'text.secondary' }}>
        {text ?? 'Not applicable: NPG never does this.'}
      </Typography>
    </Box>
  )
}

function MessageEntry({ m }: { m: ProtocolMessage }) {
  return (
    <Card id={anchor(m.name)} sx={{ p: { xs: 2.5, md: 3 }, scrollMarginTop: 24, '&:hover': { boxShadow: 'none' } }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ xs: 'flex-start', sm: 'center' }} sx={{ mb: 2 }}>
        <Typography component="h3" sx={{ fontFamily: MONO_FONT, fontWeight: 700, fontSize: 20 }}>{m.name}</Typography>
        <Box sx={{ fontFamily: MONO_FONT, fontSize: 12, color: 'text.secondary', flexGrow: 1 }}>{m.direction}</Box>
        <Stack direction="row" spacing={1} alignItems="center">
          <WireTag wire={m.wire} />
          <LevelBadge level={m.level} />
        </Stack>
      </Stack>
      <Grid container spacing={3}>
        <Grid item xs={12} md={5}>
          <Typography variant="body1" sx={{ mb: 2 }}>{m.explanation}</Typography>
          <Role title="When NPG sends it" text={m.sends} />
          <Role title="When NPG receives it" text={m.receives} />
          {m.limitations && m.limitations.length > 0 && (
            <Box>
              <Typography variant="overline" component="div" sx={{ color: 'text.secondary', lineHeight: 1.6 }}>Limitations</Typography>
              <Box component="ul" sx={{ m: 0, pl: 2.25 }}>
                {m.limitations.map(l => <Typography component="li" variant="body2" key={l} sx={{ mb: 0.5 }}>{l}</Typography>)}
              </Box>
            </Box>
          )}
        </Grid>
        <Grid item xs={12} md={7} sx={{ minWidth: 0 }}>
          <WireExample code={m.example} name={m.name} />
        </Grid>
      </Grid>
    </Card>
  )
}

export default function CompatibilityPage() {
  const [filter, setFilter] = useState<SupportLevel | 'all'>('all')
  const levelColor = useLevelColor()
  const all = useMemo(() => families.flatMap(f => f.messages), [])
  const counts = useMemo(() => all.reduce((c, m) => ({ ...c, [m.level]: c[m.level] + 1 }), { full: 0, partial: 0, development: 0 }), [all])
  const visible = (m: ProtocolMessage) => filter === 'all' || m.level === filter

  return (
    <Box sx={{ bgcolor: 'background.default', minHeight: '100vh' }}>
      <SiteHeader>
        <Container maxWidth="lg" sx={{ pt: { xs: 5, md: 8 }, pb: { xs: 6, md: 9 } }}>
          <SectionLabel dark>Compatibility</SectionLabel>
          <Typography variant="h1" sx={{ fontSize: { xs: 36, md: 56 }, lineHeight: 1.1, mb: 3, color: brand.paper, maxWidth: 860 }}>
            Every protocol message, and how far NPG supports it.
          </Typography>
          <Typography sx={{ fontSize: 18, lineHeight: 1.65, mb: 4, maxWidth: 700, color: brand.mist }}>
            The {all.length} requests and responses of the Israeli number-portability protocol ({protocolSource}),
            each with what NPG does with it, its limitations and an example as it appears on the wire.
          </Typography>
          <Grid container spacing={2}>
            {(Object.keys(LEVELS) as SupportLevel[]).map(level => (
              <Grid item xs={12} md={4} key={level}>
                <Box sx={{ p: 2.5, height: '100%', borderRadius: '14px', bgcolor: brand.ink2 }}>
                  <Stack direction="row" alignItems="baseline" spacing={1.5} sx={{ mb: 1 }}>
                    <Typography sx={{ fontFamily: MONO_FONT, fontWeight: 700, fontSize: 32, color: brand.paper }}>{counts[level]}</Typography>
                    <LevelBadge level={level} dark />
                  </Stack>
                  <Typography variant="body2" sx={{ color: brand.mist }}>{LEVELS[level].description}</Typography>
                </Box>
              </Grid>
            ))}
          </Grid>
        </Container>
      </SiteHeader>

      <Container maxWidth="lg" sx={{ py: { xs: 6, md: 8 } }}>
        <SectionLabel>At a glance</SectionLabel>
        <Typography variant="h2" sx={{ fontSize: { xs: 28, md: 36 }, mb: 3 }}>Support matrix</Typography>
        <TableContainer component={Card} sx={{ '&:hover': { boxShadow: 'none' } }}>
          <Table size="small" aria-label="Protocol message support matrix">
            <TableHead>
              <TableRow>
                <TableCell>Message</TableCell>
                <TableCell>Direction</TableCell>
                <TableCell align="center">Sends</TableCell>
                <TableCell align="center">Receives</TableCell>
                <TableCell>Wire format</TableCell>
                <TableCell>Support</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {families.flatMap(f => [
                <TableRow key={f.id}>
                  <TableCell colSpan={6} sx={{ bgcolor: 'background.default', fontWeight: 600, py: 1 }}>{f.title}</TableCell>
                </TableRow>,
                ...f.messages.map(m => (
                <TableRow key={m.name} hover>
                  <TableCell sx={{ fontFamily: MONO_FONT, fontWeight: 500, whiteSpace: 'nowrap' }}>
                    <Link href={`#${anchor(m.name)}`} underline="hover">{m.name}</Link>
                  </TableCell>
                  <TableCell sx={{ fontFamily: MONO_FONT, fontSize: 12, color: 'text.secondary', whiteSpace: 'nowrap' }}>{m.direction}</TableCell>
                  <TableCell align="center" aria-label={m.sends ? 'yes' : 'no'}>{m.sends ? 'Yes' : '—'}</TableCell>
                  <TableCell align="center" aria-label={m.receives ? 'yes' : 'no'}>{m.receives ? 'Yes' : '—'}</TableCell>
                  <TableCell><WireTag wire={m.wire} /></TableCell>
                  <TableCell><LevelBadge level={m.level} /></TableCell>
                </TableRow>
              ))])}
            </TableBody>
          </Table>
        </TableContainer>
      </Container>

      <Box sx={{ bgcolor: 'background.paper', borderTop: 1, borderColor: 'divider', py: { xs: 6, md: 8 } }}>
        <Container maxWidth="lg">
          <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" alignItems={{ md: 'flex-end' }} spacing={2} sx={{ mb: 4 }}>
            <Box>
              <SectionLabel>In detail</SectionLabel>
              <Typography variant="h2" sx={{ fontSize: { xs: 28, md: 36 } }}>Requests and responses</Typography>
            </Box>
            <ToggleButtonGroup size="small" exclusive value={filter} onChange={(_e, v) => v && setFilter(v)} aria-label="Filter by support level"
              sx={{ flexWrap: 'wrap' }}>
              <ToggleButton value="all">All ({all.length})</ToggleButton>
              {(Object.keys(LEVELS) as SupportLevel[]).map(level => (
                <ToggleButton key={level} value={level}>
                  <Box component="span" aria-hidden sx={{ color: levelColor(level), mr: 0.75 }}>{LEVELS[level].glyph}</Box>
                  {LEVELS[level].label} ({counts[level]})
                </ToggleButton>
              ))}
            </ToggleButtonGroup>
          </Stack>

          {families.map(f => {
            const shown = f.messages.filter(visible)
            if (shown.length === 0) return null
            return (
              <Box key={f.id} id={f.id} component="section" sx={{ mb: 7 }}>
                <Typography variant="h4" sx={{ mb: 0.5 }}>{f.title}</Typography>
                <Typography variant="body1" color="text.secondary" sx={{ mb: 3, maxWidth: 760 }}>{f.summary}</Typography>
                <Stack spacing={2.5}>
                  {shown.map(m => <MessageEntry key={m.name} m={m} />)}
                </Stack>
              </Box>
            )
          })}

          <Card sx={{ p: 3, bgcolor: 'background.default', '&:hover': { boxShadow: 'none' } }}>
            <Typography variant="h6" sx={{ mb: 1 }}>About these levels</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
              The support level says what NPG does with a message. The wire format says how sure the XML is:
            </Typography>
            <Stack spacing={1} sx={{ mb: 2 }}>
              <Typography variant="body2" color="text.secondary"><WireTag wire="confirmed" /> the body has the shape seen in a live operator trace.</Typography>
              <Typography variant="body2" color="text.secondary"><WireTag wire="inferred" /> the body follows the scanned specification and NPG&apos;s documented assumptions.</Typography>
            </Stack>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              Every inferred detail, including how the X-NP-* header fields are carried, is implemented as configuration or an
              isolated rule and listed in the project&apos;s open questions. Confirm them with your peers before connecting
              to live operators.
            </Typography>
            <Button href="https://github.com/greenfieldtech-nirs/npg/blob/main/docs/protocol/open-questions.md"
              target="_blank" rel="noopener noreferrer" variant="outlined" size="small">
              Read the open questions
            </Button>
          </Card>
        </Container>
      </Box>

      <SiteFooter />
    </Box>
  )
}
