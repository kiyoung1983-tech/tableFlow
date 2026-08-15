import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { getHealth, listTodos } from '../api/todoApi'
import { TodoPage } from './TodoPage'

vi.mock('../api/todoApi', () => ({ getHealth: vi.fn(), listTodos: vi.fn() }))

describe('TodoPage', () => {
  it('shows backend health and todo items', async () => {
    vi.mocked(getHealth).mockResolvedValue({ status: 'UP' })
    vi.mocked(listTodos).mockResolvedValue({
      items: [{
        id: 'todo-1', title: '기본 테스트', completed: false,
        createdAt: '2026-08-16T00:00:00Z', updatedAt: '2026-08-16T00:00:00Z',
      }],
      page: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true,
    })

    render(
      <QueryClientProvider client={createQueryClient()}>
        <TodoPage />
      </QueryClientProvider>,
    )

    expect(await screen.findByText('Spring Boot: UP')).toBeInTheDocument()
    expect(await screen.findByText('기본 테스트')).toBeInTheDocument()
  })
})
