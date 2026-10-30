import { createContext, useContext } from 'react'
import { PaletteMode } from '@mui/material'

/** 'system' follows the operating system's preference. */
export type ColorModePreference = PaletteMode | 'system'

export interface ColorModeState {
  mode: PaletteMode
  preference: ColorModePreference
  setPreference: (p: ColorModePreference) => void
  toggle: () => void
}

export const ColorModeContext = createContext<ColorModeState>({
  mode: 'light', preference: 'system', setPreference: () => {}, toggle: () => {}
})

export const useColorMode = () => useContext(ColorModeContext)

export const COLOR_MODE_KEY = 'npg.colorMode'
