import { createRequire } from 'node:module';
import { describe, expect, it } from 'vitest';

// Resolve the exact transitive copy used by Next's lint plugin, without adding
// braces as an application dependency or depending on pnpm's store layout.
const require = createRequire(import.meta.url);
const nextConfig = createRequire(require.resolve('eslint-config-next'));
const nextPlugin = createRequire(nextConfig.resolve('@next/eslint-plugin-next'));
const fastGlob = createRequire(nextPlugin.resolve('fast-glob'));
const micromatch = createRequire(fastGlob.resolve('micromatch'));
type Ast = { type: string; nodes?: Ast[]; value?: string; commas?: number; open?: boolean; close?: boolean };
type Braces = {
  (pattern: string): string[];
  parse(pattern: string): Ast;
  compile(pattern: string | Ast): string;
  expand(pattern: string | Ast): string[];
  stringify(pattern: string | Ast): string;
};
const braces = micromatch('braces') as Braces;
const depthError = 'Nesting depth exceeds safety limit (100)';

describe('braces security patch (GHSA-vfj7-8cjw-p6xm)', () => {
  const nested = '{'.repeat(4096) + 'a,b' + '}'.repeat(4096);

  it('expires the temporary version-only audit exception on 2026-10-17', () => {
    expect(Date.now(), 'Review the upstream fix and remove/renew the documented patch exception')
      .toBeLessThan(Date.parse('2026-10-17T00:00:00Z'));
  });

  it.each(['parse', 'compile', 'expand', 'stringify'] as const)(
    '%s rejects excessive string nesting with a controlled syntax error',
    (method) => {
      expect(() => braces[method](nested)).toThrow(new SyntaxError(depthError));
    },
  );

  it('protects the default compilation API', () => {
    expect(() => braces(nested)).toThrow(new SyntaxError(depthError));
  });

  it('also bounds parentheses and mixed nesting in the parser', () => {
    for (const pair of [['(', ')'], ['({', '})']]) {
      const pattern = pair[0].repeat(150) + 'x' + pair[1].repeat(150);
      expect(() => braces.parse(pattern)).toThrow(new SyntaxError(depthError));
    }
  });

  it.each(['compile', 'expand', 'stringify'] as const)(
    '%s bounds a caller-supplied AST that bypasses parse',
    (method) => {
      let node: Ast = { type: 'text', value: 'x' };
      for (let index = 0; index < 150; index++) {
        node = { type: 'brace', nodes: [node], commas: 1, open: true, close: true };
      }
      expect(() => braces[method]({ type: 'root', nodes: [node] })).toThrow(new SyntaxError(depthError));
    },
  );

  it('preserves ordinary file globs and numeric ranges', () => {
    expect(braces.expand('src/{app,components}/**/*.{ts,tsx}')).toEqual([
      'src/app/**/*.ts', 'src/app/**/*.tsx', 'src/components/**/*.ts', 'src/components/**/*.tsx',
    ]);
    expect(braces.expand('room-{1..3}')).toEqual(['room-1', 'room-2', 'room-3']);
    expect(braces.compile('src/{app,components}/**/*.tsx')).toBe('src/(app|components)/**/*.tsx');
  });

  it('accepts reasonable nesting, escaped braces and quoted literal braces', () => {
    const ordinary = '{'.repeat(32) + 'a,b' + '}'.repeat(32);
    expect(() => braces.compile(ordinary)).not.toThrow();
    expect(() => braces.expand(ordinary)).not.toThrow();
    expect(() => braces.compile('\\{'.repeat(150))).not.toThrow();
    expect(() => braces.compile('"' + '{'.repeat(150) + '"')).not.toThrow();
  });
});
