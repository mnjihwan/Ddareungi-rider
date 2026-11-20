import { createContext, useContext } from 'react';

const light = {
  surface: '#FFFFFF', raised: '#F4F7FA', selected: '#EBEFF5', surfaceChip: '#EEF1F4',
  text: '#252F3E', muted: '#52657C', quiet: '#8A9099', nav: '#7B8087',
  accent: '#2563A6', blue: '#395780', navy: '#203C60',
  border: '#E2E5E9', field: '#CDD7E5', handle: '#D3D8E0',
  error: '#B42318', warning: '#B28639', warningSurface: '#FAF5EB',
  negative: '#B56D5A', negativeSurface: '#FBF0EC',
};

const dark: typeof light = {
  surface: '#303034', raised: '#39393E', selected: '#45454C', surfaceChip: '#39393E',
  text: '#F0F0F2', muted: '#C4C4CC', quiet: '#C4C4CC', nav: '#C4C4CC',
  accent: '#80B7FF', blue: '#8FBFFF', navy: '#203C60',
  border: '#55555D', field: '#62626B', handle: '#686871',
  error: '#F1A599', warning: '#E6C17C', warningSurface: '#382F22',
  negative: '#F1A599', negativeSurface: '#3B292B',
};

export const RIDER_THEME_KEY = 'ddareungrider:mobile-theme:v1';
export const riderThemes = { light, dark };
export const RiderTheme = createContext(light);
export const useRiderTheme = () => useContext(RiderTheme);
