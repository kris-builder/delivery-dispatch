export const BEIJING_ZONE = 'Asia/Shanghai'

export function createStationTime() {
  const partsFormatter = new Intl.DateTimeFormat('en-GB', {
    timeZone: BEIJING_ZONE,
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
    timeZone: BEIJING_ZONE, dateStyle: 'medium', timeStyle: 'short', hourCycle: 'h23',
  })

  function localFields(instant: Date) {
    const fields = Object.fromEntries(partsFormatter.formatToParts(instant).map(part => [part.type, part.value]))
    return `${fields.year.padStart(4, '0')}-${fields.month}-${fields.day}T${fields.hour}:${fields.minute}:${fields.second}`
  }

  function fromInput(value: string): string | null {
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return null
    const wallTime = `${value}:00`
    const timestamp = `${wallTime}+08:00`
    const instant = new Date(timestamp)
    if (!Number.isFinite(instant.getTime()) || localFields(instant) !== wallTime) return null
    return timestamp
  }

  return {
    fromInput,
    toInput: (timestamp: string) => localFields(new Date(timestamp)).slice(0, 16),
    format: (timestamp: string) => displayFormatter.format(new Date(timestamp)),
    formatRange: (start: string, end: string) => displayFormatter.formatRange(new Date(start), new Date(end)),
  }
}
