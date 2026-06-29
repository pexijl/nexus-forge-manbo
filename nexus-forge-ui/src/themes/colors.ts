/*
 * 真理来源：src/styles/base/_tokens.scss
 * ──────────────────────────────────────────────────────────
 * 本文件零硬编码——全部用 var(--xxx) 引用 CSS 变量。
 * PrimeVue 接受 var() palette 值，浏览器原生解析。
 *
 * 改色值只改 _tokens.scss。这边自动跟随。
 * 配合 .dark 覆盖实现 dark mode 自动级联。
 */

const SHADES = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950] as const;

function makeScale(name: string): Record<(typeof SHADES)[number], string> {
  return Object.fromEntries(SHADES.map((s) => [s, `var(--${name}-${s})`])) as Record<
    (typeof SHADES)[number],
    string
  >;
}

export const palette = {
  primary: makeScale('primary'),
  success: makeScale('success'),
  warning: makeScale('warning'),
  danger: makeScale('danger'),
  info: makeScale('info'),
  muted: makeScale('muted'),
  surface: makeScale('surface'),
};
