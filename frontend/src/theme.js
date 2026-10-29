// Single palette owner for existing semantic CSS variables and Naive UI.
export const palettes = {
  "light": {
    "surface": "248 250 252",
    "surface-raised": "255 255 255",
    "surface-overlay": "255 255 255",
    "surface-field": "248 250 252",
    "surface-hover": "241 245 249",
    "divider": "226 232 240",
    "divider-light": "241 245 249",
    "divider-hover": "199 210 254",
    "content": "15 23 42",
    "content-secondary": "100 116 139",
    "content-muted": "148 163 184",
    "brand": "79 70 229",
    "brand-hover": "67 56 202",
    "brand-text": "67 56 202",
    "brand-subtle": "238 242 255"
  },
  "dark": {
    "surface": "2 6 23",
    "surface-raised": "15 23 42",
    "surface-overlay": "2 6 23",
    "surface-field": "30 41 59",
    "surface-hover": "30 41 59",
    "divider": "51 65 85",
    "divider-light": "30 41 59",
    "divider-hover": "55 48 163",
    "content": "241 245 249",
    "content-secondary": "148 163 184",
    "content-muted": "100 116 139",
    "brand": "99 102 241",
    "brand-hover": "129 140 248",
    "brand-text": "129 140 248",
    "brand-subtle": "49 46 129"
  }
}

export function createThemeOverrides(isDark) {
  const palette = palettes[isDark ? 'dark' : 'light']
  const color = name => `rgb(${palette[name].split(' ').join(', ')})`
  return {
    common: {
      primaryColor: color('brand'), primaryColorHover: color('brand-hover'),
      primaryColorPressed: color('brand-hover'), primaryColorSuppl: color('brand'),
      bodyColor: color('surface'), cardColor: color('surface-raised'),
      modalColor: color('surface-raised'), popoverColor: color('surface-raised'),
      tableColor: color('surface-raised'), inputColor: color('surface-field'),
      textColorBase: color('content'), textColor1: color('content'),
      textColor2: color('content-secondary'), textColor3: color('content-muted'),
      borderColor: color('divider'), dividerColor: color('divider'),
      borderRadius: '8px', fontSize: '14px', fontWeightStrong: '600',
      fontFamily: 'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'
    },
    Card: { borderRadius: '12px' }
  }
}

export function applyTheme(document, isDark) {
  if (!document) return
  const root = document.documentElement
  for (const [name, value] of Object.entries(palettes[isDark ? 'dark' : 'light'])) {
    root.style.setProperty(`--color-${name}`, value)
  }
  root.classList.toggle('dark', isDark)
}
