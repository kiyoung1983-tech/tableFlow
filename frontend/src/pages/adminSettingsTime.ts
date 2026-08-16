export function zonedLocalToInstant(value: string, timezone: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value)
  if (!match) throw new Error('현지 날짜와 시간을 확인해 주세요.')
  const desired = match.slice(1).map(Number)
  const targetUtc = Date.UTC(desired[0], desired[1] - 1, desired[2], desired[3], desired[4])
  const sampleHours = [-36, -24, -12, 0, 12, 24, 36]
  const possibleOffsets = new Set(sampleHours.map((hours) => timezoneOffsetMinutes(
    new Date(targetUtc + hours * 60 * 60 * 1000),
    timezone,
  )))
  const candidates = [...possibleOffsets]
    .map((offsetMinutes) => targetUtc - offsetMinutes * 60 * 1000)
    .filter((candidate, index, values) => values.indexOf(candidate) === index)
    .filter((candidate) => {
      const displayed = zonedParts(new Date(candidate), timezone)
      return [displayed.year, displayed.month, displayed.day, displayed.hour, displayed.minute]
        .every((part, index) => part === desired[index])
    })
  if (candidates.length === 0) {
    throw new Error(`${timezone}에 존재하지 않는 현지 시각입니다.`)
  }
  if (candidates.length > 1) {
    throw new Error(`${timezone}에서 두 번 발생하는 모호한 현지 시각입니다.`)
  }
  return new Date(candidates[0]).toISOString()
}

function timezoneOffsetMinutes(date: Date, timezone: string) {
  const displayed = zonedParts(date, timezone)
  return (Date.UTC(
    displayed.year,
    displayed.month - 1,
    displayed.day,
    displayed.hour,
    displayed.minute,
  ) - date.getTime()) / (60 * 1000)
}

function zonedParts(date: Date, timezone: string) {
  const parts = new Intl.DateTimeFormat('en-US', {
    day: '2-digit',
    hour: '2-digit',
    hourCycle: 'h23',
    minute: '2-digit',
    month: '2-digit',
    timeZone: timezone,
    year: 'numeric',
  }).formatToParts(date)
  const values = Object.fromEntries(parts.map((part) => [part.type, Number(part.value)]))
  return {
    year: values.year,
    month: values.month,
    day: values.day,
    hour: values.hour,
    minute: values.minute,
  }
}
