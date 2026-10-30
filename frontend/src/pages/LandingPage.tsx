import { Link as RouterLink } from 'react-router-dom'
import {
  Box, Button, Card, CardContent, Container, Grid, Typography, Accordion, AccordionSummary, AccordionDetails
} from '@mui/material'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import ArrowForwardIcon from '@mui/icons-material/ArrowForward'
import { SectionLabel, SiteFooter, SiteHeader } from '../components/site/SiteChrome'
import { REPO_URL, signalButton } from '../components/site/site'
import { brand, MONO_FONT } from '../theme/brand'

const codeSample = `POST /np/inbound HTTP/1.1
Content-Type: application/xml
X-NP-FROM: BZ
X-NP-TO: HL
X-NP-MSG-TYPE: Check
X-NP-REQUEST-ID: NPBZHL261029000001
X-NP-TRX-NO: BZ000959453468

<npMessageBody>
  <Check>
    <fixed>
      <fixedNumberSingle>
        <number>08XXXXXXX</number>
      </fixedNumberSingle>
    </fixed>
  </Check>
</npMessageBody>`

const ackSample = `<AckNack>
  <Status>ACK</Status>
  <OriginalTransactionId>BZ000959453468</OriginalTransactionId>
</AckNack>`

/** Header names in Mist, the request line and XML tags in Signal, values in Paper. */
function terminalLine(line: string, i: number) {
  const header = /^([A-Za-z-]+):(.*)$/.exec(line)
  if (i === 0 && /^[A-Z]+ /.test(line)) {
    const [method, ...rest] = line.split(' ')
    return <div key={i}><Box component="span" sx={{ color: brand.signal }}>{method}</Box> {rest.join(' ')}</div>
  }
  if (header) return <div key={i}><Box component="span" sx={{ color: brand.mist }}>{header[1]}:</Box>{header[2]}</div>
  const parts = line.split(/(<\/?[A-Za-z_][\w:.-]*>)/g)
  return (
    <div key={i}>
      {parts.map((p, j) => (p.startsWith('<')
        ? <Box component="span" key={j} sx={{ color: brand.signal }}>{p}</Box>
        : <span key={j}>{p}</span>))}
      {line === '' && ' '}
    </div>
  )
}

function Terminal({ title, code }: { title: string; code: string }) {
  return (
    <Box sx={{ borderRadius: '14px', overflow: 'hidden', bgcolor: brand.ink2, border: `1px solid ${brand.slate}` }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2, py: 1.25, bgcolor: brand.ink, borderBottom: `1px solid ${brand.ink2}` }}>
        {[0, 1, 2].map(d => <Box key={d} sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: brand.slate }} />)}
        <Box sx={{ ml: 1, fontFamily: MONO_FONT, fontSize: 12, color: brand.mist }}>{title}</Box>
      </Box>
      <Box component="pre" sx={{
        m: 0, p: 2.5, fontFamily: MONO_FONT, fontSize: 13, lineHeight: 1.7, color: brand.paper,
        whiteSpace: 'pre-wrap', wordBreak: 'break-word', overflowX: 'auto'
      }}>
        {code.split('\n').map(terminalLine)}
      </Box>
    </Box>
  )
}

function FeatureCard({ index, title, children }: { index: string; title: string; children: string }) {
  return (
    <Card sx={{ height: '100%', '&:hover': { boxShadow: 'none' } }}>
      <CardContent sx={{ p: 3 }}>
        <Box sx={{ fontFamily: MONO_FONT, fontSize: 13, fontWeight: 500, color: brand.deepSignal, mb: 1.5 }}>{index}</Box>
        <Typography variant="h6" sx={{ mb: 1 }}>{title}</Typography>
        <Typography variant="body2" color="text.secondary">{children}</Typography>
      </CardContent>
    </Card>
  )
}

const faqs = [
  {
    q: 'What is NPG?',
    a: 'NPG is a gateway that lets a licensed Israeli telecom operator exchange number-portability messages with peer operators: eligibility checks, porting requests, updates, cancellations, execution, publication, number return, inquiries, maintenance, and database synchronization.'
  },
  {
    q: 'Is raw XML stored anywhere?',
    a: 'Yes. Every inbound and outbound message is stored immutably with a SHA-256 hash in a dedicated audit store, linked from the message record. Every retrieval is itself audited.'
  },
  {
    q: 'What is the difference between ACK and a business response?',
    a: 'ACK/NACK is the technical receipt only — it confirms the message was received and durably stored. Business approval or rejection always arrives later as a separate response message (e.g., Check_response).'
  },
  {
    q: 'How is it licensed?',
    a: 'Dual license: GPL-3.0 for non-commercial and small-scale use (up to 50,000 numbers), and a commercial license for larger deployments.'
  }
]

