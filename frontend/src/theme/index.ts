// MUI theme built from the npg brand kit (see ./brand.ts), in light and dark modes
import { PaletteMode } from '@mui/material'
import { createTheme, PaletteOptions } from '@mui/material/styles'
// Registers MuiDataGrid theme keys (auto in x-data-grid v7, explicit here for TS)
import type {} from '@mui/x-data-grid/themeAugmentation'
import { brand, brandDark, MONO_FONT, SANS_FONT } from './brand'

declare module '@mui/material/styles' {
  interface PaletteColor {
    lighter?: string
    darker?: string
  }
  interface SimplePaletteColorOptions {
    lighter?: string
    darker?: string
  }
}

// Light: Paper surfaces, Ink text, Deep Signal accent.
const light: PaletteOptions = {
  mode: 'light',
  primary: { lighter: '#E3F1F5', light: '#5FA3B8', main: brand.deepSignal, dark: '#0B5D73', darker: '#083F4E', contrastText: '#FFFFFF' },
  secondary: { lighter: brand.paper, light: brand.ink2, main: brand.ink, dark: '#070C14', darker: '#070C14', contrastText: brand.paper },
  success: { lighter: '#E3F4E8', light: '#86CF9E', main: brand.ported.light, contrastText: '#FFFFFF' },
  warning: { lighter: '#FCF0E1', light: '#E9B27A', main: brand.pending.light, contrastText: '#FFFFFF' },
  error: { lighter: '#FCE9E7', light: '#EE9A92', main: brand.rejected.light, contrastText: '#FFFFFF' },
  info: { lighter: '#E3F1F5', light: '#5FA3B8', main: brand.deepSignal, contrastText: '#FFFFFF' },
  grey: { 50: brand.paper, 100: '#EBEFF4', 200: brand.line, 300: '#C5CEDA', 400: brand.mist },
  text: { primary: brand.ink, secondary: brand.slate, disabled: brand.mist },
  divider: brand.line,
  background: { default: brand.paper, paper: '#FFFFFF' }
}

// Dark: the kit's dark surfaces (Ink, Ink 2), Paper text, Signal accent, dark status colors.
const dark: PaletteOptions = {
  mode: 'dark',
  primary: { lighter: 'rgba(34, 211, 238, 0.14)', light: '#67E8F9', main: brand.signal, dark: brand.deepSignal, darker: '#A5F3FC', contrastText: brand.ink },
  secondary: { lighter: brand.ink2, light: brandDark.raised, main: brand.paper, dark: brand.mist, darker: brand.mist, contrastText: brand.ink },
  success: { lighter: 'rgba(74, 222, 128, 0.14)', light: '#86EFAC', main: brand.ported.dark, contrastText: brand.ink },
  warning: { lighter: 'rgba(251, 191, 36, 0.14)', light: '#FCD34D', main: brand.pending.dark, contrastText: brand.ink },
  error: { lighter: 'rgba(248, 113, 113, 0.14)', light: '#FCA5A5', main: brand.rejected.dark, contrastText: brand.ink },
  info: { lighter: 'rgba(34, 211, 238, 0.14)', light: '#67E8F9', main: brand.signal, contrastText: brand.ink },
  grey: { 50: brand.ink, 100: brandDark.raised, 200: brandDark.line, 300: brand.slate, 400: brand.mist },
  text: { primary: brand.paper, secondary: brand.mist, disabled: brand.slate },
  divider: brandDark.line,
  background: { default: brand.ink, paper: brand.ink2 }
}

