import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { type FormEvent, useState } from 'react'
import { listAdminBranches, listAdminRestaurants } from '../api/adminReservationApi'
import {
  createBookingBlock,
  createBranch,
  createDiningTable,
  createRestaurant,
  deleteBookingBlock,
  getBusinessHours,
  listBookingBlocks,
  listDiningTables,
  replaceBusinessHours,
  updateBranch,
  updateDiningTable,
  updateRestaurant,
} from '../api/adminSettingsApi'
import { ApiError } from '../api/client'
import type {
  Branch,
  BusinessHour,
  DiningTable,
  Restaurant,
  UpdateBranchRequest,
} from '../api/types'
import {
  AdminLoginPanel,
  AdminSessionHeader,
} from '../auth/AdminAccess'
import { useAdminAuth } from '../auth/adminAuthContext'
import { useAdminSession } from '../auth/adminSession'
import { zonedLocalToInstant } from './adminSettingsTime'

const DAYS = [
  [1, '월요일'],
  [2, '화요일'],
  [3, '수요일'],
  [4, '목요일'],
  [5, '금요일'],
  [6, '토요일'],
  [7, '일요일'],
] as const

const POLICY_FIELDS: Array<{
  key: Exclude<keyof UpdateBranchRequest, 'name' | 'address' | 'timezone' | 'status' | 'expectedVersion'>
  label: string
  min: number
  max: number
}> = [
  { key: 'slotIntervalMinutes', label: '예약 간격(분)', min: 5, max: 120 },
  { key: 'defaultDurationMinutes', label: '기본 이용시간(분)', min: 15, max: 720 },
  { key: 'bufferMinutes', label: '정리시간(분)', min: 0, max: 240 },
  { key: 'minAdvanceMinutes', label: '최소 사전예약(분)', min: 0, max: 10080 },
  { key: 'bookingHorizonDays', label: '예약 가능 기간(일)', min: 1, max: 365 },
  { key: 'changeCutoffMinutes', label: '고객 변경·취소 마감(분)', min: 0, max: 10080 },
  { key: 'noShowGraceMinutes', label: '노쇼 유예(분)', min: 0, max: 1440 },
  { key: 'maxPartySize', label: '최대 예약 인원', min: 1, max: 100 },
  { key: 'maxCapacityGap', label: '테이블 여유 좌석 한도', min: 0, max: 100 },
]

