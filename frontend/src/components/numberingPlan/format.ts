const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 })
const full = new Intl.NumberFormat('en')

/** "9.1M" — for tiles and bar labels, where the exact figure is in the tooltip. */
export const compactCount = (n: number) => compact.format(Number(n))
export const fullCount = (n: number) => full.format(Number(n))

const TYPE_LABELS: Record<string, string> = {
  FIXED_LINE: 'Fixed line', MOBILE: 'Mobile', VOIP: 'VoIP', TOLL_FREE: 'Toll free', PREMIUM_RATE: 'Premium rate',
  SHARED_COST: 'Shared cost', PERSONAL_NUMBER: 'Personal', PAGER: 'Pager', UAN: 'UAN', VOICEMAIL: 'Voicemail',
  FIXED_LINE_OR_MOBILE: 'Fixed or mobile', UNKNOWN: 'Not in libphonenumber'
}

/** libphonenumber's PhoneNumberType, for people. */
export const numberTypeLabel = (type: string | null | undefined) => (type ? TYPE_LABELS[type] ?? type : '—')
