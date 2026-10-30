// MUI theme built from the npg brand kit (see ./brand.ts)
import { createTheme } from '@mui/material/styles'
// Registers MuiDataGrid theme keys (auto in x-data-grid v7, explicit here for TS)
import type {} from '@mui/x-data-grid/themeAugmentation'
import { brand, MONO_FONT, SANS_FONT } from './brand'

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

const theme = createTheme({
  palette: {
    // Deep Signal: links, focus, selection and primary actions on light surfaces
    primary: { lighter: '#E3F1F5', light: '#5FA3B8', main: brand.deepSignal, dark: '#0B5D73', darker: '#083F4E', contrastText: '#FFFFFF' },
    // Ink: the brand's primary surface
    secondary: { lighter: brand.paper, light: brand.ink2, main: brand.ink, dark: '#070C14', darker: '#070C14', contrastText: brand.paper },
    success: { lighter: '#E3F4E8', light: '#86CF9E', main: brand.ported.light, contrastText: '#FFFFFF' },
    warning: { lighter: '#FCF0E1', light: '#E9B27A', main: brand.pending.light, contrastText: '#FFFFFF' },
    error: { lighter: '#FCE9E7', light: '#EE9A92', main: brand.rejected.light, contrastText: '#FFFFFF' },
    info: { lighter: '#E3F1F5', light: '#5FA3B8', main: brand.deepSignal, contrastText: '#FFFFFF' },
    grey: { 50: brand.paper, 100: '#EBEFF4', 200: brand.line, 300: '#C5CEDA', 400: brand.mist },
    text: { primary: brand.ink, secondary: brand.slate, disabled: brand.mist },
    divider: brand.line,
    background: { default: brand.paper, paper: '#FFFFFF' }
  },
  typography: {
    // Inter does the reading; JetBrains Mono carries the voice (display, wordmark, code, labels)
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
      styleOverrides: {
        // tabular figures on: counts, numbers and IDs line up
        body: { fontVariantNumeric: 'tabular-nums' },
        'code, kbd, pre, samp': { fontFamily: MONO_FONT },
        a: { color: brand.deepSignal }
      }
    },
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: {
          border: `1px solid ${brand.line}`,
          borderRadius: 14,
          boxShadow: 'none',
          transition: 'box-shadow 200ms ease, border-color 200ms ease',
          '&:hover': { boxShadow: '0px 4px 14px -4px rgba(14, 23, 38, 0.12)' }
        }
      }
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
        // the console header sits on Ink, like the brand's terminal tile
        root: { backgroundColor: brand.ink, color: brand.paper, boxShadow: 'none' }
      }
    },
    MuiDrawer: {
      styleOverrides: {
        paper: { backgroundColor: '#FFFFFF', borderRight: `1px solid ${brand.line}` }
      }
    },
    MuiLinearProgress: {
      styleOverrides: { bar: { backgroundColor: brand.signal } }
    },
    MuiDataGrid: {
      styleOverrides: {
        root: { backgroundColor: '#FFFFFF', border: `1px solid ${brand.line}`, borderRadius: 12 },
        columnHeaders: { backgroundColor: brand.paper, borderBottom: `1px solid ${brand.line}` },
        columnHeaderTitle: { fontWeight: 600 }
      }
    },
    MuiTableCell: {
      styleOverrides: {
        root: { borderColor: brand.line },
        head: { fontWeight: 600, color: brand.slate }
      }
    },
    MuiTooltip: {
      styleOverrides: { tooltip: { backgroundColor: brand.ink2, color: brand.paper } }
    }
  }
})

export default theme
