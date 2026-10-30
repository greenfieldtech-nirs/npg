import { Box, SxProps, Theme, useTheme } from '@mui/material'
import { brand, MONO_FONT } from '../../theme/brand'

/** Where the mark sits: Ink tile on light surfaces, Paper tile on dark ones. */
export type MarkSurface = 'light' | 'dark'

/** An explicit surface (the Ink header, hero, footer) wins; otherwise follow the color mode. */
function useSurface(surface?: MarkSurface): MarkSurface {
  const mode = useTheme().palette.mode
  return surface ?? mode
}

/**
 * The npg terminal tile, drawn from the brand kit's artwork — never retyped.
 * ≥ 64px: full mark with the signal arrow; 40–63px: simplified tile; < 40px: the >_ prompt glyph.
 */
export function NpgMark({ size = 64, surface: requested, title }: { size?: number; surface?: MarkSurface; title?: string }) {
  const surface = useSurface(requested)
  const tile = surface === 'light' ? brand.ink : brand.paper
  const text = surface === 'light' ? brand.paper : brand.ink
  const signal = surface === 'light' ? brand.signal : brand.deepSignal
  const a11y = title ? { role: 'img', 'aria-label': title } : { 'aria-hidden': true }

  return (
    <svg viewBox="0 0 120 120" width={size} height={size} fill="none" strokeLinecap="round" strokeLinejoin="round"
      style={{ flexShrink: 0, display: 'block' }} {...a11y}>
      <rect width="120" height="120" rx="28" fill={tile} />
      {size >= 64 ? (
        <>
          <text x="60" y="62" textAnchor="middle" fontFamily={MONO_FONT} fontWeight="700" fontSize="40" fill={text}>npg</text>
          <path d="M28 84 H86" stroke={signal} strokeWidth="7" />
          <path d="M78 76 L87 84 L78 92" stroke={signal} strokeWidth="7" />
        </>
      ) : size >= 40 ? (
        <>
          <text x="60" y="64" textAnchor="middle" fontFamily={MONO_FONT} fontWeight="700" fontSize="42" fill={text}>npg</text>
          <path d="M26 88 H90" stroke={signal} strokeWidth="10" />
        </>
      ) : (
        <>
          <path d="M36 36 L60 60 L36 84" stroke={text} strokeWidth={size <= 20 ? 18 : 16} />
          <path d="M68 86 H90" stroke={signal} strokeWidth={size <= 20 ? 18 : 16} />
        </>
      )}
    </svg>
  )
}

/** The wordmark: lowercase npg with a blinking-cursor underscore. NPG in prose, npg for the mark. */
export function NpgWordmark({ size = 24, surface: requested, sx }: { size?: number; surface?: MarkSurface; sx?: SxProps<Theme> }) {
  const surface = useSurface(requested)
  return (
    <Box component="span" sx={{
      fontFamily: MONO_FONT, fontWeight: 700, fontSize: size, lineHeight: 1, letterSpacing: 0,
      color: surface === 'light' ? brand.ink : brand.paper, ...sx
    }}>
      npg<Box component="span" sx={{ color: surface === 'light' ? brand.deepSignal : brand.signal }}>_</Box>
    </Box>
  )
}

/** Horizontal lockup: tile, wordmark, and optionally the descriptor. */
export function NpgLockup({ markSize = 40, wordSize = 24, surface: requested, descriptor = false }: {
  markSize?: number; wordSize?: number; surface?: MarkSurface; descriptor?: boolean
}) {
  const surface = useSurface(requested)
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
      <NpgMark size={markSize} surface={surface} />
      <NpgWordmark size={wordSize} surface={surface} />
      {descriptor && (
        <>
          <Box sx={{ width: '1px', alignSelf: 'stretch', my: 0.5, bgcolor: surface === 'light' ? '#C5CEDA' : brand.slate }} />
          <Box sx={{
            fontFamily: MONO_FONT, fontWeight: 500, fontSize: Math.max(10, Math.round(wordSize * 0.36)), lineHeight: 1.4,
            letterSpacing: '1px', textTransform: 'uppercase', color: surface === 'light' ? brand.slate : brand.mist
          }}>
            Number Portability<br />Gateway
          </Box>
        </>
      )}
    </Box>
  )
}
