// npg brand kit — "Ink, Paper, Signal". Signal is for dark surfaces only; on light, use Deep Signal.
export const brand = {
  ink: '#0E1726', // primary surface, text on light, the tile
  ink2: '#1B2638', // raised panels, code blocks
  paper: '#F4F6F9', // light surface, text on Ink
  slate: '#4A5568', // secondary text on light
  mist: '#9AA8BC', // secondary text on dark
  line: '#DDE3EA', // borders, dividers on light
  signal: '#22D3EE', // accent on dark only — never text on light
  deepSignal: '#0E7490', // accent, links, focus on light
  // port status; always paired with a glyph or label
  ported: { light: '#15803D', dark: '#4ADE80' },
  pending: { light: '#B45309', dark: '#FBBF24' },
  rejected: { light: '#B42318', dark: '#F87171' }
} as const

// Dark-mode surfaces between Ink 2 and Slate: borders and raised layers on dark backgrounds
export const brandDark = {
  line: '#2B3A50', // borders, dividers on Ink / Ink 2
  raised: '#24324A' // hover and raised layers on Ink 2
} as const

export const MONO_FONT = "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, monospace"
export const SANS_FONT = "'Inter', system-ui, -apple-system, 'Segoe UI', sans-serif"
