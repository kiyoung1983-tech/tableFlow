import { Component, type ErrorInfo, type PropsWithChildren, type ReactNode } from 'react'

type State = { error?: Error }

export class AppErrorBoundary extends Component<PropsWithChildren, State> {
  state: State = {}

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unexpected UI error', error, info)
  }

  render(): ReactNode {
    if (this.state.error) {
      return (
        <main className="page narrow-page">
          <p className="eyebrow">UNEXPECTED ERROR</p>
          <h1>화면을 표시하지 못했습니다.</h1>
          <p>문제가 계속되면 브라우저 콘솔과 서버의 요청 ID를 함께 확인하세요.</p>
          <button type="button" onClick={() => window.location.reload()}>다시 불러오기</button>
        </main>
      )
    }
    return this.props.children
  }
}
