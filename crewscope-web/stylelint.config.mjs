/** Stylelint entry point for CSS and Vue SFC style blocks. */
export default {
  customSyntax: 'postcss-html',
  rules: {
    'block-no-empty': true,
    'color-no-invalid-hex': true,
    // `height: 100vh` followed by `100dvh` is the intentional mobile viewport fallback.
    'declaration-block-no-duplicate-properties': [true, { ignore: ['consecutive-duplicates-with-different-values'] }],
    'no-invalid-double-slash-comments': true,
  },
}
