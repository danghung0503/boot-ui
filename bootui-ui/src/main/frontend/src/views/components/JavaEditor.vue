<script setup>
// mani fork: the Hotpatch panel's Java editor (CodeMirror 6). Syntax colors are GitHub's light and dark palettes, picked
// by the shell's Bootstrap scheme (html[data-bs-theme]), since BootUI's code-pane tokens stay dark in every theme for
// terminal output. Lines changed from the source file are marked in a gutter, compiler diagnostics are underlined at
// their line, and Mod-S asks the parent to compile.
import {onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {EditorState, RangeSetBuilder, StateEffect, StateField} from '@codemirror/state'
import {
  Decoration,
  EditorView,
  GutterMarker,
  drawSelection,
  gutter,
  highlightActiveLine,
  highlightActiveLineGutter,
  keymap,
  lineNumbers
} from '@codemirror/view'
import {defaultKeymap, history, historyKeymap, indentWithTab} from '@codemirror/commands'
import {
  HighlightStyle,
  LanguageSupport,
  bracketMatching,
  foldGutter,
  foldKeymap,
  indentOnInput,
  syntaxHighlighting
} from '@codemirror/language'
import {javaLanguage} from '@codemirror/lang-java'
import {highlightSelectionMatches, searchKeymap} from '@codemirror/search'
import {lintGutter, setDiagnostics} from '@codemirror/lint'
import {styleTags, tags} from '@lezer/highlight'
import {changedLines, parseDiagnostics} from '../../utils/hotpatchEditor.js'

const props = defineProps({
  modelValue: {type: String, default: ''},
  // The source file the edit started from: lines that differ are marked.
  original: {type: String, default: ''},
  // Compiler diagnostics as "line N: message".
  diagnostics: {type: Array, default: () => []},
  // Whether those diagnostics come from a successful compilation (warnings) or a failed one (errors).
  compiledOk: {type: Boolean, default: false},
  readOnly: {type: Boolean, default: false},
  label: {type: String, default: 'Class source'}
})
const emit = defineEmits(['update:modelValue', 'compile'])

const host = ref(null)
let view = null

/** @type {import('@codemirror/state').StateEffectType<string>} */
const setOriginal = StateEffect.define()

class ChangedMarker extends GutterMarker {
  toDOM() {
    const span = document.createElement('span')
    span.className = 'cm-hotpatch-changed-marker'
    span.title = 'Changed from the source file'
    return span
  }
}
const changedMarker = new ChangedMarker()
const changedLine = Decoration.line({class: 'cm-hotpatch-changed'})

// The source file's text and the lines the current document changed from it.
const changes = StateField.define({
  create: (state) => ({original: props.original, lines: changedLines(props.original, state.doc.toString())}),
  update(value, tr) {
    let original = value.original
    for (const effect of tr.effects) {
      if (effect.is(setOriginal)) original = effect.value
    }
    if (!tr.docChanged && original === value.original) return value
    return {original, lines: changedLines(original, tr.state.doc.toString())}
  },
  provide: (field) =>
    EditorView.decorations.from(field, (value) => (view) => {
      const builder = /** @type {RangeSetBuilder<Decoration>} */ (new RangeSetBuilder())
      for (const number of [...value.lines].sort((a, b) => a - b)) {
        if (number <= view.state.doc.lines) {
          const line = view.state.doc.line(number)
          builder.add(line.from, line.from, changedLine)
        }
      }
      return builder.finish()
    })
})

const changedGutter = gutter({
  class: 'cm-hotpatch-changed-gutter',
  lineMarker(view, line) {
    return view.state.field(changes).lines.has(view.state.doc.lineAt(line.from).number) ? changedMarker : null
  },
  lineMarkerChange: (update) => update.startState.field(changes) !== update.state.field(changes)
})

// @lezer/java tags no annotation: color `@Service` and `@Override(...)` names as annotations.
const java = new LanguageSupport(
  javaLanguage.configure({
    props: [
      styleTags({'MarkerAnnotation Annotation MarkerAnnotation/Identifier Annotation/Identifier': tags.annotation})
    ]
  })
)

const highlight = HighlightStyle.define([
  {
    tag: [
      tags.keyword,
      tags.modifier,
      tags.controlKeyword,
      tags.operatorKeyword,
      tags.definitionKeyword,
      tags.moduleKeyword,
      tags.self
    ],
    color: 'var(--hp-keyword)'
  },
  {tag: [tags.string, tags.special(tags.string), tags.character], color: 'var(--hp-string)'},
  {tag: [tags.number, tags.integer, tags.float, tags.bool, tags.null], color: 'var(--hp-number)'},
  {tag: [tags.lineComment, tags.blockComment, tags.docComment], color: 'var(--hp-comment)', fontStyle: 'italic'},
  {tag: [tags.typeName, tags.className, tags.standard(tags.typeName)], color: 'var(--hp-type)'},
  {tag: tags.annotation, color: 'var(--hp-annotation)'},
  {tag: [tags.function(tags.variableName), tags.definition(tags.variableName)], color: 'var(--hp-function)'}
])

const theme = EditorView.theme({
  '&': {
    backgroundColor: 'var(--hp-bg)',
    color: 'var(--hp-fg)',
    border: '1px solid var(--bootui-border)',
    borderRadius: '0.5rem',
    fontSize: '12.5px'
  },
  '&.cm-focused': {outline: 'none', borderColor: 'var(--hp-focus)', boxShadow: '0 0 0 3px var(--hp-focus-ring)'},
  '.cm-scroller': {
    fontFamily: "ui-monospace, SFMono-Regular, 'JetBrains Mono', Menlo, Consolas, monospace",
    lineHeight: '1.6',
    minHeight: '26rem',
    maxHeight: '68vh',
    overflow: 'auto',
    borderRadius: '0.5rem'
  },
  '.cm-content': {caretColor: 'var(--hp-fg)', padding: '6px 0'},
  '.cm-line': {padding: '0 12px 0 6px'},
  '.cm-cursor': {borderLeftColor: 'var(--hp-fg)'},
  '.cm-gutters': {backgroundColor: 'var(--hp-bg)', color: 'var(--hp-gutter)', border: 'none'},
  '.cm-lineNumbers .cm-gutterElement': {padding: '0 6px 0 12px', minWidth: '2.5em'},
  '.cm-foldGutter .cm-gutterElement': {color: 'var(--hp-gutter)', padding: '0 2px'},
  '.cm-activeLine': {backgroundColor: 'var(--hp-active)'},
  '.cm-activeLineGutter': {backgroundColor: 'transparent', color: 'var(--hp-fg)'},
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground, .cm-content ::selection': {
    backgroundColor: 'var(--hp-selection) !important'
  },
  '.cm-matchingBracket, &.cm-focused .cm-matchingBracket': {
    backgroundColor: 'var(--hp-bracket)',
    outline: 'none',
    borderRadius: '2px'
  },
  '.cm-selectionMatch': {backgroundColor: 'var(--hp-match)'},
  '.cm-searchMatch': {backgroundColor: 'var(--hp-match)', outline: '1px solid var(--hp-changed-bar)'},
  '.cm-hotpatch-changed': {backgroundColor: 'var(--hp-changed)'},
  '.cm-hotpatch-changed-gutter': {width: '3px'},
  '.cm-hotpatch-changed-marker': {
    display: 'block',
    width: '3px',
    height: '100%',
    backgroundColor: 'var(--hp-changed-bar)'
  },
  '.cm-lintRange-error': {
    backgroundImage: 'none',
    textDecoration: 'underline wavy var(--bootui-danger)',
    textUnderlineOffset: '3px'
  },
  '.cm-panels': {backgroundColor: 'var(--hp-panel)', color: 'var(--hp-fg)', borderColor: 'var(--bootui-border)'},
  '.cm-panels input, .cm-panels button': {fontSize: '12px'},
  '.cm-tooltip': {
    backgroundColor: 'var(--hp-panel)',
    color: 'var(--hp-fg)',
    border: '1px solid var(--bootui-border)',
    borderRadius: '6px'
  }
})

