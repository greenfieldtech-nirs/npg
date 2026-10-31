import { ReactNode, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Box, IconButton, Stack, Tab, Tabs, Tooltip, Typography, keyframes } from '@mui/material'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import PauseIcon from '@mui/icons-material/Pause'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import OperationsSlide from './dashboard/OperationsSlide'
import NumberingPlanSlide from './dashboard/NumberingPlanSlide'
import PlatformHealthSlide from './dashboard/PlatformHealthSlide'

const SLIDE_SECONDS = 15
const AUTOPLAY_KEY = 'npg.dashboardAutoplay'

const SLIDES: { id: string; title: string; subtitle: string; render: () => ReactNode }[] = [
  { id: 'operations', title: 'Operations Dashboard', subtitle: 'Live gateway activity at a glance', render: () => <OperationsSlide /> },
  { id: 'numbering-plan', title: 'Israel Numbering Plan', subtitle: 'Who each number range was originally allocated to, before porting', render: () => <NumberingPlanSlide /> },
  { id: 'health', title: 'Platform Health', subtitle: 'Data stores, runtime roles and processing', render: () => <PlatformHealthSlide /> }
]

const enter = keyframes`
  from { opacity: 0; transform: translateX(16px); }
  to { opacity: 1; transform: translateX(0); }
`

function storedAutoplay(): boolean {
  try {
    return localStorage.getItem(AUTOPLAY_KEY) !== 'false'
  } catch {
    return true
  }
}

/**
 * The dashboard as a slide show: operations, the numbering plan and platform health. It advances on
 * its own every 15 seconds; pointing at or focusing a slide holds it, and the pause button stops it.
 * The current slide is in the URL (?slide=health), so a slide can be linked to directly.
 */
export default function Dashboard() {
  const [params, setParams] = useSearchParams()
  const found = SLIDES.findIndex(s => s.id === params.get('slide'))
  const index = found < 0 ? 0 : found
  const [playing, setPlaying] = useState(storedAutoplay)
  const [held, setHeld] = useState(false)

  const go = (i: number) => {
    const next = new URLSearchParams(params)
    next.set('slide', SLIDES[(i + SLIDES.length) % SLIDES.length].id)
    setParams(next, { replace: true })
  }

  useEffect(() => {
    if (!playing || held) return
    const timer = setTimeout(() => go(index + 1), SLIDE_SECONDS * 1000)
    return () => clearTimeout(timer)
  })

  const togglePlaying = () => setPlaying(p => {
    try {
      localStorage.setItem(AUTOPLAY_KEY, String(!p))
    } catch {
      // storage can be refused; the choice still applies to this visit
    }
    return !p
  })

  const slide = SLIDES[index]

  return (
    <Box>
      <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" alignItems={{ md: 'flex-end' }} spacing={2} sx={{ mb: 3 }}>
        <Box>
          <Typography variant="h5">{slide.title}</Typography>
          <Typography variant="caption" color="text.secondary">{slide.subtitle}</Typography>
        </Box>
        <Stack direction="row" alignItems="center" spacing={0.5}>
          <Tooltip title="Previous slide">
            <IconButton aria-label="Previous slide" onClick={() => go(index - 1)} size="small"><ChevronLeftIcon /></IconButton>
          </Tooltip>
          <Tabs value={index} onChange={(_e, i: number) => go(i)} aria-label="Dashboard slides"
            variant="scrollable" scrollButtons={false} sx={{ minHeight: 40, '& .MuiTab-root': { minHeight: 40, textTransform: 'none' } }}>
            {SLIDES.map((s, i) => <Tab key={s.id} value={i} label={s.title.replace(' Dashboard', '')} />)}
          </Tabs>
          <Tooltip title="Next slide">
            <IconButton aria-label="Next slide" onClick={() => go(index + 1)} size="small"><ChevronRightIcon /></IconButton>
          </Tooltip>
          <Tooltip title={playing ? 'Pause the slide show' : 'Play the slide show'}>
            <IconButton aria-label={playing ? 'Pause the slide show' : 'Play the slide show'} aria-pressed={!playing}
              onClick={togglePlaying} size="small" color="primary">
              {playing ? <PauseIcon /> : <PlayArrowIcon />}
            </IconButton>
          </Tooltip>
        </Stack>
      </Stack>

      <Box
        role="region"
        aria-roledescription="carousel"
        aria-label="Dashboard"
        // pointing at or working in a slide holds it, so it never moves under the user
        onMouseEnter={() => setHeld(true)}
        onMouseLeave={() => setHeld(false)}
        onFocus={() => setHeld(true)}
        onBlur={() => setHeld(false)}
      >
        <Box
          key={slide.id}
          role="group"
          aria-roledescription="slide"
          aria-label={`${index + 1} of ${SLIDES.length}: ${slide.title}`}
          sx={{ animation: `${enter} 300ms ease both`, '@media (prefers-reduced-motion: reduce)': { animation: 'none' } }}
        >
          {slide.render()}
        </Box>
      </Box>
    </Box>
  )
}
