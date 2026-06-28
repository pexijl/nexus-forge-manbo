import { palette } from './colors';

export const semantic = {
  primary: palette.primary,        //  色阶对象，PrimeVue 自动取 500 作主色
  success: palette.success,
  warning: palette.warning,
  danger: palette.danger,
  info: palette.info,

  // 自定义语义色（PrimeVue 不认 muted/secondary，需要在 colorScheme 里映射）
  colorScheme: {
    light: {
      muted: palette.muted[500],
      surface: palette.muted[50],
      border: palette.muted[200],
    },
    dark: {
      muted: palette.muted[400],
      surface: palette.muted[900],
      border: palette.muted[700],
    },
  },
};
