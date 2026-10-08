/**
 * 设计 token - 借鉴 wenming7/marketplace 视觉语言
 *
 * 设计原则:
 *   - 主色蓝紫, OKLCH 感知均匀
 *   - 多种分类色 (粉/蓝/绿/橙/紫) 用于模块/分类标识
 *   - 统一圆角 8/12/16, 统一阴影 (柔和多层)
 *   - 紧凑间距 4/8/12/16/24/32
 *
 * 这些 token 既被 antd ConfigProvider 消费, 也被 UI 组件内部引用,
 * 保持单一来源, 改一处全局生效.
 */

// ============ 品牌主色 ============
export const brand = {
    primary: '#7c3aed',
    primaryHover: '#8b5cf6',
    primaryActive: '#6d28d9',
    primarySoft: '#ede9fe',
}

export const palette = {
    pink:   { bg: '#ec4899', fg: '#ffffff', soft: '#fce7f3', gradient: 'linear-gradient(135deg, #ec4899 0%, #be185d 100%)' },
    blue:   { bg: '#3b82f6', fg: '#ffffff', soft: '#dbeafe', gradient: 'linear-gradient(135deg, #3b82f6 0%, #1e40af 100%)' },
    green:  { bg: '#10b981', fg: '#ffffff', soft: '#d1fae5', gradient: 'linear-gradient(135deg, #10b981 0%, #047857 100%)' },
    orange: { bg: '#f97316', fg: '#ffffff', soft: '#ffedd5', gradient: 'linear-gradient(135deg, #f97316 0%, #c2410c 100%)' },
    violet: { bg: '#8b5cf6', fg: '#ffffff', soft: '#ede9fe', gradient: 'linear-gradient(135deg, #8b5cf6 0%, #6d28d9 100%)' },
    cyan:   { bg: '#06b6d4', fg: '#ffffff', soft: '#cffafe', gradient: 'linear-gradient(135deg, #06b6d4 0%, #0e7490 100%)' },
    red:    { bg: '#ef4444', fg: '#ffffff', soft: '#fee2e2', gradient: 'linear-gradient(135deg, #ef4444 0%, #b91c1c 100%)' },
    yellow: { bg: '#eab308', fg: '#1f2937', soft: '#fef3c7', gradient: 'linear-gradient(135deg, #eab308 0%, #a16207 100%)' },
}

export const neutral = {
    bg: '#f8fafc',
    surface: '#ffffff',
    surfaceAlt: '#fafbfc',
    border: '#e5e7eb',
    borderStrong: '#d1d5db',
    text: '#111827',
    textMuted: '#6b7280',
    textDisabled: '#9ca3af',
}

export const radius = { sm: 8, md: 12, lg: 16, xl: 20, pill: 999 }

export const shadow = {
    sm: '0 1px 2px 0 rgba(15, 23, 42, 0.04), 0 1px 3px 0 rgba(15, 23, 42, 0.06)',
    md: '0 2px 4px -1px rgba(15, 23, 42, 0.04), 0 4px 8px -1px rgba(15, 23, 42, 0.08)',
    lg: '0 4px 6px -2px rgba(15, 23, 42, 0.05), 0 12px 16px -4px rgba(15, 23, 42, 0.10)',
    xl: '0 8px 24px -6px rgba(15, 23, 42, 0.10), 0 24px 48px -12px rgba(15, 23, 42, 0.18)',
}

export const lift = {
    rest: shadow.md,
    hover: '0 8px 16px -4px rgba(15, 23, 42, 0.08), 0 20px 32px -8px rgba(15, 23, 42, 0.14)',
}

export const space = { 1: 4, 2: 8, 3: 12, 4: 16, 5: 20, 6: 24, 8: 32, 10: 40, 12: 48, 16: 64 }

export const fontSize = { xs: 11, sm: 12, base: 14, md: 16, lg: 18, xl: 20, '2xl': 24, '3xl': 30, '4xl': 36 }

export const antdTheme = {
    token: {
        colorPrimary: brand.primary,
        colorInfo: brand.primary,
        colorSuccess: palette.green.bg,
        colorWarning: palette.orange.bg,
        colorError: palette.red.bg,

        colorLink: brand.primary,
        colorLinkHover: brand.primaryHover,
        colorLinkActive: brand.primaryActive,

        colorText: neutral.text,
        colorTextSecondary: neutral.textMuted,
        colorTextTertiary: neutral.textDisabled,

        colorBgBase: neutral.surface,
        colorBgLayout: neutral.bg,
        colorBgContainer: neutral.surface,
        colorBgElevated: neutral.surface,

        colorBorder: neutral.border,
        colorBorderSecondary: neutral.surfaceAlt,

        borderRadius: radius.md,
        borderRadiusLG: radius.lg,
        borderRadiusSM: radius.sm,

        fontSize: fontSize.base,
        fontFamily:
            "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', " +
            "'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', Arial, sans-serif",

        boxShadow: shadow.md,
        boxShadowSecondary: shadow.lg,
    },
    components: {
        Layout: {
            headerBg: neutral.surface,
            headerHeight: 64,
            headerPadding: '0 24px',
            siderBg: '#0f172a',
        },
        Menu: {
            darkItemBg: 'transparent',
            darkSubMenuItemBg: 'transparent',
            darkItemSelectedBg: brand.primary,
            darkItemHoverBg: 'rgba(255, 255, 255, 0.06)',
            darkItemColor: 'rgba(255, 255, 255, 0.75)',
            itemBorderRadius: 8,
            itemMarginInline: 8,
        },
        Card: {
            borderRadiusLG: radius.lg,
            boxShadowTertiary: shadow.sm,
        },
        Button: {
            borderRadius: radius.md,
            borderRadiusLG: radius.lg,
            fontWeight: 500,
        },
        Tabs: {
            itemSelectedColor: brand.primary,
            inkBarColor: brand.primary,
            titleFontSize: fontSize.base,
        },
        Tag: {
            borderRadiusSM: 6,
        },
    },
}
