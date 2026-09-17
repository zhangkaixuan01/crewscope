/*
 * Prism's grammar files are side-effect modules: each one registers itself on the global `Prism` when
 * it is evaluated, and none of them exports anything. `@types/prismjs` declares only the `prismjs`
 * entry point, so the individual component paths have no types at all.
 *
 * The declaration is deliberately shape-less. We import these paths for the registration side effect
 * alone, and giving them a shape would be a claim about their contents that nothing enforces. The
 * contract that does matter — which grammar registers which language, and in what order — is written
 * out in `syntax.ts` and held in place by its tests.
 */
declare module 'prismjs/components/prism-*'
