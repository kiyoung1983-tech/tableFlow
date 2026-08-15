import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { getHealth, listTodos } from '../api/todoApi'

function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    const trace = error.traceId ? ` 요청 ID: ${error.traceId}` : ''
    return `${error.problem.detail}${trace}`
  }
  return '백엔드가 실행 중인지 확인하세요.'
}

export function TodoPage() {
  const health = useQuery({ queryKey: ['health'], queryFn: getHealth })
  const todos = useQuery({ queryKey: ['todos', 0, 20], queryFn: () => listTodos() })
  const error = health.error ?? todos.error

  return (
    <main className="page narrow-page">
      <p className="eyebrow">API CONNECTION</p>
      <h1>Todo API</h1>
      {error
        ? <p className="status error" role="alert">{errorMessage(error)}</p>
        : <p className="status" aria-live="polite">
            {health.isPending ? '백엔드 연결 확인 중…' : `Spring Boot: ${health.data?.status}`}
          </p>}
      <section className="card" aria-busy={todos.isPending}>
        <h2>현재 항목</h2>
        {todos.isPending && <p>Todo를 불러오는 중입니다.</p>}
        {todos.data?.items.length === 0 && <p>아직 항목이 없습니다. POST /api/todos로 첫 항목을 추가하세요.</p>}
        {!!todos.data?.items.length && (
          <ul>{todos.data.items.map((todo) => <li key={todo.id}>{todo.title}</li>)}</ul>
        )}
      </section>
    </main>
  )
}
