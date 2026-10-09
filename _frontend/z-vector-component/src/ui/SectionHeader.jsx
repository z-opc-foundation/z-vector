/**
 * SectionHeader 段落标题 + CategoryDot 圆点。
 * 阶段一 console 用不到，保留为通用组件。
 */
export function CategoryDot({ color = 'blue', size = 8 }) {
    const palette = {
        blue: '#3b82f6', green: '#10b981', orange: '#f97316',
        violet: '#8b5cf6', pink: '#ec4899', cyan: '#06b6d4',
    }
    return (
        <span
            aria-hidden="true"
            style={{
                display: 'inline-block',
                width: size,
                height: size,
                borderRadius: '50%',
                background: palette[color] || color,
                marginRight: 8,
                verticalAlign: 'middle',
            }}
        />
    )
}

export default function SectionHeader({
                                          color = 'blue',
                                          title,
                                          subtitle,
                                          moreText = '全部',
                                          moreHref,
                                          onMore,
                                      }) {
    return (
        <div style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            marginBottom: 16,
        }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
                <CategoryDot color={color} size={10} />
                <span style={{ fontSize: 14, fontWeight: 600, color: '#111827' }}>{title}</span>
                {subtitle && (
                    <span style={{ fontSize: 13, color: '#6b7280', fontWeight: 400 }}>
                        {subtitle}
                    </span>
                )}
            </div>
            {(moreHref || onMore) && (
                <a
                    href={moreHref}
                    onClick={(e) => {
                        if (onMore) {
                            e.preventDefault()
                            onMore()
                        }
                    }}
                    style={{
                        fontSize: 13,
                        color: '#6b7280',
                        textDecoration: 'none',
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 4,
                        cursor: 'pointer',
                        transition: 'color 150ms ease',
                    }}
                    onMouseEnter={(e) => { e.currentTarget.style.color = '#7c3aed' }}
                    onMouseLeave={(e) => { e.currentTarget.style.color = '#6b7280' }}
                >
                    {moreText}
                    <span style={{ fontSize: 12 }}>›</span>
                </a>
            )}
        </div>
    )
}