function diagnosticsFor(state) {
  return parseDiagnostics(/** @type {string[]} */ (props.diagnostics), props.compiledOk).map((d) => {
    const line = state.doc.line(Math.min(Math.max(d.line, 1), state.doc.lines))
    return {from: line.from, to: Math.max(line.to, line.from), severity: d.severity, message: d.message}
  })
}

onMounted(() => {
  view = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        lineNumbers(),
        changedGutter,
        foldGutter(),
        lintGutter(),
        highlightActiveLineGutter(),
        history(),
        drawSelection(),
        indentOnInput(),
        bracketMatching(),
        highlightActiveLine(),
        highlightSelectionMatches(),
        java,
        syntaxHighlighting(highlight),
        changes,
        theme,
        EditorState.tabSize.of(4),
        EditorState.readOnly.of(props.readOnly),
        EditorView.contentAttributes.of({'aria-label': props.label}),
        keymap.of([
          {key: 'Mod-s', preventDefault: true, run: () => (emit('compile'), true)},
          indentWithTab,
          ...defaultKeymap,
          ...historyKeymap,
          ...searchKeymap,
          ...foldKeymap
        ]),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) emit('update:modelValue', update.state.doc.toString())
        })
      ]
    })
  })
})

onBeforeUnmount(() => {
  view?.destroy()
  view = null
})

