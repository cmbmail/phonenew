import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from './locales/zh-CN';

i18n
  .use(initReactI18next)
  .init({
    resources: {
      'zh-CN': { translation: zhCN },
    },
    fallbackLng: 'zh-CN',
    lng: 'zh-CN',
    interpolation: { escapeValue: false },
  });

export default i18n;
