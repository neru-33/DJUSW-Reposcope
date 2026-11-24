import {
  Badge,
  Button,
  createTheme,
  Input,
  Paper,
  SegmentedControl,
  Table,
  Tabs,
  Title,
  type CSSVariablesResolver,
  type MantineColorsTuple,
} from '@mantine/core'

/** 기본 파랑. 6번이 기본색, 7번이 누른 상태다. */
const brand: MantineColorsTuple = [
  '#eaf0fa', '#d3e0f5', '#a8c1ea', '#7aa0dd', '#5285d2',
  '#3a72c6', '#2157b0', '#1a4690', '#143871', '#0e2a55',
]

/** 신호 주황. 검증 단계와 이번 주 활동에 쓴다. 글자에는 7번을 쓴다. */
const signal: MantineColorsTuple = [
  '#fdf3e2', '#f9e2bb', '#f2c77f', '#e9a944', '#df921b',
  '#d4850c', '#c97a06', '#a46304', '#7f4d03', '#5b3702',
]

/** 완료 초록 */
const done: MantineColorsTuple = [
  '#e6f4ed', '#c4e5d4', '#93cfb1', '#5fb78b', '#38a16c',
  '#26905d', '#1d7f55', '#176845', '#115136', '#0b3a27',
]

/** 위험. 삭제·보관·제외 버튼과 오류에 쓴다. */
const danger: MantineColorsTuple = [
  '#fcecee', '#f5d3d8', '#eaa7b1', '#de7a89', '#d25a6c',
  '#c54a5d', '#b83f50', '#993442', '#7b2a35', '#5d2028',
]

/** 화면 기준 3장의 기본 색 */
export const tokens = {
  ink: '#1b2a41',
  muted: '#5a6b82',
  line: '#d8dfe8',
  lineSoft: '#e8edf3',
  canvas: '#f3f5f8',
  surface: '#ffffff',
  danger: '#b83f50',
}

const font = "'Pretendard Variable', Pretendard, 'Noto Sans KR', sans-serif"

export const theme = createTheme({
  primaryColor: 'brand',
  primaryShade: 6,
  colors: { brand, signal, done, danger },
  fontFamily: font,
  headings: {
    fontFamily: font,
    fontWeight: '700',
    sizes: {
      h1: { fontSize: '28px', lineHeight: '1.3' },
      h2: { fontSize: '20px', lineHeight: '1.4' },
      h3: { fontSize: '16px', lineHeight: '1.5' },
    },
  },
  fontSizes: { xs: '12px', sm: '13px', md: '14px', lg: '16px', xl: '20px' },
  radius: { xs: '2px', sm: '3px', md: '4px', lg: '6px', xl: '8px' },
  defaultRadius: 'md',
  shadows: { xs: 'none', sm: 'none', md: '0 6px 20px rgba(27, 42, 65, 0.12)' },
  components: {
    Button: Button.extend({ styles: { root: { fontWeight: 600 } } }),
    Badge: Badge.extend({
      defaultProps: { radius: 'sm', variant: 'light' },
      styles: { root: { textTransform: 'none', fontWeight: 600, letterSpacing: 0 } },
    }),
    Paper: Paper.extend({ defaultProps: { radius: 'lg', shadow: 'none' } }),
    Title: Title.extend({ styles: { root: { color: tokens.ink, letterSpacing: '-0.01em' } } }),
    Input: Input.extend({ styles: { input: { borderColor: tokens.line } } }),
    SegmentedControl: SegmentedControl.extend({ defaultProps: { radius: 'md' } }),
    Tabs: Tabs.extend({ styles: { tab: { fontWeight: 600, fontSize: 14, paddingInline: 16 } } }),
    Table: Table.extend({
      defaultProps: { horizontalSpacing: 'md', verticalSpacing: 10 },
      styles: {
        th: { color: tokens.muted, fontWeight: 600, fontSize: 12, whiteSpace: 'nowrap' },
        td: { fontSize: 14 },
      },
      vars: () => ({ table: { '--table-border-color': tokens.lineSoft } }),
    }),
  },
})

export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {},
  light: {
    '--mantine-color-body': tokens.canvas,
    '--mantine-color-text': tokens.ink,
    '--mantine-color-dimmed': tokens.muted,
    '--mantine-color-default-border': tokens.line,
    '--mantine-color-error': tokens.danger,
  },
  dark: {},
})