watch(
  () => props.modelValue,
  (value) => {
    if (view && value !== view.state.doc.toString()) {
      view.dispatch({changes: {from: 0, to: view.state.doc.length, insert: value}})
    }
  }
)
watch(
  () => props.original,
  (value) => view?.dispatch({effects: setOriginal.of(value)})
)
watch(
  () => [props.diagnostics, props.compiledOk],
  () => view && view.dispatch(setDiagnostics(view.state, diagnosticsFor(view.state)))
)
</script>

<template>
  <div ref="host" class="java-editor"></div>
</template>

<style>
/* GitHub's light palette by default, its dark one under a dark Bootstrap scheme (Dark, Graphite, Cyberpunk). */
.java-editor {
  contain: inline-size; /* long lines scroll inside the editor instead of widening the panel */
  width: 100%;
  --hp-bg: #ffffff;
  --hp-fg: #1f2328;
  --hp-gutter: #8c959f;
  --hp-active: #f6f8fa;
  --hp-panel: #f6f8fa;
  --hp-selection: #b6e3ff;
  --hp-match: rgba(84, 174, 255, 0.25);
  --hp-bracket: rgba(84, 174, 255, 0.3);
  --hp-focus: #0969da;
  --hp-focus-ring: rgba(9, 105, 218, 0.15);
  --hp-keyword: #cf222e;
  --hp-string: #0a3069;
  --hp-number: #0550ae;
  --hp-comment: #6e7781;
  --hp-type: #953800;
  --hp-annotation: #8250df;
  --hp-function: #6639ba;
  --hp-changed: rgba(212, 167, 44, 0.14);
  --hp-changed-bar: #d4a72c;
}

[data-bs-theme='dark'] .java-editor {
  --hp-bg: #0d1117;
  --hp-fg: #e6edf3;
  --hp-gutter: #6e7681;
  --hp-active: #161b22;
  --hp-panel: #161b22;
  --hp-selection: #264f78;
  --hp-match: rgba(56, 139, 253, 0.25);
  --hp-bracket: rgba(56, 139, 253, 0.35);
  --hp-focus: #4493f8;
  --hp-focus-ring: rgba(68, 147, 248, 0.2);
  --hp-keyword: #ff7b72;
  --hp-string: #a5d6ff;
  --hp-number: #79c0ff;
  --hp-comment: #8b949e;
  --hp-type: #ffa657;
  --hp-annotation: #d2a8ff;
  --hp-function: #d2a8ff;
  --hp-changed: rgba(187, 128, 9, 0.18);
  --hp-changed-bar: #d29922;
}
</style>
