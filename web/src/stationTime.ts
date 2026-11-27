const DAY_MS = 24 * 60 * 60 * 1000

export function createStationTime(timeZone: string) {
  const partsFormatter = new Intl.DateTimeFormat('en-GB', {
    timeZone,
    calendar: 'iso8601',
    numberingSystem: 'latn',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  })
  const displayFormatter = new Intl.DateTimeFormat('zh-CN', {
    timeZone, dateStyle: 'medium', timeStyle: 'short', hourCycle: 'h23',
  })

  function localFields(instant: Date) {
    const fields = Object.fromEntries(partsFormatter.formatToParts(instant).map(part => [part.type, part.value]))
    return `${fields.year.padStart(4, '0')}-${fields.month}-${fields.day}T${fields.hour}:${fields.minute}:${fields.second}`
  }

  function fromInput(value: string): string | null {
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return null
    const wallTime = `${value}:00`
    const nominal = new Date(`${wallTime}Z`)
    if (!Number.isFinite(nominal.getTime()) || !nominal.toISOString().startsWith(wallTime)) return null
    const instant = nominal.getTime()
    const matches = new Set<string>()
    // Check the offsets on both sides of a possible clock change, then round-trip each candidate.
    for (const probe of [instant - DAY_MS, instant, instant + DAY_MS]) {
      const offset = Date.parse(`${localFields(new Date(probe))}Z`) - probe
      const candidate = new Date(instant - offset)
      if (localFields(candidate) === wallTime) matches.add(candidate.toISOString())
    }
    return matches.size === 1 ? [...matches][0] : null
  }

  return {
    fromInput,
    toInput: (utc: string) => localFields(new Date(utc)).slice(0, 16),
    format: (utc: string) => displayFormatter.format(new Date(utc)),
    formatRange: (start: string, end: string) => displayFormatter.formatRange(new Date(start), new Date(end)),
  }
}
