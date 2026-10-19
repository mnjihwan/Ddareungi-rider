import test from 'node:test';
import assert from 'node:assert/strict';
import {parseTheme, resolveTheme, readThemePreference, THEME_KEY} from '../src/lib/theme.ts';

test('automatic theme uses local time and switches at 07:00 and 19:00', () => {
  for (const [hour, minute, theme] of [[0,0,'dark'],[6,59,'dark'],[7,0,'light'],[18,59,'light'],[19,0,'dark'],[23,59,'dark']]) {
    assert.equal(resolveTheme(null, new Date(2026,9,19,hour,minute)),theme);
  }
});
test('manual preference overrides the daytime and nighttime defaults', () => {
  assert.equal(resolveTheme('dark',new Date(2026,9,19,12)),'dark');
  assert.equal(resolveTheme('light',new Date(2026,9,19,23)),'light');
});
test('invalid or inaccessible saved preferences fall back to automatic mode', () => {
  for(const value of [null,'','system','auto','DARK']) assert.equal(parseTheme(value),null);
  assert.equal(readThemePreference({getItem(key){assert.equal(key,THEME_KEY);return 'dark';}}),'dark');
  assert.equal(readThemePreference({getItem(){throw new Error('denied');}}),null);
});
