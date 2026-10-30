import { useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { Box, Button, ButtonBase, Container, Dialog, Grid, IconButton, Stack, Typography, useTheme } from '@mui/material'
import ArrowForwardIcon from '@mui/icons-material/ArrowForward'
import CloseIcon from '@mui/icons-material/Close'
import { SectionLabel, SiteFooter, SiteHeader } from '../components/site/SiteChrome'
import { REPO_URL, signalButton } from '../components/site/site'
import { brand, MONO_FONT } from '../theme/brand'

// every capture, light (assets/screens/<id>.webp) and dark (assets/screens/dark/<id>.webp)
const captures = import.meta.glob('../assets/screens/**/*.webp', { eager: true, import: 'default' }) as Record<string, string>
const capture = (id: string, dark: boolean) =>
  (dark && captures[`../assets/screens/dark/${id}.webp`]) || captures[`../assets/screens/${id}.webp`]

interface Screen {
  id: string
  path: string
  title: string
  summary: string
  points: string[]
  /** file name of the capture, without extension */
  image: string
}

interface Group {
  id: string
  label: string
  title: string
  intro: string
  screens: Screen[]
}

// Captured from a demo stack seeded with fictitious data: peer operators, .example endpoints,
// and numbers outside every allocated range of the national plan.
const groups: Group[] = [
  {
    id: 'operate',
    label: '01 — Operate',
    title: 'Run porting from one console',
    intro: 'Start port-ins, follow every process from its first message to its last, and act when a process needs a person.',
    screens: [
      {
        id: 'dashboard', path: '/', title: 'Operations dashboard', image: 'dashboard',
        summary: 'Gateway health at a glance, refreshed every 15 seconds.',
        points: [
          'Active and failed workflows, pending inbox and outbox, open manual tasks, operators down, timers due',
          'Every card opens the queue or list behind it',
          'Israel numbering plan summary below the counters'
        ]
      },
      {
        id: 'workflows', path: '/workflows', title: 'Workflows', image: 'workflows',
        summary: 'Every porting, publication, return, inquiry, maintenance and sync process in one searchable list.',
        points: [
          'Search by phone number, request ID or state; filter by process type and by our role',
          'Filters live in the URL, so a search can be bookmarked or shared',
          'Our role is explicit: recipient, donor, observer, requester or responder'
        ]
      },
      {
        id: 'workflow-detail', path: '/workflows/…', title: 'Workflow detail', image: 'workflow-detail',
        summary: 'The full record of one process, with the actions its current state allows.',
        points: [
          'Overview: request ID, counterparty, port times, execution and completion',
          'Every message of the process with direction, TRX number and delivery status',
          'Timers and publication recipients with their answers',
          'Cancel, move the port time, decide a port-out or make an audited manual transition'
        ]
      },
      {
        id: 'start-port-in', path: '/porting/new', title: 'Start a port-in', image: 'start-port-in',
        summary: 'Port one number, or a whole customer batch, from the donor operator.',
        points: [
          'A batch is split into one workflow per number, grouped under a customer batch ID',
          'Check, Request and the scheduled Execute run on their own from here',
          'Request a port time, or take the default lead time'
        ]
      },
      {
        id: 'processes', path: '/processes', title: 'Processes', image: 'processes',
        summary: 'The protocol\'s other message families, each a click away.',
        points: [
          'Ask an operator who serves a number (Inquire_number)',
          'Return a disconnected ported-in number after its cooling period',
          'Request a peer\'s database synchronization snapshot',
          'Announce this gateway\'s maintenance to every peer (Down_system / Up_system)'
        ]
      }
    ]
  },
  {
    id: 'supervise',
    label: '02 — Supervise',
    title: 'See every message, fix what is stuck',
    intro: 'Queues, timers and messages live in MariaDB and MongoDB, so nothing is lost and everything can be inspected.',
    screens: [
      {
        id: 'queues', path: '/queues', title: 'Queues & timers', image: 'queues',
        summary: 'The durable inbox, outbox and timers, filtered by status.',
        points: [
          'See retries, the last error and the workflow behind each item',
          'Retry, confirm delivery, cancel, or fire a timer now',
          'Every action asks for a reason and is written to the audit log'
        ]
      },
      {
        id: 'manual-tasks', path: '/manual-tasks', title: 'Manual tasks', image: 'manual-tasks',
        summary: 'What the gateway hands to the NOC instead of guessing.',
        points: [
          'Publication conflicts, messages that could not be processed, and port-outs awaiting a decision',
          'Severity, a plain-language description and a link to the workflow',
          'Take a task, add notes and resolve it'
        ]
      },
      {
        id: 'log-viewer', path: '/messages', title: 'Log viewer', image: 'log-viewer',
        summary: 'Every inbound and outbound protocol message.',
        points: [
          'Filter by message type, direction, status, request ID and date',
          'Open a message to see its request/response pair'
        ]
      },
      {
        id: 'raw-xml', path: '/messages/…/xml', title: 'Raw XML', image: 'raw-xml',
        summary: 'The exact XML that was received or sent, from the immutable audit store.',
        points: [
          'Stored with its SHA-256 before it is acknowledged or sent',
          'Every view is itself recorded in the audit log'
        ]
      }
    ]
  },
  {
    id: 'configure',
    label: '03 — Configure',
    title: 'Peers, identity and access',
    intro: 'Who this gateway is, who it talks to, which numbers each operator owns, and who may do what.',
    screens: [
      {
        id: 'operators', path: '/operators', title: 'Operators', image: 'operators',
        summary: 'Peer operators, their endpoints and their availability.',
        points: [
          'Mark a peer down to hold its traffic; mark it up to release it',
          'Endpoints with the peer\'s credentials: client-certificate subject and IP allowlist',
          'Unknown operator codes register themselves and are flagged for configuration'
        ]
      },
      {
        id: 'local-setup', path: '/settings/local', title: 'Local setup', image: 'local-setup',
        summary: 'This gateway\'s own operator identity and public endpoint.',
        points: ['Operator code, name, licence type and contacts', 'The base URL peers send to']
      },
      {
        id: 'number-ranges', path: '/number-ranges', title: 'Number ranges', image: 'number-ranges',
        summary: 'Number blocks allocated to each operator.',
        points: [
          'Decide who natively serves a number nobody has ported',
          'Publications and port-outs that contradict them are refused'
        ]
      },
      {
        id: 'users', path: '/users', title: 'Users & roles', image: 'users',
        summary: 'Console accounts with three roles.',
        points: [
          'USER reads, MANAGER operates, ADMIN also configures',
          'Roles are re-checked against the database on every request'
        ]
      }
    ]
  },
  {
    id: 'numbers',
    label: '04 — Numbers',
    title: 'Where every number lives',
    intro: 'Today\'s routing from this gateway\'s records, and the original allocation from the national plan.',
    screens: [
      {
        id: 'numbers', path: '/numbers', title: 'Number lookup', image: 'numbers',
        summary: 'The routing record of a number and its ownership history.',
        points: ['Current operator, port, return and publication dates', 'Every ownership change, from which operator to which']
      },
      {
        id: 'numbering-plan', path: '/numbering-plan', title: 'Israel numbering plan', image: 'numbering-plan',
        summary: 'The Ministry of Communications plan, classified with Google libphonenumber.',
        points: [
          'Imported from data.gov.il, or uploaded as a CSV',
          'Allocated numbers per operator, and every range with filters',
          'Look up any number\'s original operator, type and porting status'
        ]
      }
    ]
  }
]

function Shot({ screen, onOpen }: { screen: Screen; onOpen: () => void }) {
  const dark = useTheme().palette.mode === 'dark'
  return (
    <ButtonBase onClick={onOpen} aria-label={`Enlarge the ${screen.title} screenshot`}
      sx={{
        display: 'block', width: '100%', textAlign: 'left', borderRadius: '14px', overflow: 'hidden',
        border: 1, borderColor: 'divider', bgcolor: 'background.paper',
        boxShadow: '0px 18px 40px -24px rgba(14, 23, 38, 0.45)',
        transition: 'transform 200ms ease, box-shadow 200ms ease',
        '&:hover': { transform: 'translateY(-2px)', boxShadow: '0px 24px 48px -24px rgba(14, 23, 38, 0.55)' },
        '&:focus-visible': { outline: '3px solid', outlineColor: 'primary.main', outlineOffset: 3 }
      }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, px: 1.75, py: 1, bgcolor: brand.ink }}>
        {[0, 1, 2].map(d => <Box key={d} sx={{ width: 9, height: 9, borderRadius: '50%', bgcolor: brand.slate }} />)}
        <Box sx={{ ml: 1, fontFamily: MONO_FONT, fontSize: 12, color: brand.mist }}>npg{screen.path}</Box>
      </Box>
      <Box component="img" src={capture(screen.image, dark)} alt={`${screen.title}: ${screen.summary}`} loading="lazy"
        width={1440} height={900} sx={{ display: 'block', width: '100%', height: 'auto' }} />
    </ButtonBase>
  )
}

