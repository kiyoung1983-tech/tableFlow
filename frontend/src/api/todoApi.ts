import { api } from './client'
import type { CreateTodoRequest, HealthResponse, Todo, TodoPage } from './types'

export function getHealth(): Promise<HealthResponse> {
  return api('/actuator/health')
}

export function listTodos(page = 0, size = 20): Promise<TodoPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  return api(`/api/todos?${query}`)
}

export function getTodo(id: string): Promise<Todo> {
  return api(`/api/todos/${encodeURIComponent(id)}`)
}

export function createTodo(request: CreateTodoRequest, authorization?: string): Promise<Todo> {
  return api('/api/todos', {
    method: 'POST',
    headers: authorization ? { Authorization: authorization } : undefined,
    json: request,
  })
}
