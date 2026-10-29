import { Link as RouterLink } from 'react-router-dom'
import {
  AppBar, Box, Button, Card, CardContent, Chip, Container, Divider,
  Grid, Paper, Toolbar, Typography, Accordion, AccordionSummary, AccordionDetails
} from '@mui/material'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import ArrowForwardIcon from '@mui/icons-material/ArrowForward'

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

function CodePanel({ code }: { code: string }) {
  return (
    <Paper
      elevation={0}
      sx={{
        bgcolor: '#1B1B1F',
        color: '#E0E0FF',
        borderRadius: 3,
        p: 2.5,
        fontFamily: 'monospace',
        fontSize: 12.5,
        lineHeight: 1.7,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        overflowX: 'auto'
      }}
    >
      {code}
    </Paper>
  )
}

function FeatureCard({ title, children }: { title: string; children: string }) {
  return (
    <Card elevation={0} sx={{ border: 1, borderColor: 'divider', borderRadius: 3, height: '100%' }}>
      <CardContent>
        <Typography variant="subtitle1" sx={{ fontFamily: "'Archivo', sans-serif", fontWeight: 600, mb: 1 }}>
          {title}
        </Typography>
        <Typography variant="body2" color="text.secondary">{children}</Typography>
      </CardContent>
    </Card>
  )
}