export default function FeaturesPage() {
  const [open, setOpen] = useState<Screen | null>(null)
  const dark = useTheme().palette.mode === 'dark'

  return (
    <Box sx={{ bgcolor: 'background.default', minHeight: '100vh' }}>
      <SiteHeader>
        <Container maxWidth="lg" sx={{ pt: { xs: 5, md: 8 }, pb: { xs: 6, md: 9 } }}>
          <SectionLabel dark>Features</SectionLabel>
          <Typography variant="h1" sx={{ fontSize: { xs: 36, md: 56 }, lineHeight: 1.1, mb: 3, color: brand.paper, maxWidth: 820 }}>
            Every screen of the console.
          </Typography>
          <Typography sx={{ fontSize: 18, lineHeight: 1.65, mb: 4, maxWidth: 640, color: brand.mist }}>
            The NOC runs number portability from one place: start and follow processes, supervise the queues,
            configure peers, and see where every number lives. Every action that changes state is audited.
          </Typography>
          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
            {groups.map(g => (
              <Button key={g.id} href={`#${g.id}`} variant="outlined" size="small"
                sx={{ fontFamily: MONO_FONT, fontWeight: 500, color: brand.paper, borderColor: brand.slate,
                  '&:hover': { borderColor: brand.signal, bgcolor: 'transparent' } }}>
                {g.label}
              </Button>
            ))}
          </Stack>
        </Container>
      </SiteHeader>

      {groups.map((group, gi) => (
        <Box key={group.id} id={group.id} component="section"
          sx={{ py: { xs: 7, md: 10 }, bgcolor: gi % 2 ? 'background.paper' : 'background.default',
            borderTop: gi % 2 ? 1 : 0, borderBottom: gi % 2 ? 1 : 0, borderColor: 'divider' }}>
          <Container maxWidth="lg">
            <SectionLabel>{group.label}</SectionLabel>
            <Typography variant="h2" sx={{ fontSize: { xs: 28, md: 36 }, mb: 1.5 }}>{group.title}</Typography>
            <Typography variant="body1" color="text.secondary" sx={{ mb: { xs: 5, md: 7 }, maxWidth: 660 }}>{group.intro}</Typography>
            {/* gap, not Stack spacing: Stack resets child margins and would cancel the Grid gutters */}
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: { xs: 7, md: 10 } }}>
              {group.screens.map((screen, si) => (
                <Grid key={screen.id} id={screen.id} container spacing={{ xs: 3, md: 6 }} alignItems="center"
                  direction={{ xs: 'column-reverse', md: si % 2 ? 'row-reverse' : 'row' }}>
                  <Grid item xs={12} md={4}>
                    <Typography variant="h4" sx={{ mb: 1 }}>{screen.title}</Typography>
                    <Typography variant="body1" color="text.secondary" sx={{ mb: 2 }}>{screen.summary}</Typography>
                    <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
                      {screen.points.map(point => (
                        <Box component="li" key={point} sx={{ display: 'flex', gap: 1.25, mb: 1.25, fontSize: 15, lineHeight: 1.55 }}>
                          <Box component="span" aria-hidden sx={{ fontFamily: MONO_FONT, fontWeight: 700, color: 'primary.main' }}>&gt;</Box>
                          <span>{point}</span>
                        </Box>
                      ))}
                    </Box>
                  </Grid>
                  <Grid item xs={12} md={8} sx={{ width: '100%' }}>
                    <Shot screen={screen} onOpen={() => setOpen(screen)} />
                  </Grid>
                </Grid>
              ))}
            </Box>
          </Container>
        </Box>
      ))}

      <Box sx={{ bgcolor: brand.ink2, color: brand.paper, py: { xs: 7, md: 9 } }}>
        <Container maxWidth="lg">
          <Grid container spacing={4} alignItems="center">
            <Grid item xs={12} md={6}>
              <SectionLabel dark>Run it yourself</SectionLabel>
              <Typography variant="h2" sx={{ fontSize: { xs: 28, md: 36 }, mb: 1.5, color: brand.paper }}>
                The whole stack, one command.
              </Typography>
              <Typography sx={{ color: brand.mist, mb: 3 }}>
                MariaDB, MongoDB, Redis, the gateway and this console, in containers. No local JDK or Node.js needed.
              </Typography>
              <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', rowGap: 2 }}>
                <Button component={RouterLink} to="/login" variant="contained" endIcon={<ArrowForwardIcon />} sx={signalButton}>
                  Sign in to the console
                </Button>
                <Button href={REPO_URL} target="_blank" rel="noopener noreferrer" variant="outlined"
                  sx={{ color: brand.paper, borderColor: brand.slate, '&:hover': { borderColor: brand.paper, bgcolor: 'transparent' } }}>
                  View on GitHub
                </Button>
              </Stack>
            </Grid>
            <Grid item xs={12} md={6}>
              <Box component="pre" sx={{
                m: 0, p: 3, borderRadius: '14px', bgcolor: brand.ink, border: `1px solid ${brand.slate}`,
                fontFamily: MONO_FONT, fontSize: 14, lineHeight: 1.9, color: brand.paper, overflowX: 'auto'
              }}>
                <Box component="span" sx={{ color: brand.signal }}>$</Box> git clone {REPO_URL}.git{'\n'}
                <Box component="span" sx={{ color: brand.signal }}>$</Box> cd npg{'\n'}
                <Box component="span" sx={{ color: brand.signal }}>$</Box> ./npg -e dev start
              </Box>
            </Grid>
          </Grid>
        </Container>
      </Box>

      <SiteFooter />

      <Dialog open={!!open} onClose={() => setOpen(null)} maxWidth="xl" fullWidth
        aria-label={open ? `${open.title} screenshot` : undefined}
        PaperProps={{ sx: { bgcolor: brand.ink, borderRadius: '14px', overflow: 'hidden' } }}>
        {open && (
          <Box>
            <Box sx={{ display: 'flex', alignItems: 'center', px: 2, py: 1 }}>
              <Box sx={{ fontFamily: MONO_FONT, fontSize: 13, color: brand.mist, flexGrow: 1 }}>{open.title} · npg{open.path}</Box>
              <IconButton aria-label="Close" onClick={() => setOpen(null)} sx={{ color: brand.mist, '&:hover': { color: brand.paper } }}>
                <CloseIcon />
              </IconButton>
            </Box>
            <Box component="img" src={capture(open.image, dark)} alt={`${open.title}: ${open.summary}`}
              sx={{ display: 'block', width: '100%', height: 'auto' }} />
          </Box>
        )}
      </Dialog>
    </Box>
  )
}
