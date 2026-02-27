export type Lang = 'zh' | 'en';

export function getPreferredLang(request: Request): Lang {
  const header = request.headers.get('accept-language') || '';
  const primary = header.split(',')[0]?.trim().toLowerCase() || '';
  if (primary.startsWith('zh')) return 'zh';
  return 'en';
}

const messages: Record<Lang, Record<string, string>> = {
  zh: {
    not_found: '未找到',
    internal_server_error: '服务器内部错误',
  },
  en: {
    not_found: 'Not Found',
    internal_server_error: 'Internal Server Error',
  },
};

export function t(key: string, lang: Lang): string {
  return messages[lang]?.[key] || messages.en[key] || key;
}
