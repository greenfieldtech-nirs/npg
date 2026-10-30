import { ReactNode, useEffect, useMemo, useState } from 'react'
import { CssBaseline, ThemeProvider, useMediaQuery } from '@mui/material'
import { createAppTheme } from '.'
import { brand } from './brand'
import { COLOR_MODE_KEY, ColorModeContext, ColorModePreference } from './colorMode'

function storedPreference(): ColorModePreference {
  try {
    const v = localStorage.getItem(COLOR_MODE_KEY)
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch {
    return 'system'
  }
}

/** Light or dark from the user's choice, else from the operating system; remembered per browser. */
export default function ColorModeProvider({ children }: { children: ReactNode }) {
  const prefersDark = useMediaQuery('(prefers-color-scheme: dark)', { noSsr: true })
  const [preference, setPreferenceState] = useState<ColorModePreference>(storedPreference)
  const mode = preference === 'system' ? (prefersDark ? 'dark' : 'light') : preference

  const state = useMemo(() => {
    const setPreference = (p: ColorModePreference) => {
      setPreferenceState(p)
      try {
        if (p === 'system') localStorage.removeItem(COLOR_MODE_KEY)
        else localStorage.setItem(COLOR_MODE_KEY, p)
      } catch {
        // private windows can refuse storage; the choice still applies to this visit
      }
    }
    return { mode, preference, setPreference, toggle: () => setPreference(mode === 'dark' ? 'light' : 'dark') }
  }, [mode, preference])

  const theme = useMemo(() => createAppTheme(mode), [mode])

  // native controls, scrollbars and the browser chrome follow the mode too
  useEffect(() => {
    document.documentElement.style.colorScheme = mode
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', brand.ink)
  }, [mode])

  return (
    <ColorModeContext.Provider value={state}>
      <ThemeProvider theme={theme}>
        <CssBaseline enableColorScheme />
        {children}
      </ThemeProvider>
    </ColorModeContext.Provider>
  )
}
