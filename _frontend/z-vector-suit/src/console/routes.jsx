import CollectionList from './pages/CollectionList.jsx'
import CollectionDetail from './pages/CollectionDetail.jsx'
import SearchPlayground from './pages/SearchPlayground.jsx'
import InstanceStatus from './pages/InstanceStatus.jsx'

/**
 * z-vector console 的菜单 + 路由清单（routes manifest）。
 * <p>
 * 这是「通用左菜单架子 AdminShell」的第一个消费方。
 * App.jsx 不直接 import 任何 z-vector 页面，而是从这里读 menuItems + routeTable。
 * 替换 z-* 仓时只改本文件，App.jsx 不动一行。
 */
export const appMeta = {
    title: 'z-vector',
    short: 'ZV',
}

/**
 * 左侧菜单分组（antd Menu items 格式）。
 * 阶段一只有一组菜单（集合管理）；后续接「系统设置」「监控告警」等加 children。
 *
 * icon 字段：传 forwardRef 组件引用（<Xxx /> 拆出来的那一份），
 * antd Menu 内部会把它包成元素。icon 字段不能传字符串 / DOM / undefined，
 * 否则 antd 渲染时会把 forwardRef 类型对象当作 child render → React 抛
 * "object with keys {$$typeof, render}"。
 */
export const menuConfig = [
    {
        key: '/collections',
        label: '集合管理',
        children: [
            { key: '/collections', label: '集合列表' },
        ],
    },
    { key: '/search',    label: '检索调试' },
    { key: '/instance',  label: '实例状态' },
]

/**
 * 路由表（react-router 6+ Route 元素格式）。
 * key 用 path 字符串，App.jsx 用 Route path 渲染。
 */
export const routeTable = [
    { path: '/collections',       element: <CollectionList /> },
    { path: '/collections/:name', element: <CollectionDetail /> },
    { path: '/search',            element: <SearchPlayground /> },
    { path: '/instance',          element: <InstanceStatus /> },
]

