// Google Analytics (GA4) for the public site only, after consent. The measurement id comes from the
// container environment via /runtime-config.js, never from the source. The console never loads GA:
// signing in does a full page load, and GA is switched off whenever a console page is shown.

declare global {
  interface Window {
    __NPG_CONFIG__?: { gaMeasurementId?: string }
    dataLayer?: unknown[]
    gtag?: (...args: unknown[]) => void
    [disableFlag: `ga-disable-${string}`]: boolean | undefined
  }
}

export type Consent = 'granted' | 'denied' | null

const CONSENT_KEY = 'npg.analyticsConsent'
const PUBLIC_PATHS = ['/landing', '/features', '/compatibility', '/login']

/** The configured GA4 measurement id, or null when analytics is off. */
export function measurementId(): string | null {
  const id = window.__NPG_CONFIG__?.gaMeasurementId ?? ''
  return /^G-[A-Z0-9]{4,20}$/.test(id) ? id : null
}

export const isPublicPath = (pathname: string) => PUBLIC_PATHS.includes(pathname)

export function storedConsent(): Consent {
  try {
    const v = localStorage.getItem(CONSENT_KEY)
    return v === 'granted' || v === 'denied' ? v : null
  } catch {
    return null
  }
}

export function storeConsent(consent: Consent) {
  try {
    if (consent) localStorage.setItem(CONSENT_KEY, consent)
    else localStorage.removeItem(CONSENT_KEY)
  } catch {
    // storage can be refused: the choice applies to this visit only
  }
}

/** True once gtag.js has been loaded in this page. */
export const isLoaded = () => typeof window.gtag === 'function'

function load(id: string) {
  if (isLoaded()) return
  window.dataLayer = window.dataLayer ?? []
  // gtag must push the arguments object itself
  // eslint-disable-next-line prefer-rest-params
  window.gtag = function gtag() { window.dataLayer!.push(arguments) }
  window.gtag('js', new Date())
  // page views are sent explicitly, with the path only (no query string or fragment)
  window.gtag('config', id, { send_page_view: false })
  const script = document.createElement('script')
  script.async = true
  script.src = `https://www.googletagmanager.com/gtag/js?id=${encodeURIComponent(id)}`
  document.head.appendChild(script)
}

/** Reports a public page view (when configured and consented); switches GA off anywhere else. */
export function trackPage(pathname: string) {
  const id = measurementId()
  if (!id) return
  const allowed = isPublicPath(pathname) && storedConsent() === 'granted'
  window[`ga-disable-${id}`] = !allowed
  if (!allowed) return
  load(id)
  window.gtag!('event', 'page_view', {
    page_path: pathname,
    page_location: window.location.origin + pathname,
    page_title: document.title
  })
}

/** Withdrawal: stop GA in this page and remove its cookies. */
export function withdraw() {
  const id = measurementId()
  if (id) window[`ga-disable-${id}`] = true
  for (const cookie of document.cookie.split(';')) {
    const name = cookie.split('=')[0].trim()
    if (name === '_ga' || name.startsWith('_ga_')) {
      const domain = window.location.hostname.split('.').slice(-2).join('.')
      document.cookie = `${name}=; Max-Age=0; path=/`
      document.cookie = `${name}=; Max-Age=0; path=/; domain=.${domain}`
    }
  }
}

/** Event the footer's "Cookie settings" link sends to reopen the consent banner. */
export const OPEN_CONSENT_EVENT = 'npg:open-analytics-consent'