export default function LandingPage() {
  return (
    <Box sx={{ bgcolor: 'background.default', minHeight: '100vh' }}>
      <SiteHeader>
        <Container maxWidth="lg" sx={{ pt: { xs: 6, md: 10 }, pb: { xs: 8, md: 12 } }}>
          <Grid container spacing={6} alignItems="center">
            <Grid item xs={12} md={6}>
              <SectionLabel dark>Israeli number portability</SectionLabel>
              <Typography variant="h1" sx={{ fontSize: { xs: 38, md: 56 }, lineHeight: 1.1, mb: 3, color: brand.paper }}>
                Every number, exactly where it belongs.
              </Typography>
              <Typography sx={{ fontSize: 18, lineHeight: 1.65, mb: 4, maxWidth: 500, color: brand.mist }}>
                NPG is the portability exchange for licensed operators: requests, responses, executions and
                publications over the Israeli NP protocol, with a durable audit trail for every message.
              </Typography>
              <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                <Button component={RouterLink} to="/login" variant="contained" size="large" endIcon={<ArrowForwardIcon />}
                  sx={signalButton}>
                  Sign in to the console
                </Button>
                <Button href={REPO_URL} target="_blank" rel="noopener noreferrer" variant="outlined" size="large"
                  sx={{ color: brand.paper, borderColor: brand.slate, '&:hover': { borderColor: brand.paper, bgcolor: 'transparent' } }}>
                  View on GitHub
                </Button>
              </Box>
            </Grid>
            <Grid item xs={12} md={6}>
              <Terminal title="peer → npg · inbound Check" code={codeSample} />
            </Grid>
          </Grid>
        </Container>
      </SiteHeader>

      <Container maxWidth="lg" sx={{ py: { xs: 7, md: 10 } }} id="features">
        <SectionLabel>01 — What it does</SectionLabel>
        <Typography variant="h2" sx={{ fontSize: { xs: 28, md: 36 }, mb: 1.5 }}>
          One gateway in front of every portability message.
        </Typography>
        <Typography variant="body1" color="text.secondary" sx={{ mb: 4, maxWidth: 620 }}>
          Built for the operator that has to answer to the regulator, the NOC, and the audit team at once.
        </Typography>
        <Grid container spacing={3}>
          <Grid item xs={12} md={4}>
            <FeatureCard index="// messages" title="One gateway for every message">
              Eligibility checks, porting requests, updates, cancellations, execution,
              publication, number return, inquiries, maintenance, and database
              synchronization: one inbound endpoint, one outbound pipeline.
            </FeatureCard>
          </Grid>
          <Grid item xs={12} md={4}>
            <FeatureCard index="// durability" title="Durable by design">
              Every message lands in MariaDB before its ACK is returned: inbox/outbox
              queues with atomic claiming, durable timers, idempotent retries, and
              crash-safe recovery. No external broker required.
            </FeatureCard>
          </Grid>
          <Grid item xs={12} md={4}>
            <FeatureCard index="// audit" title="Every message, auditable">
              Raw XML is stored immutably in MongoDB with SHA-256 hashes, linked to
              workflow state and event history. Manual actions and XML views are
              audit-logged by design.
            </FeatureCard>
          </Grid>
        </Grid>
        <Button component={RouterLink} to="/features" endIcon={<ArrowForwardIcon />} sx={{ mt: 3 }}>
          Tour every screen of the console
        </Button>
      </Container>

      <Box sx={{ bgcolor: '#FFFFFF', borderTop: `1px solid ${brand.line}`, borderBottom: `1px solid ${brand.line}`, py: { xs: 7, md: 10 } }} id="protocol">
        <Container maxWidth="lg">
          <Grid container spacing={6} alignItems="center">
            <Grid item xs={12} md={5}>
              <SectionLabel>02 — Protocol</SectionLabel>
              <Typography variant="h2" sx={{ fontSize: { xs: 28, md: 36 }, mb: 2 }}>
                Speaks the protocol you already ship.
              </Typography>
              <Typography variant="body1" color="text.secondary" sx={{ mb: 2 }}>
                Protocol XML over HTTPS with header-carried transaction metadata.
                Technical ACK/NACK is returned synchronously; business responses
                follow as separate messages, exactly as the specification demands.
              </Typography>
              <Typography variant="body1" color="text.secondary">
                Unknown operator codes self-register and are flagged for configuration,
                so a new peer never blocks the queue.
              </Typography>
            </Grid>
            <Grid item xs={12} md={7}>
              <Terminal title="npg → peer · technical receipt" code={ackSample} />
            </Grid>
          </Grid>
        </Container>
      </Box>

      <Container maxWidth="md" sx={{ py: { xs: 7, md: 10 } }} id="faq">
        <SectionLabel>03 — Questions</SectionLabel>
        <Typography variant="h2" sx={{ fontSize: { xs: 28, md: 36 }, mb: 3 }}>
          About number portability
        </Typography>
        {faqs.map(item => (
          <Accordion key={item.q} elevation={0} disableGutters
            sx={{ bgcolor: 'transparent', borderBottom: `1px solid ${brand.line}`, '&:before': { display: 'none' } }}>
            <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ px: 0 }}>
              <Typography variant="subtitle1">{item.q}</Typography>
            </AccordionSummary>
            <AccordionDetails sx={{ px: 0 }}>
              <Typography variant="body2" color="text.secondary">{item.a}</Typography>
            </AccordionDetails>
          </Accordion>
        ))}
      </Container>

      <SiteFooter />
    </Box>
  )
}
