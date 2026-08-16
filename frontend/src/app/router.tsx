import { createBrowserRouter } from 'react-router-dom'
import App from '../App'
import { HomePage } from '../pages/HomePage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { ReservationManagePage } from '../pages/ReservationManagePage'
import { ReservationBookingPage } from '../pages/ReservationBookingPage'
import { PrivacyPolicyPage } from '../pages/PrivacyPolicyPage'
import { TodoPage } from '../pages/TodoPage'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'reservations/new', element: <ReservationBookingPage /> },
      { path: 'reservations/manage', element: <ReservationManagePage /> },
      { path: 'privacy', element: <PrivacyPolicyPage /> },
      { path: 'todos', element: <TodoPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
