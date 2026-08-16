import { NavLink, Outlet } from 'react-router-dom'
import './App.css'

function App() {
  return (
    <div className="app-shell">
      <header className="app-header">
        <NavLink className="brand" to="/">TABLEFLOW</NavLink>
        <nav aria-label="주요 메뉴">
          <NavLink to="/" end>소개</NavLink>
          <NavLink to="/reservations/new">새 예약</NavLink>
          <NavLink to="/reservations/manage">예약 확인</NavLink>
          <NavLink to="/admin/reservations">운영</NavLink>
          <NavLink to="/admin/settings">설정</NavLink>
          <NavLink to="/todos">Todo</NavLink>
        </nav>
      </header>
      <Outlet />
    </div>
  )
}

export default App
