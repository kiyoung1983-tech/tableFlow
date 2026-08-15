import { NavLink, Outlet } from 'react-router-dom'
import './App.css'

function App() {
  return (
    <div className="app-shell">
      <header className="app-header">
        <NavLink className="brand" to="/">SPRING FULL-STACK STARTER</NavLink>
        <nav aria-label="주요 메뉴">
          <NavLink to="/" end>소개</NavLink>
          <NavLink to="/todos">Todo</NavLink>
        </nav>
      </header>
      <Outlet />
    </div>
  )
}

export default App
