import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from './App.tsx'

describe('App', () => {
  it('서비스 이름을 보여 준다', () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: 'AskFlow' })).toBeTruthy()
  })
})
