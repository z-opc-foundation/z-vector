import { useEffect, useRef, useState } from 'react'
import { Input } from 'antd'
import { SearchOutlined } from '@ant-design/icons'
import { radius } from './tokens.js'

/**
 * Debounced SearchInput —— 阶段一直接搬 z-opc 版本，0 改动。
 *
 * 300ms 防抖 + Enter 立即触发 + Esc 清空。
 */
export default function SearchInput({
                                        value: controlledValue,
                                        onChange,
                                        onSearch,
                                        placeholder = '搜索...',
                                        debounceMs = 300,
                                        width = 280,
                                        allowClear = true,
                                        enterButton: _ignored,
                                        ...rest
                                    }) {
    const [inner, setInner] = useState(controlledValue || '')
    const timerRef = useRef(null)
    const lastEmittedRef = useRef(controlledValue || '')

    useEffect(() => {
        if (controlledValue !== undefined && controlledValue !== inner) {
            setInner(controlledValue || '')
            lastEmittedRef.current = controlledValue || ''
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [controlledValue])

    useEffect(() => {
        return () => {
            if (timerRef.current) clearTimeout(timerRef.current)
        }
    }, [])

    const emit = (v) => {
        lastEmittedRef.current = v
        if (onChange) onChange(v)
    }

    const handleChange = (e) => {
        const v = e.target.value
        setInner(v)
        if (timerRef.current) clearTimeout(timerRef.current)
        if (v === '') {
            emit('')
            return
        }
        timerRef.current = setTimeout(() => emit(v), debounceMs)
    }

    const handleSearch = (v) => {
        if (timerRef.current) clearTimeout(timerRef.current)
        emit(v)
        if (onSearch) onSearch(v)
    }

    const handleKeyDown = (e) => {
        if (e.key === 'Enter') {
            e.preventDefault()
            handleSearch(inner)
        } else if (e.key === 'Escape') {
            setInner('')
            handleSearch('')
        }
    }

    const handleClear = () => {
        setInner('')
        handleSearch('')
    }

    return (
        <Input
            {...rest}
            value={inner}
            onChange={handleChange}
            onPressEnter={handleSearch}
            onKeyDown={handleKeyDown}
            placeholder={placeholder}
            allowClear={allowClear ? { clearIcon: <span onClick={handleClear}>×</span> } : false}
            prefix={<SearchOutlined style={{ color: '#9ca3af' }} />}
            style={{
                width,
                borderRadius: radius.md,
                ...(rest.style || {}),
            }}
        />
    )
}
