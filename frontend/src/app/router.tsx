import { createBrowserRouter } from 'react-router-dom'
import App from '../App'
import { HomePage } from '../pages/HomePage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { TodoPage } from '../pages/TodoPage'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'todos', element: <TodoPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
