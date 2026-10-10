import {Navigate, Route, Routes} from 'react-router-dom'
import CollectionList from './CollectionList'
import CollectionDetail from './CollectionDetail'
import SearchPlayground from './SearchPlayground'
import InstanceStatus from './InstanceStatus'

/**
 * z-vector 管理面 — OpsWorkbench 以 /vector/* 通配挂进来。
 * 数据一律经 z-opc 的 VectorProxyController（/api/vector/**）转发到本 JVM 内嵌的 Qdrant REST 服务。
 */
export default function VectorApp() {
    return (
        <Routes>
            <Route index element={<Navigate to="collections" replace/>}/>
            <Route path="collections" element={<CollectionList/>}/>
            <Route path="collections/:name" element={<CollectionDetail/>}/>
            <Route path="search" element={<SearchPlayground/>}/>
            <Route path="instance" element={<InstanceStatus/>}/>
        </Routes>
    )
}
