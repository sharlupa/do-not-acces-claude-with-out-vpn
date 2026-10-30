// Страны, где Claude официально доступен (Supported Regions Policy Anthropic,
// https://www.anthropic.com/supported-countries — список, действующий с 12.11.2026).
// Из всех остальных стран (Россия, Беларусь, Китай, Гонконг, Иран, КНДР, Сирия, Куба,
// Венесуэла, Афганистан, Мьянма, ...) расширение не пускает к Claude.
// Обновить список: скопируйте коды с этой страницы.
export const SUPPORTED_COUNTRIES = new Set([
  'AL', 'DZ', 'AD', 'AO', 'AG', 'AR', 'AM', 'AU', 'AT', 'AZ', 'BS', 'BH', 'BD', 'BB', 'BE', 'BZ',
  'BJ', 'BT', 'BO', 'BA', 'BW', 'BR', 'BN', 'BG', 'BF', 'BI', 'CV', 'KH', 'CM', 'CA', 'CF', 'TD',
  'CL', 'CO', 'KM', 'CG', 'CD', 'CR', 'CI', 'HR', 'CY', 'CZ', 'DK', 'DJ', 'DM', 'DO', 'EC', 'EG',
  'SV', 'GQ', 'ER', 'EE', 'SZ', 'ET', 'FJ', 'FI', 'FR', 'GA', 'GM', 'GE', 'DE', 'GH', 'GR', 'GD',
  'GT', 'GN', 'GW', 'GY', 'HT', 'HN', 'HU', 'IS', 'IN', 'ID', 'IQ', 'IE', 'IL', 'IT', 'JM', 'JP',
  'JO', 'KZ', 'KE', 'KI', 'KW', 'KG', 'LA', 'LV', 'LB', 'LS', 'LR', 'LY', 'LI', 'LT', 'LU', 'MG',
  'MW', 'MY', 'MV', 'ML', 'MT', 'MH', 'MR', 'MU', 'MX', 'FM', 'MD', 'MC', 'MN', 'ME', 'MA', 'MZ',
  'NA', 'NR', 'NP', 'NL', 'NZ', 'NI', 'NE', 'NG', 'MK', 'NO', 'OM', 'PK', 'PW', 'PS', 'PA', 'PG',
  'PY', 'PE', 'PH', 'PL', 'PT', 'QA', 'RO', 'RW', 'KN', 'LC', 'VC', 'WS', 'SM', 'ST', 'SA', 'SN',
  'RS', 'SC', 'SL', 'SG', 'SK', 'SI', 'SO', 'SB', 'ZA', 'KR', 'SS', 'ES', 'LK', 'SD', 'SR', 'SE',
  'CH', 'TW', 'TJ', 'TZ', 'TH', 'TL', 'TG', 'TO', 'TT', 'TN', 'TR', 'TM', 'TV', 'UG', 'UA', 'AE',
  'GB', 'US', 'UY', 'UZ', 'VU', 'VA', 'VN', 'ZM', 'ZW',
  // Территории перечисленных стран — по правилам Anthropic тоже поддерживаются
  'PR', 'GU', 'VI', 'AS', 'MP', 'UM', 'GI', 'JE', 'GG', 'IM', 'BM', 'KY', 'VG', 'TC', 'AI', 'MS',
  'FK', 'SH', 'IO', 'PN', 'GS', 'GF', 'GP', 'MQ', 'RE', 'YT', 'PM', 'BL', 'MF', 'NC', 'PF', 'WF',
  'TF', 'AW', 'CW', 'SX', 'BQ', 'GL', 'FO', 'SJ', 'BV', 'CX', 'CC', 'NF', 'HM', 'CK', 'NU', 'TK',
  'AX',
]);

// Внимание: в Украине (UA) не поддерживаются Крым, Донецкая, Луганская, Херсонская и
// Запорожская области — по стране подключения их не отличить.
