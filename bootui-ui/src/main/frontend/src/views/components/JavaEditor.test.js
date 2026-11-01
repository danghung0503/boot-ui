import {describe, expect, it} from 'vitest'
import {mount} from '@vue/test-utils'
import {nextTick} from 'vue'
import JavaEditor from './JavaEditor.vue'

const ORIGINAL = 'class Fee {\n  long fee() {\n    return 1;\n  }\n}'

describe('JavaEditor', () => {
  it('renders the source with line numbers and marks the lines changed from the source file', async () => {
    const edited = ORIGINAL.replace('return 1;', 'return 2;')
    const wrapper = mount(JavaEditor, {props: {modelValue: edited, original: ORIGINAL}, attachTo: document.body})
    await nextTick()

    expect(wrapper.find('.cm-content').text()).toContain('return 2;')
    expect(wrapper.findAll('.cm-lineNumbers .cm-gutterElement').length).toBeGreaterThan(1)
    expect(wrapper.findAll('.cm-hotpatch-changed')).toHaveLength(1)
    expect(wrapper.find('.cm-content').attributes('aria-label')).toBe('Class source')
    wrapper.unmount()
  })

  it('asks the panel to compile on Mod-S', async () => {
    const wrapper = mount(JavaEditor, {props: {modelValue: ORIGINAL, original: ORIGINAL}, attachTo: document.body})
    await nextTick()

    const content = wrapper.find('.cm-content').element
    content.dispatchEvent(new KeyboardEvent('keydown', {key: 's', keyCode: 83, ctrlKey: true, bubbles: true}))

    expect(wrapper.emitted('compile')).toBeTruthy()
    wrapper.unmount()
  })
})
