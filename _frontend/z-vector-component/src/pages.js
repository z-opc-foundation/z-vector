/**
 * ./pages 入口（lead 005 §8.2/§8.3/§9.3）：路由清单 + 菜单配置 + apiBase 注入。
 * suit 独立跑与宿主重组（主壳 domainRoutes）都从这里读。
 */
export { configureVector } from './services/api.js'
export { appMeta, menuConfig, routeTable } from './pages-manifest.jsx'
