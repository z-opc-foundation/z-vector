import { useEffect, useMemo, useRef, useState } from 'react'
import { ReadOutlined } from '@ant-design/icons'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeSlug from 'rehype-slug'
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter'
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism'
import { PageHeader } from '@yuku123/z-vector-component'
import docMarkdown from '../DOC.md?raw'

const TOC_WIDTH = 240
const ACTIVE_COLOR = '#7c3aed'
const ACTIVE_BG = 'rgba(124,58,237,0.08)'

const slugify = (text) => text.trim().toLowerCase().replace(/\s+/g, '-').replace(/[^\u4e00-\u9fa5\w\-]/g, '')

/**
 * 组件文档页（lead 005 §7）—— 把 <repo>-component/src/DOC.md 用 react-markdown 渲染，
 * 左侧 240px TOC + 右侧正文，TOC 当前章节随滚动联动。
 *
 * 实现要点：
 *   - 文档以字符串 import（vite `?raw` 编译期嵌入），不是 fetch。
 *   - 章节 id 由 rehype-slug 自动加到 H1/H2/H3。
 *   - TOC 自行渲染（不引 antd Anchor 的 getCurrentAnchor 黑盒），点击 → 平滑滚动到对应 h*；
 *     滚动联动靠 article 上的 scroll 事件 + headings 顶部位置循环挑"最靠近视口顶且已过线"的章节。
 *   - 代码块走 react-syntax-highlighter Prism 主题，零行号、零复制按钮（文档阅读场景）。
 */
