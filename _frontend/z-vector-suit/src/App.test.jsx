// Run with Vitest --globals --environment jsdom; no backend or auth service is used.
import React, {act} from 'react'
import {createRoot} from 'react-dom/client'
import {Outlet} from 'react-router-dom'
import App from './App'
import {routes} from '@yuku123/z-vector-component/pages'

vi.mock('@yuku123/z-vector-component/pages', async () => import('../../z-vector-component/src/pages-manifest.jsx'))
vi.mock('../../z-vector-component/src/pages/CollectionList', () => ({default: () => <div>collection element</div>}))
vi.mock('../../z-vector-component/src/pages/CollectionDetail', () => ({default: () => null}))
vi.mock('../../z-vector-component/src/pages/SearchPlayground', () => ({default: () => null}))
vi.mock('../../z-vector-component/src/pages/InstanceStatus', () => ({default: () => null}))
vi.mock('../../z-vector-component/src/pages/ComponentDoc', () => ({default: () => null}))
vi.mock('../../z-vector-component/src/pages/HomePage', () => ({default: () => null}))
vi.mock('../../z-vector-component/src/index.js', () => ({VectorApp: () => <div>vector fallback component</div>}))
vi.mock('@yuku123/z-vector-component', () => ({EmptyState: () => <div>not found</div>, LoginPage: () => null}))
vi.mock('@ant-design/icons', () => ({
    CloudServerOutlined: () => null, DatabaseOutlined: () => null, HomeOutlined: () => null,
    ReadOutlined: () => null, ThunderboltOutlined: () => null,
}))
vi.mock('@yuku123/z-frontend-common', () => ({
    ErrorBoundary: ({children}) => children,
    AppLayout: () => <Outlet />,
}))

let root, container
const originalRoutes = [...routes]
beforeEach(() => {
    globalThis.IS_REACT_ACT_ENVIRONMENT = true
    localStorage.setItem('token', 'test-only')
    container = document.createElement('div')
    document.body.append(container)
    root = createRoot(container)
})
afterEach(async () => {
    await act(async () => root.unmount())
    container.remove()
    localStorage.clear()
    routes.splice(0, routes.length, ...originalRoutes)
    window.history.replaceState({}, '', '/')
})
async function open(path) {
    window.history.replaceState({}, '', path)
    await act(async () => root.render(<App />))
}

it('renders the real manifest Component fallback', async () => {
    await open('/z-vector/legacy')
    expect(container.textContent).toContain('vector fallback component')
    expect(container.textContent).not.toContain('not found')
})

it('preserves existing element routes', async () => {
    await open('/z-vector/collections')
    expect(container.textContent).toContain('collection element')
    expect(container.textContent).not.toContain('vector fallback component')
})

it('prefers an explicit element when both formats are present', async () => {
    routes.unshift({path: '/z-vector/precedence', element: <div>explicit element</div>, Component: () => <div>wrong component</div>})
    await open('/z-vector/precedence')
    expect(container.textContent).toBe('explicit element')
})

it.each([null, false])('preserves an intentionally empty element (%s)', async element => {
    routes.unshift({path: '/z-vector/empty', element, Component: () => <div>wrong component</div>})
    await open('/z-vector/empty')
    expect(container.textContent).toBe('')
})