export function AdminSettingsPage() {
  const queryClient = useQueryClient()
  const auth = useAdminAuth()
  const session = useAdminSession()
  const [restaurantId, setRestaurantId] = useState('')
  const [branchId, setBranchId] = useState('')
  const [newRestaurantName, setNewRestaurantName] = useState('')
  const [newBranch, setNewBranch] = useState({ name: '', address: '', timezone: 'Asia/Seoul' })
  const [newTable, setNewTable] = useState({ name: '', capacity: '2' })
  const [blockDate, setBlockDate] = useState(todayInTimezone('Asia/Seoul'))
  const [armedBlockId, setArmedBlockId] = useState('')

  const restaurantsQuery = useQuery({
    queryKey: ['admin', 'restaurants', session?.id],
    queryFn: () => listAdminRestaurants(session!.authorization),
    enabled: session !== undefined,
    retry: false,
  })
  const branchesQuery = useQuery({
    queryKey: ['admin', 'branches', session?.id, restaurantId],
    queryFn: () => listAdminBranches(restaurantId, session!.authorization),
    enabled: session !== undefined && restaurantId !== '',
    retry: false,
  })
  const selectedRestaurant = restaurantsQuery.data?.find((item) => item.id === restaurantId)
  const selectedBranch = branchesQuery.data?.find((item) => item.id === branchId)
  const tablesQuery = useQuery({
    queryKey: ['admin', 'tables', session?.id, branchId],
    queryFn: () => listDiningTables(branchId, session!.authorization),
    enabled: session !== undefined && branchId !== '',
    retry: false,
  })
  const hoursQuery = useQuery({
    queryKey: ['admin', 'business-hours', session?.id, branchId],
    queryFn: () => getBusinessHours(branchId, session!.authorization),
    enabled: session !== undefined && branchId !== '',
    retry: false,
  })
  const blockWindow = selectedBranch && blockDate
    ? zonedDayWindow(blockDate, selectedBranch.timezone)
    : undefined
  const blocksQuery = useQuery({
    queryKey: ['admin', 'booking-blocks', session?.id, branchId, blockDate],
    queryFn: () => listBookingBlocks(
      branchId,
      blockWindow!.startsAt,
      blockWindow!.endsAt,
      session!.authorization,
    ),
    enabled: session !== undefined && branchId !== '' && blockWindow !== undefined,
    retry: false,
  })

  const createRestaurantMutation = useMutation({
    mutationFn: () => createRestaurant(
      { name: newRestaurantName.trim() },
      session!.authorization,
    ),
    onSuccess: async (restaurant) => {
      setNewRestaurantName('')
      setRestaurantId(restaurant.id)
      setBranchId('')
      await invalidate(queryClient, ['admin', 'restaurants'], ['booking-context'])
    },
  })
  const updateRestaurantMutation = useMutation({
    mutationFn: ({ restaurant, name, status }: {
      restaurant: Restaurant
      name: string
      status: Restaurant['status']
    }) => updateRestaurant(restaurant.id, {
      name: name.trim(),
      status,
      expectedVersion: restaurant.version,
    }, session!.authorization),
    onSuccess: async () => invalidate(queryClient, ['admin', 'restaurants'], ['booking-context']),
    onError: () => void queryClient.invalidateQueries({ queryKey: ['admin', 'restaurants'] }),
  })
  const createBranchMutation = useMutation({
    mutationFn: () => createBranch(restaurantId, {
      name: newBranch.name.trim(),
      address: newBranch.address.trim(),
      timezone: newBranch.timezone.trim(),
    }, session!.authorization),
    onSuccess: async (branch) => {
      setNewBranch({ name: '', address: '', timezone: branch.timezone })
      setBranchId(branch.id)
      setBlockDate(todayInTimezone(branch.timezone))
      await invalidate(queryClient, ['admin', 'branches'], ['booking-context'])
    },
  })
  const updateBranchMutation = useMutation({
    mutationFn: ({ branch, request }: { branch: Branch; request: UpdateBranchRequest }) =>
      updateBranch(branch.id, request, session!.authorization),
    onSuccess: async () => invalidate(
      queryClient,
      ['admin', 'branches'],
      ['booking-context'],
      ['availability'],
    ),
    onError: () => void queryClient.invalidateQueries({ queryKey: ['admin', 'branches'] }),
  })
  const createTableMutation = useMutation({
    mutationFn: () => createDiningTable(branchId, {
      name: newTable.name.trim(),
      capacity: Number(newTable.capacity),
    }, session!.authorization),
    onSuccess: async () => {
      setNewTable({ name: '', capacity: '2' })
      await invalidate(queryClient, ['admin', 'tables'], ['availability'])
    },
  })
  const updateTableMutation = useMutation({
    mutationFn: ({ table, name, capacity, enabled }: {
      table: DiningTable
      name: string
      capacity: number
      enabled: boolean
    }) => updateDiningTable(table.id, {
      name: name.trim(),
      capacity,
      enabled,
      expectedVersion: table.version,
    }, session!.authorization),
    onSuccess: async () => invalidate(queryClient, ['admin', 'tables'], ['availability']),
    onError: () => void queryClient.invalidateQueries({ queryKey: ['admin', 'tables'] }),
  })
  const replaceHoursMutation = useMutation({
    mutationFn: (items: Array<{ dayOfWeek: number; opensAt: string; closesAt: string }>) =>
      replaceBusinessHours(branchId, { items }, session!.authorization),
    onSuccess: async () => invalidate(queryClient, ['admin', 'business-hours'], ['availability']),
  })
  const createBlockMutation = useMutation({
    mutationFn: (request: {
      diningTableId?: string
      startsAt: string
      endsAt: string
      reason: string
    }) => createBookingBlock(branchId, request, session!.authorization),
    onSuccess: async () => invalidate(queryClient, ['admin', 'booking-blocks'], ['availability']),
  })
  const deleteBlockMutation = useMutation({
    mutationFn: (blockId: string) => deleteBookingBlock(blockId, session!.authorization),
    onSuccess: async () => {
      setArmedBlockId('')
      await invalidate(queryClient, ['admin', 'booking-blocks'], ['availability'])
    },
  })

  function submitRestaurant(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    createRestaurantMutation.mutate()
  }

  function submitBranch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    createBranchMutation.mutate()
  }

  function submitTable(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    createTableMutation.mutate()
  }

  return (
    <main className="page admin-page settings-page">
      <p className="eyebrow">SERVICE CONFIGURATION</p>
      <h1>매장 설정</h1>
      <p className="lead">
        식당과 지점을 만들고 예약 정책, 테이블, 영업시간과 예약 차단 시간을 관리합니다.
        {auth.mode === 'basic'
          ? ' 로컬 자격정보는 앱 메모리에만 유지됩니다.'
          : ' 모든 변경은 OIDC 관리자 권한으로 실행됩니다.'}
      </p>

      {!session ? (
        <AdminLoginPanel basicButtonLabel="매장 설정 연결" />
      ) : (
        <>
          <AdminSessionHeader session={session} />
          <section className="settings-section" aria-labelledby="restaurant-heading">
            <SectionHeading kicker="ORGANIZATION" id="restaurant-heading" title="식당과 지점" />
            <div className="settings-selectors">
              <label>
                식당
                <select
                  onChange={(event) => {
                    setRestaurantId(event.target.value)
                    setBranchId('')
                  }}
                  value={restaurantId}
                >
                  <option value="">식당 선택</option>
                  {restaurantsQuery.data?.map((restaurant) => (
                    <option key={restaurant.id} value={restaurant.id}>
                      {restaurant.name} · {restaurant.status === 'ACTIVE' ? '운영' : '중지'}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                지점
                <select
                  disabled={!restaurantId}
                  onChange={(event) => {
                    const nextId = event.target.value
                    setBranchId(nextId)
                    const branch = branchesQuery.data?.find((item) => item.id === nextId)
                    if (branch) setBlockDate(todayInTimezone(branch.timezone))
                  }}
                  value={branchId}
                >
                  <option value="">지점 선택</option>
                  {branchesQuery.data?.map((branch) => (
                    <option key={branch.id} value={branch.id}>
                      {branch.name} · {branch.status === 'ACTIVE' ? '운영' : '중지'}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <QueryError errors={[restaurantsQuery.error, branchesQuery.error]} />
            <div className="settings-create-grid">
              <form className="settings-card compact-form" onSubmit={submitRestaurant}>
                <h3>새 식당</h3>
                <label>
                  식당 이름
                  <input
                    maxLength={100}
                    onChange={(event) => setNewRestaurantName(event.target.value)}
                    required
                    value={newRestaurantName}
                  />
                </label>
                <button disabled={createRestaurantMutation.isPending} type="submit">식당 만들기</button>
                <MutationStatus mutation={createRestaurantMutation} success="식당을 만들었습니다." />
              </form>
              {selectedRestaurant ? (
                <RestaurantEditor
                  busy={updateRestaurantMutation.isPending}
                  error={updateRestaurantMutation.error}
                  key={`${selectedRestaurant.id}-${selectedRestaurant.version}`}
                  onSave={(name, status) => updateRestaurantMutation.mutate({
                    restaurant: selectedRestaurant,
                    name,
                    status,
                  })}
                  restaurant={selectedRestaurant}
                  success={updateRestaurantMutation.isSuccess}
                />
              ) : (
                <p className="settings-placeholder">기존 식당을 선택하면 이름과 운영 상태를 변경할 수 있습니다.</p>
              )}
            </div>
            {selectedRestaurant && (
              <form className="settings-card branch-create-form" onSubmit={submitBranch}>
                <h3>새 지점</h3>
                <div className="three-column-fields">
                  <label>지점 이름<input
                    maxLength={100}
                    onChange={(event) => setNewBranch((current) => ({ ...current, name: event.target.value }))}
                    required
                    value={newBranch.name}
                  /></label>
                  <label>주소<input
                    maxLength={300}
                    onChange={(event) => setNewBranch((current) => ({ ...current, address: event.target.value }))}
                    required
                    value={newBranch.address}
                  /></label>
                  <label>IANA 시간대<input
                    maxLength={50}
                    onChange={(event) => setNewBranch((current) => ({ ...current, timezone: event.target.value }))}
                    required
                    value={newBranch.timezone}
                  /></label>
                </div>
                <button disabled={createBranchMutation.isPending} type="submit">지점 만들기</button>
                <MutationStatus mutation={createBranchMutation} success="지점을 만들었습니다." />
              </form>
            )}
          </section>

          {selectedBranch && (
            <>
              <section className="settings-section" aria-labelledby="policy-heading">
                <SectionHeading kicker="POLICY" id="policy-heading" title="지점과 예약 정책" />
                <BranchPolicyEditor
                  branch={selectedBranch}
                  busy={updateBranchMutation.isPending}
                  error={updateBranchMutation.error}
                  key={`${selectedBranch.id}-${selectedBranch.version}`}
                  onSave={(request) => updateBranchMutation.mutate({ branch: selectedBranch, request })}
                  success={updateBranchMutation.isSuccess}
                />
              </section>

              <section className="settings-section" aria-labelledby="tables-heading">
                <SectionHeading kicker="CAPACITY" id="tables-heading" title="테이블" />
                <form className="inline-create-form" onSubmit={submitTable}>
                  <label>테이블 이름<input
                    maxLength={50}
                    onChange={(event) => setNewTable((current) => ({ ...current, name: event.target.value }))}
                    required
                    value={newTable.name}
                  /></label>
                  <label>수용 인원<input
                    max={100}
                    min={1}
                    onChange={(event) => setNewTable((current) => ({ ...current, capacity: event.target.value }))}
                    required
                    type="number"
                    value={newTable.capacity}
                  /></label>
                  <button disabled={createTableMutation.isPending} type="submit">테이블 추가</button>
                </form>
                <MutationStatus mutation={createTableMutation} success="테이블을 추가했습니다." />
                <QueryError errors={[tablesQuery.error]} />
                <div className="table-editor-list">
                  {tablesQuery.data?.map((table) => (
                    <TableEditor
                      busy={updateTableMutation.isPending}
                      error={updateTableMutation.variables?.table.id === table.id
                        ? updateTableMutation.error
                        : undefined}
                      key={`${table.id}-${table.version}`}
                      onSave={(name, capacity, enabled) => updateTableMutation.mutate({
                        table,
                        name,
                        capacity,
                        enabled,
                      })}
                      table={table}
                    />
                  ))}
                  {tablesQuery.data?.length === 0 && <p className="empty-state">등록된 테이블이 없습니다.</p>}
                </div>
              </section>

              <section className="settings-section" aria-labelledby="hours-heading">
                <SectionHeading kicker="SCHEDULE" id="hours-heading" title="주간 영업시간" />
                <p className="section-help">하루에 여러 영업 구간을 추가할 수 있으며 저장 시 전체 주간표를 교체합니다.</p>
                <QueryError errors={[hoursQuery.error]} />
                {hoursQuery.data && (
                  <HoursEditor
                    busy={replaceHoursMutation.isPending}
                    error={replaceHoursMutation.error}
                    hours={hoursQuery.data.items}
                    key={`${selectedBranch.id}-${hoursQuery.dataUpdatedAt}`}
                    onSave={(items) => replaceHoursMutation.mutate(items)}
                    success={replaceHoursMutation.isSuccess}
                  />
                )}
              </section>

              <section className="settings-section" aria-labelledby="blocks-heading">
                <SectionHeading kicker="BLOCKS" id="blocks-heading" title="예약 차단" />
                <label className="block-date-field">
                  조회 날짜
                  <input onChange={(event) => setBlockDate(event.target.value)} type="date" value={blockDate} />
                </label>
                <BlockCreateForm
                  branch={selectedBranch}
                  busy={createBlockMutation.isPending}
                  date={blockDate}
                  error={createBlockMutation.error}
                  key={`${selectedBranch.id}-${blockDate}`}
                  onCreate={(request) => createBlockMutation.mutate(request)}
                  tables={tablesQuery.data ?? []}
                  success={createBlockMutation.isSuccess}
                />
                <QueryError errors={[blocksQuery.error]} />
                <div className="booking-block-list">
                  {blocksQuery.data?.map((block) => {
                    const tableName = tablesQuery.data?.find((table) => table.id === block.diningTableId)?.name
                    return (
                      <article className="booking-block-card" key={block.id}>
                        <div>
                          <strong>{tableName ?? '지점 전체'}</strong>
                          <span>{formatDateTime(block.startsAt, selectedBranch.timezone)} – {formatTime(block.endsAt, selectedBranch.timezone)}</span>
                          <p>{block.reason}</p>
                        </div>
                        <button
                          className="danger-outline-button"
                          disabled={deleteBlockMutation.isPending}
                          onClick={() => {
                            if (armedBlockId !== block.id) setArmedBlockId(block.id)
                            else deleteBlockMutation.mutate(block.id)
                          }}
                          type="button"
                        >{armedBlockId === block.id ? '삭제 확정' : '삭제 검토'}</button>
                      </article>
                    )
                  })}
                  {blocksQuery.data?.length === 0 && <p className="empty-state">선택한 날짜에 차단 시간이 없습니다.</p>}
                </div>
                <MutationStatus mutation={deleteBlockMutation} success="차단 시간을 삭제했습니다." />
              </section>
            </>
          )}
        </>
      )}
    </main>
  )
}

function RestaurantEditor({ restaurant, busy, success, error, onSave }: {
  restaurant: Restaurant
  busy: boolean
  success: boolean
  error: unknown
  onSave: (name: string, status: Restaurant['status']) => void
}) {
  const [name, setName] = useState(restaurant.name)
  const [status, setStatus] = useState(restaurant.status)
  return (
    <form className="settings-card compact-form" onSubmit={(event) => {
      event.preventDefault()
      onSave(name, status)
    }}>
      <h3>선택한 식당</h3>
      <label>식당 이름<input maxLength={100} onChange={(event) => setName(event.target.value)} required value={name} /></label>
      <label>운영 상태<select onChange={(event) => setStatus(event.target.value as Restaurant['status'])} value={status}>
        <option value="ACTIVE">운영</option><option value="INACTIVE">중지</option>
      </select></label>
      <button disabled={busy} type="submit">식당 저장</button>
      {success && <p className="inline-success" role="status">식당을 저장했습니다.</p>}
      {error !== null && error !== undefined && <p className="inline-error" role="alert">{errorMessage(error)}</p>}
    </form>
  )
}

function BranchPolicyEditor({ branch, busy, success, error, onSave }: {
  branch: Branch
  busy: boolean
  success: boolean
  error: unknown
  onSave: (request: UpdateBranchRequest) => void
}) {
  const [name, setName] = useState(branch.name)
  const [address, setAddress] = useState(branch.address)
  const [timezone, setTimezone] = useState(branch.timezone)
  const [status, setStatus] = useState(branch.status)
  const [policy, setPolicy] = useState<Record<string, string>>(() => Object.fromEntries(
    POLICY_FIELDS.map(({ key }) => [key, String(branch[key])]),
  ))
  return (
    <form className="branch-policy-form" onSubmit={(event) => {
      event.preventDefault()
      const numericPolicy = Object.fromEntries(
        POLICY_FIELDS.map(({ key }) => [key, Number(policy[key])]),
      )
      onSave({
        name: name.trim(),
        address: address.trim(),
        timezone: timezone.trim(),
        status,
        ...numericPolicy,
        expectedVersion: branch.version,
      })
    }}>
      <div className="three-column-fields">
        <label>지점 이름<input maxLength={100} onChange={(event) => setName(event.target.value)} required value={name} /></label>
        <label>주소<input maxLength={300} onChange={(event) => setAddress(event.target.value)} required value={address} /></label>
        <label>IANA 시간대<input maxLength={50} onChange={(event) => setTimezone(event.target.value)} required value={timezone} /></label>
      </div>
      <div className="policy-grid">
        <label>운영 상태<select onChange={(event) => setStatus(event.target.value as Branch['status'])} value={status}>
          <option value="ACTIVE">운영</option><option value="INACTIVE">중지</option>
        </select></label>
        {POLICY_FIELDS.map((field) => (
          <label key={field.key}>{field.label}<input
            max={field.max}
            min={field.min}
            onChange={(event) => setPolicy((current) => ({ ...current, [field.key]: event.target.value }))}
            required
            type="number"
            value={policy[field.key]}
          /></label>
        ))}
      </div>
      <button disabled={busy} type="submit">지점 정책 저장</button>
      {success && <p className="inline-success" role="status">지점 정책을 저장했습니다.</p>}
      {error !== null && error !== undefined && <p className="inline-error" role="alert">{errorMessage(error)}</p>}
    </form>
  )
}

function TableEditor({ table, busy, error, onSave }: {
  table: DiningTable
  busy: boolean
  error: unknown
  onSave: (name: string, capacity: number, enabled: boolean) => void
}) {
  const [name, setName] = useState(table.name)
  const [capacity, setCapacity] = useState(String(table.capacity))
  const [enabled, setEnabled] = useState(table.enabled)
  return (
    <form className="table-editor" onSubmit={(event) => {
      event.preventDefault()
      onSave(name, Number(capacity), enabled)
    }}>
      <label>이름<input maxLength={50} onChange={(event) => setName(event.target.value)} required value={name} /></label>
      <label>수용 인원<input max={100} min={1} onChange={(event) => setCapacity(event.target.value)} required type="number" value={capacity} /></label>
      <label className="toggle-field"><input checked={enabled} onChange={(event) => setEnabled(event.target.checked)} type="checkbox" />예약 사용</label>
      <button className="secondary-button" disabled={busy} type="submit">저장</button>
      {error !== null && error !== undefined && <p className="inline-error" role="alert">{errorMessage(error)}</p>}
    </form>
  )
}

type HourRow = { key: string; dayOfWeek: number; opensAt: string; closesAt: string }
let hourSequence = 0

function HoursEditor({ hours, busy, success, error, onSave }: {
  hours: BusinessHour[]
  busy: boolean
  success: boolean
  error: unknown
  onSave: (items: Array<{ dayOfWeek: number; opensAt: string; closesAt: string }>) => void
}) {
  const [rows, setRows] = useState<HourRow[]>(() => hours.map((hour) => ({
    key: hour.id,
    dayOfWeek: hour.dayOfWeek,
    opensAt: hour.opensAt.slice(0, 5),
    closesAt: hour.closesAt.slice(0, 5),
  })))
  function updateRow(key: string, update: Partial<HourRow>) {
    setRows((current) => current.map((row) => row.key === key ? { ...row, ...update } : row))
  }
  return (
    <form className="hours-editor" onSubmit={(event) => {
      event.preventDefault()
      onSave(rows.map(({ dayOfWeek, opensAt, closesAt }) => ({
        dayOfWeek,
        opensAt: apiTime(opensAt),
        closesAt: apiTime(closesAt),
      })))
    }}>
      <div className="hours-list">
        {rows.map((row) => (
          <div className="hours-row" key={row.key}>
            <label>요일<select onChange={(event) => updateRow(row.key, { dayOfWeek: Number(event.target.value) })} value={row.dayOfWeek}>
              {DAYS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select></label>
            <label>시작<input onChange={(event) => updateRow(row.key, { opensAt: event.target.value })} required type="time" value={row.opensAt} /></label>
            <label>종료<input onChange={(event) => updateRow(row.key, { closesAt: event.target.value })} required type="time" value={row.closesAt} /></label>
            <button className="text-button" onClick={() => setRows((current) => current.filter((item) => item.key !== row.key))} type="button">구간 제거</button>
          </div>
        ))}
        {rows.length === 0 && <p className="empty-state">영업시간이 없습니다. 온라인 예약을 열려면 구간을 추가하세요.</p>}
      </div>
      <div className="editor-actions">
        <button className="secondary-button" onClick={() => {
          hourSequence += 1
          setRows((current) => [...current, {
            key: `new-${hourSequence}`,
            dayOfWeek: 1,
            opensAt: '11:00',
            closesAt: '22:00',
          }])
        }} type="button">영업 구간 추가</button>
        <button disabled={busy} type="submit">주간표 전체 저장</button>
      </div>
      {success && <p className="inline-success" role="status">영업시간을 저장했습니다.</p>}
      {error !== null && error !== undefined && <p className="inline-error" role="alert">{errorMessage(error)}</p>}
    </form>
  )
}

function BlockCreateForm({ branch, tables, date, busy, success, error, onCreate }: {
  branch: Branch
  tables: DiningTable[]
  date: string
  busy: boolean
  success: boolean
  error: unknown
  onCreate: (request: { diningTableId?: string; startsAt: string; endsAt: string; reason: string }) => void
}) {
  const [tableId, setTableId] = useState('')
  const [startsAt, setStartsAt] = useState(`${date}T12:00`)
  const [endsAt, setEndsAt] = useState(`${date}T13:00`)
  const [reason, setReason] = useState('')
  const [localError, setLocalError] = useState('')
  return (
    <form className="block-create-form" onSubmit={(event) => {
      event.preventDefault()
      try {
        setLocalError('')
        onCreate({
          ...(tableId ? { diningTableId: tableId } : {}),
          startsAt: zonedLocalToInstant(startsAt, branch.timezone),
          endsAt: zonedLocalToInstant(endsAt, branch.timezone),
          reason: reason.trim(),
        })
      } catch (caught) {
        setLocalError(errorMessage(caught))
      }
    }}>
      <div className="block-fields">
        <label>대상<select onChange={(event) => setTableId(event.target.value)} value={tableId}>
          <option value="">지점 전체</option>
          {tables.map((table) => <option key={table.id} value={table.id}>{table.name}</option>)}
        </select></label>
        <label>시작<input onChange={(event) => setStartsAt(event.target.value)} required type="datetime-local" value={startsAt} /></label>
        <label>종료<input onChange={(event) => setEndsAt(event.target.value)} required type="datetime-local" value={endsAt} /></label>
        <label>사유<input maxLength={500} onChange={(event) => setReason(event.target.value)} required value={reason} /></label>
      </div>
      <button disabled={busy} type="submit">차단 시간 추가</button>
      {success && <p className="inline-success" role="status">차단 시간을 추가했습니다.</p>}
      {(localError || error) && <p className="inline-error" role="alert">{localError || errorMessage(error)}</p>}
    </form>
  )
}

function SectionHeading({ kicker, title, id }: { kicker: string; title: string; id: string }) {
  return <div className="section-heading"><div><p className="action-kicker">{kicker}</p><h2 id={id}>{title}</h2></div></div>
}

function QueryError({ errors }: { errors: unknown[] }) {
  const error = errors.find((item) => item !== null && item !== undefined)
  return error ? <p className="inline-error" role="alert">{errorMessage(error)}</p> : null
}

function MutationStatus({ mutation, success }: {
  mutation: { isSuccess: boolean; error: unknown }
  success: string
}) {
  if (mutation.error !== null && mutation.error !== undefined) {
    return <p className="inline-error" role="alert">{errorMessage(mutation.error)}</p>
  }
  return mutation.isSuccess ? <p className="inline-success" role="status">{success}</p> : null
}

async function invalidate(
  queryClient: ReturnType<typeof useQueryClient>,
  ...keys: string[][]
) {
  await Promise.all(keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })))
}

function apiTime(value: string) {
  return value.length === 5 ? `${value}:00` : value
}

function todayInTimezone(timezone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    day: '2-digit',
    month: '2-digit',
    timeZone: timezone,
    year: 'numeric',
  }).formatToParts(new Date())
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}`
}

function zonedDayWindow(date: string, timezone: string) {
  const startsAt = zonedLocalToInstant(`${date}T00:00`, timezone)
  const next = new Date(`${date}T00:00:00Z`)
  next.setUTCDate(next.getUTCDate() + 1)
  const endsAt = zonedLocalToInstant(`${next.toISOString().slice(0, 10)}T00:00`, timezone)
  return { startsAt, endsAt }
}

function formatDateTime(value: string, timezone: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: timezone,
  }).format(new Date(value))
}

function formatTime(value: string, timezone: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    timeZone: timezone,
  }).format(new Date(value))
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError) return error.problem.detail
  if (error instanceof Error) return error.message
  return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}