export default function ComponentDoc() {
    const contentRef = useRef(null)
    const tocRef = useRef(null)
    const [activeId, setActiveId] = useState('')

    // 从 markdown 文本里粗提取 H1/H2/H3 标题构造 TOC（渲染时仍以 rehype-slug 加的 id 为准）
    const headings = useMemo(() => {
        const acc = []
        let inFence = false
        for (const raw of docMarkdown.split('\n')) {
            const line = raw.trimEnd()
            if (/^```/.test(line)) { inFence = !inFence; continue }
            if (inFence) continue
            const m = /^(#{1,3})\s+(.+?)\s*$/.exec(line)
            if (m) acc.push({ level: m[1].length, text: m[2] })
        }
        return acc
    }, [])

    const tocItems = useMemo(() =>
            headings
                .filter((h) => h.level <= 2)
                .map((h) => ({ level: h.level, id: slugify(h.text), text: h.text }))
        , [headings])

    useEffect(() => {
        const root = contentRef.current
        if (!root) return

        const list = () => Array.from(root.querySelectorAll('h1, h2'))
            .map((el) => ({ id: el.id || slugify(el.textContent || ''), top: el.getBoundingClientRect().top }))

        // 距 article 视口顶部最近且已经过线（top <= rootTop+80）的章节视为当前
        const computeActive = () => {
            const rootTop = root.getBoundingClientRect().top
            const items = list()
            if (!items.length) return
            let best = items[0].id
            for (const n of items) {
                if (n.top - rootTop <= 80) best = n.id
                else break
            }
            setActiveId(best)
        }

        computeActive()
        const onScroll = () => computeActive()
        root.addEventListener('scroll', onScroll, { passive: true })
        window.addEventListener('scroll', onScroll, { passive: true, capture: true })
        window.addEventListener('resize', onScroll)

        const ro = new ResizeObserver(() => computeActive())
        ro.observe(root)

        return () => {
            root.removeEventListener('scroll', onScroll)
            window.removeEventListener('scroll', onScroll, true)
            window.removeEventListener('resize', onScroll)
            ro.disconnect()
        }
    }, [])

    // 选中 TOC 项滚出可视区时，把 TOC 自身滚动到让它入视
    useEffect(() => {
        const toc = tocRef.current
        if (!toc || !activeId) return
        const activeEl = toc.querySelector(`[data-toc-id="${CSS.escape(activeId)}"]`)
        if (activeEl) {
            const tocTop = toc.getBoundingClientRect().top
            const elTop = activeEl.getBoundingClientRect().top
            if (elTop < tocTop + 24 || elTop > tocTop + toc.clientHeight - 24) {
                activeEl.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
            }
        }
    }, [activeId])

    const onTocClick = (e, id) => {
        e.preventDefault()
        const el = document.getElementById(id)
        if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
        setActiveId(id)
    }

    return (
        <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, flex: 1 }}>
            <PageHeader
                title="组件文档"
                breadcrumb={[{ label: 'z-vector' }, { label: '组件文档' }]}
                extra={<span style={{ fontSize: 12, color: '#94a3b8' }}>DOC.md → 实时渲染</span>}
            />

            <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start', minHeight: 0 }}>
                <aside
                    ref={tocRef}
                    style={{
                        width: TOC_WIDTH,
                        flexShrink: 0,
                        position: 'sticky',
                        top: 12,
                        alignSelf: 'flex-start',
                        maxHeight: 'calc(100vh - 120px)',
                        overflowY: 'auto',
                        padding: '8px 8px 12px',
                        background: '#ffffff',
                        border: '1px solid #eef2f7',
                        borderRadius: 8,
                    }}
                >
                    <div style={{
                        display: 'flex', alignItems: 'center', gap: 6,
                        fontSize: 12, color: '#64748b', marginBottom: 8, padding: '0 4px',
                    }}>
                        <ReadOutlined />
                        <span style={{ fontWeight: 600 }}>本文目录</span>
                    </div>
                    <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                        {tocItems.map((h) => {
                            const isActive = activeId === h.id
                            return (
                                <li key={h.id} data-toc-id={h.id}>
                                    <a
                                        href={'#' + h.id}
                                        onClick={(e) => onTocClick(e, h.id)}
                                        style={{
                                            display: 'block',
                                            padding: h.level === 1 ? '6px 10px' : '6px 10px 6px 22px',
                                            borderLeft: `3px solid ${isActive ? ACTIVE_COLOR : 'transparent'}`,
                                            background: isActive ? ACTIVE_BG : 'transparent',
                                            color: isActive ? ACTIVE_COLOR : '#475569',
                                            fontWeight: isActive ? 600 : 400,
                                            fontSize: 13,
                                            lineHeight: 1.5,
                                            textDecoration: 'none',
                                            borderRadius: isActive ? '0 4px 4px 0' : 4,
                                            transition: 'all 120ms ease',
                                            whiteSpace: 'nowrap',
                                            overflow: 'hidden',
                                            textOverflow: 'ellipsis',
                                        }}
                                    >
                                        {h.text}
                                    </a>
                                </li>
                            )
                        })}
                    </ul>
                </aside>

                <article
                    ref={contentRef}
                    style={{
                        flex: 1,
                        minWidth: 0,
                        background: '#ffffff',
                        border: '1px solid #eef2f7',
                        borderRadius: 8,
                        padding: '24px 32px',
                        lineHeight: 1.75,
                        color: '#0f172a',
                        overflowY: 'auto',
                        maxHeight: 'calc(100vh - 120px)',
                    }}
                    className="zvec-doc"
                >
                    <ReactMarkdown
                        remarkPlugins={[remarkGfm]}
                        rehypePlugins={[rehypeSlug]}
                        components={{
                            code({ inline, className, children, ...props }) {
                                const match = /language-(\w+)/.exec(className || '')
                                if (inline || !match) {
                                    return <code className={className} style={{
                                        background: 'rgba(124,58,237,0.08)',
                                        color: '#7c3aed',
                                        padding: '1px 6px',
                                        borderRadius: 4,
                                        fontSize: '0.92em',
                                        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                                    }} {...props}>{children}</code>
                                }
                                return (
                                    <SyntaxHighlighter
                                        language={match[1]}
                                        style={oneDark}
                                        customStyle={{
                                            margin: '12px 0',
                                            borderRadius: 6,
                                            fontSize: 13,
                                            background: '#1e293b',
                                        }}
                                    >
                                        {String(children).replace(/\n$/, '')}
                                    </SyntaxHighlighter>
                                )
                            },
                            h1: ({ node, children }) => {
                                const id = node?.properties?.id
                                return <h1 id={id} style={{ fontSize: 22, fontWeight: 700, margin: '8px 0 16px', borderBottom: '1px solid #eef2f7', paddingBottom: 8, scrollMarginTop: 16 }}>{children}</h1>
                            },
                            h2: ({ node, children }) => {
                                const id = node?.properties?.id
                                return <h2 id={id} style={{ fontSize: 18, fontWeight: 700, margin: '24px 0 12px', color: '#0f172a', scrollMarginTop: 16 }}>{children}</h2>
                            },
                            h3: ({ node, children }) => {
                                const id = node?.properties?.id
                                return <h3 id={id} style={{ fontSize: 15, fontWeight: 600, margin: '16px 0 8px', color: '#334155', scrollMarginTop: 16 }}>{children}</h3>
                            },
                            p: ({ children }) => <p style={{ margin: '8px 0', color: '#1f2937' }}>{children}</p>,
                            ul: ({ children }) => <ul style={{ margin: '8px 0', paddingLeft: 22 }}>{children}</ul>,
                            ol: ({ children }) => <ol style={{ margin: '8px 0', paddingLeft: 22 }}>{children}</ol>,
                            li: ({ children }) => <li style={{ margin: '4px 0' }}>{children}</li>,
                            blockquote: ({ children }) => <blockquote style={{
                                margin: '12px 0', padding: '8px 14px',
                                borderLeft: '3px solid #7c3aed', background: 'rgba(124,58,237,0.05)',
                                color: '#475569', borderRadius: 4,
                            }}>{children}</blockquote>,
                            table: ({ children }) => (
                                <div style={{ overflowX: 'auto', margin: '12px 0' }}>
                                    <table style={{
                                        width: '100%', borderCollapse: 'collapse',
                                        fontSize: 13,
                                    }}>{children}</table>
                                </div>
                            ),
                            th: ({ children }) => <th style={{
                                padding: '8px 10px', background: '#f8fafc',
                                border: '1px solid #e2e8f0', textAlign: 'left',
                                fontWeight: 600, color: '#334155',
                            }}>{children}</th>,
                            td: ({ children }) => <td style={{
                                padding: '8px 10px', border: '1px solid #e2e8f0',
                                color: '#1f2937',
                            }}>{children}</td>,
                            a: ({ href, children }) => {
                                const isExternal = /^https?:\/\//i.test(href || '')
                                return isExternal
                                    ? <a href={href} target="_blank" rel="noopener noreferrer"
                                          style={{ color: '#7c3aed' }}>{children}</a>
                                    : <a href={href} style={{ color: '#7c3aed' }}>{children}</a>
                            },
                            hr: () => <hr style={{ border: 'none', borderTop: '1px solid #eef2f7', margin: '20px 0' }} />,
                        }}
                    >
                        {docMarkdown}
                    </ReactMarkdown>
                </article>
            </div>
        </div>
    )
}