export function createAppTheme(mode: PaletteMode) {
  const isDark = mode === 'dark'
  return createTheme({
    palette: isDark ? dark : light,
    typography: {
      // Inter does the reading; JetBrains Mono carries the voice (display, wordmark, labels, code)
      fontFamily: SANS_FONT,
      h1: { fontFamily: MONO_FONT, fontWeight: 700, fontSize: 56, lineHeight: '62px', letterSpacing: '-1px' },
      h2: { fontWeight: 700, fontSize: 36, lineHeight: '44px', letterSpacing: '-0.5px' },
      h3: { fontWeight: 700, fontSize: 28, lineHeight: '36px', letterSpacing: '-0.3px' },
      h4: { fontWeight: 700, fontSize: 24, lineHeight: '32px', letterSpacing: '-0.3px' },
      h5: { fontWeight: 600, fontSize: 22, lineHeight: '30px' },
      h6: { fontWeight: 600, fontSize: 18, lineHeight: '26px' },
      subtitle1: { fontWeight: 600, fontSize: 16, lineHeight: '24px' },
      subtitle2: { fontWeight: 600, fontSize: 14, lineHeight: '20px' },
      body1: { fontWeight: 400, fontSize: 16, lineHeight: '26px' },
      body2: { fontWeight: 400, fontSize: 14, lineHeight: '22px' },
      caption: { fontWeight: 400, fontSize: 12, lineHeight: '18px' },
      // the brand "label": Mono 500 · 12 · +2px · caps
      overline: { fontFamily: MONO_FONT, fontWeight: 500, fontSize: 12, lineHeight: '18px', letterSpacing: '2px', textTransform: 'uppercase' },
      button: { textTransform: 'none', fontWeight: 600 }
    },
    shape: { borderRadius: 10 },
    components: {
      MuiCssBaseline: {
        styleOverrides: theme => ({
          // tabular figures on: counts, numbers and IDs line up
          body: { fontVariantNumeric: 'tabular-nums' },
          'code, kbd, pre, samp': { fontFamily: MONO_FONT },
          a: { color: theme.palette.primary.main }
        })
      },
      MuiCard: {
        defaultProps: { elevation: 0 },
        styleOverrides: {
          root: ({ theme }) => ({
            border: `1px solid ${theme.palette.divider}`,
            borderRadius: 14,
            boxShadow: 'none',
            backgroundImage: 'none',
            transition: 'box-shadow 200ms ease, border-color 200ms ease',
            '&:hover': { boxShadow: isDark ? '0px 6px 18px -6px rgba(0, 0, 0, 0.6)' : '0px 4px 14px -4px rgba(14, 23, 38, 0.12)' }
          })
        }
      },
      MuiPaper: {
        // MUI lightens dark papers with an overlay; the brand's Ink 2 is the raised surface already
        styleOverrides: { root: { backgroundImage: 'none' } }
      },
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: { root: { textTransform: 'none', borderRadius: 8, fontWeight: 600 } }
      },
      MuiChip: {
        styleOverrides: { root: { fontWeight: 500 } }
      },
      MuiAppBar: {
        defaultProps: { elevation: 0, color: 'inherit' },
        styleOverrides: {
          // the console header sits on Ink in both modes, like the brand's terminal tile
          root: ({ theme }) => ({
            backgroundColor: brand.ink, color: brand.paper, boxShadow: 'none',
            borderBottom: isDark ? `1px solid ${theme.palette.divider}` : 'none'
          })
        }
      },
      MuiDrawer: {
        styleOverrides: {
          paper: ({ theme }) => ({ backgroundColor: theme.palette.background.paper, borderRight: `1px solid ${theme.palette.divider}` })
        }
      },
      MuiLinearProgress: {
        styleOverrides: { bar: { backgroundColor: brand.signal } }
      },
      MuiDataGrid: {
        styleOverrides: {
          root: ({ theme }) => ({
            backgroundColor: theme.palette.background.paper, border: `1px solid ${theme.palette.divider}`, borderRadius: 12,
            '--DataGrid-containerBackground': isDark ? brand.ink : brand.paper
          }),
          columnHeaders: ({ theme }) => ({ borderBottom: `1px solid ${theme.palette.divider}` }),
          // grid cells centre text through line-height, which leaves badges (and boxes holding them) at
          // the top of the row; cells holding a badge centre their content instead. Plain-text cells keep
          // block layout so long values still end in an ellipsis.
          cell: {
            '&:has(.MuiChip-root)': { display: 'flex', alignItems: 'center' }
          },
          columnHeaderTitle: { fontWeight: 600 }
        }
      },
      MuiTableCell: {
        styleOverrides: {
          root: ({ theme }) => ({ borderColor: theme.palette.divider }),
          head: ({ theme }) => ({ fontWeight: 600, color: theme.palette.text.secondary })
        }
      },
      MuiTooltip: {
        styleOverrides: { tooltip: { backgroundColor: isDark ? brandDark.raised : brand.ink2, color: brand.paper } }
      }
    }
  })
}
