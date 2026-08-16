import { createBrowserRouter } from 'react-router-dom'
import App from '../App'
import { HomePage } from '../pages/HomePage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { ReservationManagePage } from '../pages/ReservationManagePage'
import { AdminReservationPage } from '../pages/AdminReservationPage'
import { OidcSigninCallbackPage, OidcSignoutCallbackPage } from '../pages/OidcCallbackPage'
import { ReservationBookingPage } from '../pages/ReservationBookingPage'
import { PrivacyPolicyPage } from '../pages/PrivacyPolicyPage'
import { AdminSettingsPage } from '../pages/AdminSettingsPage'
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
      { path: 'admin/reservations', element: <AdminReservationPage /> },
      { path: 'admin/settings', element: <AdminSettingsPage /> },
      { path: 'auth/callback', element: <OidcSigninCallbackPage /> },
      { path: 'auth/logout-callback', element: <OidcSignoutCallbackPage /> },
      { path: 'todos', element: <TodoPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
