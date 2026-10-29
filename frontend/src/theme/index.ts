// Design tokens adapted from SaaSable UI (https://github.com/phoenixcoded/saasable-ui), MIT License © Phoenixcoded
import { createTheme } from '@mui/material/styles'
// Registers MuiDataGrid theme keys (auto in x-data-grid v7, explicit here for TS)
import type {} from '@mui/x-data-grid/themeAugmentation'

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

const HEADING_FONT = "'Archivo', 'Helvetica Neue', Arial, sans-serif"
const BODY_FONT = "'Figtree', 'Helvetica Neue', Arial, sans-serif"

const theme = createTheme({
  palette: {
    primary: {
      lighter: '#E0E0FF',
      light: '#BDC2FF',
      main: '#606BDF',
      dark: '#3944B8',
      darker: '#000668'
    },
    secondary: {
      lighter: '#E0E0FF',
      light: '#C3C4E4',
      main: '#5A5C78',
      dark: '#43455F',
      darker: '#171A31'
    },
    error: { lighter: '#FFEDEA', light: '#FFDAD6', main: '#DE3730' },
    warning: { lighter: '#FFEEE1', light: '#FFDCBE', main: '#AE6600' },
    success: { lighter: '#C8FFC0', light: '#B6F2AF', main: '#22892F' },
    info: { lighter: '#D4F7FF', light: '#A1EFFF', main: '#008394' },
    grey: {
      50: '#FBF8FF',
      100: '#F5F2FA',
      200: '#EFEDF4',
      300: '#EAE7EF',
      400: '#E4E1E6'
    },
    text: {
      primary: '#1B1B1F',
      secondary: '#46464F',
      disabled: '#777680'
    },
    divider: '#EFEDF4',
    background: {
      default: '#FBF8FF',
      paper: '#FFFFFF'
    }
  },
  typography: {
    fontFamily: BODY_FONT,
    h1: { fontFamily: HEADING_FONT, fontWeight: 500, fontSize: 40, lineHeight: '44px' },
    h2: { fontFamily: HEADING_FONT, fontWeight: 500, fontSize: 32, lineHeight: '36px' },
    h3: { fontFamily: HEADING_FONT, fontWeight: 500, fontSize: 28, lineHeight: '32px' },
    h4: { fontFamily: HEADING_FONT, fontWeight: 500, fontSize: 24, lineHeight: '28px' },
    h5: { fontFamily: HEADING_FONT, fontWeight: 500, fontSize: 20, lineHeight: '24px' },
    h6: { fontFamily: HEADING_FONT, fontWeight: 500, fontSize: 18, lineHeight: '22px' },
    subtitle1: { fontWeight: 500, fontSize: 16, lineHeight: '20px' },
    subtitle2: { fontWeight: 500, fontSize: 14, lineHeight: '18px' },
    body1: { fontWeight: 400, fontSize: 16, lineHeight: '24px' },
    body2: { fontWeight: 400, fontSize: 14, lineHeight: '20px' },
    caption: { fontWeight: 400, fontSize: 12, lineHeight: '16px' },
    button: { textTransform: 'none', fontWeight: 500 }
  },
  shape: { borderRadius: 12 },
  components: {
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: ({ theme }) => ({
          border: `1px solid ${theme.palette.divider}`,
          borderRadius: 12,
          boxShadow: '0px 1px 2px 0px rgba(27, 27, 31, 0.07)',
          transition: 'box-shadow 200ms ease, transform 200ms ease',
          '&:hover': {
            boxShadow: '0px 4px 12px -2px rgba(96, 107, 223, 0.15)',
            transform: 'translateY(-1px)'
          }
        })
      }
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: {
          textTransform: 'none',
          borderRadius: 8,
          fontWeight: 500
        }
      }
    },
    MuiAppBar: {
      defaultProps: { elevation: 0, color: 'inherit' },
      styleOverrides: {
        root: ({ theme }) => ({
          backgroundColor: '#FFFFFF',
          color: theme.palette.text.primary,
          borderBottom: `1px solid ${theme.palette.divider}`,
          boxShadow: 'none'
        })
      }
    },
    MuiDrawer: {
      styleOverrides: {
        paper: ({ theme }) => ({
          backgroundColor: '#FFFFFF',
          borderRight: `1px solid ${theme.palette.divider}`
        })
      }
    },
    MuiDataGrid: {
      styleOverrides: {
        root: ({ theme }) => ({
          backgroundColor: '#FFFFFF',
          border: `1px solid ${theme.palette.divider}`,
          borderRadius: 12
        }),
        columnHeaders: ({ theme }) => ({
          backgroundColor: theme.palette.grey[50],
          borderBottom: `1px solid ${theme.palette.divider}`
        })
      }
    },
    MuiTableCell: {
      styleOverrides: {
        root: ({ theme }) => ({
          borderColor: theme.palette.divider
        })
      }
    }
  }
})

export default theme