const faqs = [
  {
    q: 'What is The NPG Project?',
    a: 'The NPG Project is a gateway that lets a licensed Israeli telecom operator exchange number-portability messages with peer operators: eligibility checks, porting requests, updates, cancellations, execution, publication, number return, inquiries, maintenance, and database synchronization.'
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
    <Box sx={{ bgcolor: '#FFF', minHeight: '100vh' }}>
      <AppBar position="static" elevation={0} sx={{ bgcolor: '#FFF', borderBottom: 1, borderColor: 'divider' }}>
        <Toolbar sx={{ maxWidth: 1200, width: '100%', mx: 'auto' }}>
          <Typography sx={{ fontFamily: "'Archivo', sans-serif", fontWeight: 700, fontSize: 24, color: 'primary.main', flexGrow: 1 }}>
            The NPG Project 
          </Typography>
          <Button color="inherit" sx={{ color: 'text.secondary', textTransform: 'none' }} href="#features">Features</Button>
          <Button color="inherit" sx={{ color: 'text.secondary', textTransform: 'none' }} href="#protocol">Protocol</Button>
          <Button color="inherit" sx={{ color: 'text.secondary', textTransform: 'none' }} href="#faq">FAQ</Button>
          <Button component={RouterLink} to="/login" variant="contained" sx={{ ml: 2, textTransform: 'none', borderRadius: 2 }}>
            Sign in
          </Button>
        </Toolbar>
      </AppBar>

      <Container maxWidth="lg" sx={{ pt: 8, pb: 4 }}>
        <Grid container spacing={6} alignItems="center">
          <Grid item xs={12} md={6}>
            <Chip label="Israeli Number Portability Gateway" size="small" variant="outlined"
              sx={{ color: 'primary.dark', borderColor: 'primary.light', mb: 2 }} />
            <Typography variant="h2" sx={{
              fontFamily: "'Archivo', sans-serif", fontWeight: 700,
              fontSize: { xs: 34, md: 46 }, lineHeight: 1.1, mb: 2, color: 'text.primary'
            }}>
              Every number, exactly where it belongs.
            </Typography>
            <Typography variant="body1" color="text.secondary" sx={{ mb: 3, maxWidth: 480 }}>
              The NPG Project is the portability exchange for licensed operators — requests, responses,
              executions, and publications over the Israeli NP protocol, with a durable
              audit trail for every message.
            </Typography>
            <Box sx={{ display: 'flex', gap: 2 }}>
              <Button component={RouterLink} to="/login" variant="contained" size="large"
                endIcon={<ArrowForwardIcon />} sx={{ textTransform: 'none', borderRadius: 2 }}>
                Sign in to the console
              </Button>
              <Button href="https://github.com/greenfieldtech-nirs/npg.cloudonix.cloud" target="_blank"
                variant="outlined" size="large" sx={{ textTransform: 'none', borderRadius: 2 }}>
                View on GitHub
              </Button>
            </Box>
          </Grid>
          <Grid item xs={12} md={6}>
            <CodePanel code={codeSample} />
            <Box sx={{ mt: 2 }}>
              <CodePanel code={ackSample} />
            </Box>
          </Grid>
        </Grid>
      </Container>

      <Container maxWidth="lg" sx={{ py: 6 }} id="features">
        <Typography variant="h5" sx={{ fontFamily: "'Archivo', sans-serif", fontWeight: 600, mb: 1 }}>
          One gateway in front of every portability message.
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3, maxWidth: 560 }}>
          Built for the operator that has to answer to the regulator, the NOC, and the audit team at once.
        </Typography>
        <Grid container spacing={2}>
          <Grid item xs={12} md={4}>
            <FeatureCard title="One gateway for every message">
              Eligibility checks, porting requests, updates, cancellations, execution,
              publication, number return, inquiries, maintenance, and database
              synchronization — one inbound endpoint, one outbound pipeline.
            </FeatureCard>
          </Grid>
          <Grid item xs={12} md={4}>
            <FeatureCard title="Durable by design">
              Every message lands in MariaDB before its ACK is returned — inbox/outbox
              queues with atomic claiming, durable timers, idempotent retries, and
              crash-safe recovery. No external broker required.
            </FeatureCard>
          </Grid>
          <Grid item xs={12} md={4}>
            <FeatureCard title="Every message, auditable">
              Raw XML is stored immutably in MongoDB with SHA-256 hashes, linked to
              workflow state and event history. Manual actions and XML views are
              audit-logged by design.
            </FeatureCard>
          </Grid>
        </Grid>
      </Container>

      <Box sx={{ bgcolor: 'grey.50', py: 6 }} id="protocol">
        <Container maxWidth="lg">
          <Grid container spacing={6} alignItems="center">
            <Grid item xs={12} md={5}>
              <Typography variant="h5" sx={{ fontFamily: "'Archivo', sans-serif", fontWeight: 600, mb: 2 }}>
                Ask in the language you already ship.
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                Protocol XML over HTTPS with header-carried transaction metadata.
                Technical ACK/NACK is returned synchronously; business responses
                follow as separate messages — exactly as the specification demands.
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Unknown operator codes self-register and are flagged for configuration,
                so a new peer never blocks the queue.
              </Typography>
            </Grid>
            <Grid item xs={12} md={7}>
              <CodePanel code={ackSample} />
            </Grid>
          </Grid>
        </Container>
      </Box>

      <Container maxWidth="lg" sx={{ py: 6 }} id="faq">
        <Typography variant="h5" sx={{ fontFamily: "'Archivo', sans-serif", fontWeight: 600, mb: 2 }}>
          Questions about number portability
        </Typography>
        {faqs.map(item => (
          <Accordion key={item.q} elevation={0} sx={{ borderBottom: 1, borderColor: 'divider' }}>
            <AccordionSummary expandIcon={<ExpandMoreIcon />}>
              <Typography variant="subtitle2">{item.q}</Typography>
            </AccordionSummary>
            <AccordionDetails>
              <Typography variant="body2" color="text.secondary">{item.a}</Typography>
            </AccordionDetails>
          </Accordion>
        ))}
      </Container>

      <Divider />
      <Container maxWidth="lg" sx={{ py: 3 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}>
          <Typography variant="caption" color="text.secondary">
            NPG Project — Number Portability Gateway · Dual licensed: GPL-3.0 (≤50k numbers) / Commercial
          </Typography>
          <Button component={RouterLink} to="/login" size="small" sx={{ textTransform: 'none' }}>
            Sign in
          </Button>
        </Box>
      </Container>
    </Box>
  )
}
