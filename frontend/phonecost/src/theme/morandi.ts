import type { ThemeConfig } from 'antd';

// ============ Morandi Color Palette ============
export const COLORS = {
  // Primary palette
  charcoal: '#3D4248',    // Dark charcoal grey — sidebar, stat cards
  sage: '#8B9D9E',        // Muted sage green — primary action color
  taupe: '#C4B5A0',       // Warm taupe/beige — accents
  slate: '#7B8FA1',       // Dusty slate blue — secondary
  cream: '#F5F2ED',       // Light cream — page background
  white: '#FFFFFF',

  // Text
  textDark: '#2C2C2C',
  textLight: '#FFFFFF',
  textMuted: '#8C8C8C',

  // Borders & dividers
  border: '#E8E5E0',

  // Status colors (Morandi-tuned)
  confirmed: '#7BA586',   // Muted green
  pending: '#D4A574',     // Warm amber
  danger: '#C47B6C',      // Muted terracotta
  info: '#7B8FA1',        // Slate blue
} as const;

// ============ Ant Design Theme Tokens ============
export const morandiTheme: ThemeConfig = {
  token: {
    colorPrimary: COLORS.sage,
    colorBgLayout: COLORS.cream,
    colorBgContainer: COLORS.white,
    colorText: COLORS.textDark,
    colorTextSecondary: COLORS.textMuted,
    colorBorder: COLORS.border,
    colorBorderSecondary: COLORS.border,
    borderRadius: 10,
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif",
    colorSuccess: COLORS.confirmed,
    colorWarning: COLORS.pending,
    colorError: COLORS.danger,
    colorInfo: COLORS.info,
    boxShadow: '0 2px 8px rgba(0,0,0,0.04)',
    boxShadowSecondary: '0 2px 8px rgba(0,0,0,0.06)',
  },
  components: {
    Card: {
      borderRadiusLG: 12,
      boxShadowTertiary: '0 2px 8px rgba(0,0,0,0.04)',
      headerBg: 'transparent',
      headerFontSize: 15,
    },
    Button: {
      borderRadius: 8,
      controlHeight: 34,
    },
    Table: {
      headerBg: COLORS.cream,
      headerColor: COLORS.textDark,
      rowHoverBg: 'rgba(139, 157, 158, 0.06)',
      borderColor: COLORS.border,
    },
    Menu: {
      darkItemBg: COLORS.charcoal,
      darkSubMenuItemBg: '#33373D',
      darkItemSelectedBg: COLORS.sage,
      darkItemHoverBg: 'rgba(139, 157, 158, 0.25)',
      darkItemColor: 'rgba(255,255,255,0.75)',
      darkItemSelectedColor: COLORS.white,
    },
    Tabs: {
      cardBg: COLORS.white,
      cardBgActive: COLORS.white,
      inkBarColor: COLORS.sage,
      itemActiveColor: COLORS.sage,
      itemSelectedColor: COLORS.sage,
      itemHoverColor: COLORS.slate,
    },
    Tag: {
      defaultBg: 'rgba(139, 157, 158, 0.08)',
      defaultColor: COLORS.textDark,
    },
    Modal: {
      borderRadiusLG: 12,
    },
    Input: {
      borderRadius: 8,
      activeShadow: `0 0 0 2px rgba(139, 157, 158, 0.2)`,
    },
    Select: {
      borderRadius: 8,
    },
    Statistic: {
      contentFontSize: 22,
    },
  },
};
