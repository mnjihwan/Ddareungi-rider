// Keep the matching contract aligned with backend KoreanSearch (covered by shared fixtures).
const initials = 'ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ';
const leads = ['r','R','s','e','E','f','a','q','Q','t','T','d','w','W','c','z','x','v','g'];
const vowels = ['k','o','i','O','j','p','u','P','h','hk','ho','hl','y','n','nj','np','nl','b','m','ml','l'];
const tails = ['','r','R','rt','s','sw','sg','e','f','fr','fa','fq','ft','fx','fv','fg','a','q','qt','t','T','d','w','c','z','x','v','g'];
const jamo = 'ㄱㄲㄳㄴㄵㄶㄷㄸㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅃㅄㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ';
const keys = ['r','R','rt','s','sw','sg','e','E','f','fr','fa','fq','ft','fx','fv','fg','a','q','Q','qt','t','T','d','w','W','c','z','x','v','g','k','o','i','O','j','p','u','P','h','hk','ho','hl','y','n','nj','np','nl','b','m','ml','l'];

export const normalizeSearch = (value: string | null | undefined) => (value ?? '').normalize('NFC').replace(/\s+/g, '').toLowerCase();
export function keyboard(value: string): string {
  return [...value.normalize('NFC')].map(c => {
    const n = c.charCodeAt(0) - 0xac00;
    if (n >= 0 && n < 11172) return leads[Math.floor(n / 588)] + vowels[Math.floor(n % 588 / 28)] + tails[n % 28];
    const i = jamo.indexOf(c);
    return i < 0 ? c : keys[i];
  }).join('').replace(/\s+/g, '');
}

function distance(a: string, b: string): number {
  const d = Array.from({length: a.length + 1}, (_, i) => Array.from({length: b.length + 1}, (_, j) => i === 0 ? j : j === 0 ? i : 0));
  for (let i = 1; i <= a.length; i++) for (let j = 1; j <= b.length; j++) {
    d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
  }
  return d[a.length][b.length];
}

export function searchScore(value: string | null | undefined, query: string): number {
  const text = normalizeSearch(value), term = normalizeSearch(query);
  if (!term || text.includes(term)) return 0;
  if (term.length < 2) return -1;
  if ([...term].every(c => initials.includes(c))) {
    const head = [...text].map(c => c >= '가' && c <= '힣' ? initials[Math.floor((c.charCodeAt(0) - 0xac00) / 588)] : c).join('');
    return head.includes(term) ? 2 : -1;
  }
  const textKeys = keyboard(value ?? ''), queryKeys = keyboard(query);
  if ((/[a-z]/.test(term) || /[a-z]/.test(text)) && textKeys.includes(queryKeys)) return 1;
  if (!/^[가-힣]{2,}$/.test(term)) return -1;
  const limit = term.length >= 4 ? 2 : 1;
  let best = limit + 1;
  for (let start = 0; start < text.length; start++) {
    for (let length = Math.max(2, term.length - 1); length <= term.length + 1 && start + length <= text.length; length++) {
      const part = text.slice(start, start + length);
      if (!/^[가-힣]+$/.test(part)) continue;
      const candidate = keyboard(part);
      if (Math.abs(candidate.length - queryKeys.length) <= limit) best = Math.min(best, distance(candidate, queryKeys));
    }
  }
  return best <= limit ? 3 + best : -1;
}

export function bestSearchScore(name: string | null | undefined, address: string | null | undefined, query: string): number {
  const fieldScore = (term: string) => {
    const scores = [searchScore(name, term), searchScore(address, term)].filter(score => score >= 0);
    return scores.length ? Math.min(...scores) : -1;
  };
  const whole = fieldScore(query);
  if (whole === 0) return 0;
  const terms = query.trim().split(/\s+/);
  if (terms.length < 2 || terms.some(term => normalizeSearch(term).length < 2)) return whole;
  const scores = terms.map(fieldScore);
  if (scores.some(score => score < 0)) return whole;
  const combined = Math.max(...scores);
  return whole < 0 ? combined : Math.min(whole, combined);
}

export function searchMatchLabel(name: string, address: string, query: string): string {
  const score = bestSearchScore(name, address, query);
  return score === 1 ? '한/영 키 일치' : score === 2 ? '초성 일치' : score >= 3 ? '유사 이름' : '';
}
