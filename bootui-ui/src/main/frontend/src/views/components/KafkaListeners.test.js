import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {confirmState, settleConfirm} from '../../utils/useConfirm.js'
import KafkaListeners from './KafkaListeners.vue'

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), {status, headers: {'Content-Type': 'application/json'}})

const listener = (overrides = {}) => ({
  id: 'transaction-create',
  groupId: 'test.transaction',
  topics: ['test.transaction.create'],
  concurrency: 8,
  running: true,
  pauseRequested: false,
  paused: false,
  assignedPartitions: ['test.transaction.create-0', 'test.transaction.create-1'],
  ...overrides
})

describe('KafkaListeners', () => {
  afterEach(() => {
    settleConfirm(false)
    vi.unstubAllGlobals()
  })

  it('lists listeners and pauses one after confirmation', async () => {
    let paused = false
    const fetch = vi.fn((url, init) =>
      Promise.resolve(
        init?.method === 'POST'
          ? json(((paused = true), listener({pauseRequested: true, paused: true})))
          : json({available: true, unavailableReason: null, listeners: [listener({pauseRequested: paused, paused})]})
      )
    )
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(KafkaListeners)
    await flushPromises()

    expect(wrapper.text()).toContain('transaction-create')
    expect(wrapper.text()).toContain('running')
    await wrapper.find('button.btn-outline-warning').trigger('click')
    expect(confirmState.open).toBe(true)
    settleConfirm(true)
    await flushPromises()

    const post = fetch.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(String(post[0])).toContain('api/kafka/listeners/transaction-create/pause')
    expect(wrapper.emitted('flash')[0][0]).toBe('Paused transaction-create')
    expect(wrapper.text()).toContain('paused')
    expect(wrapper.find('button.btn-outline-success').exists()).toBe(true)
  })

  it('disables the actions when the panel is read-only and hides itself without a registry', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(json({available: true, listeners: [listener()]})))
    )
    const readOnly = mount(KafkaListeners, {props: {readOnly: true}})
    await flushPromises()
    expect(readOnly.find('button.btn-outline-warning').attributes('disabled')).toBeDefined()

    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(json({available: false, unavailableReason: 'none', listeners: []})))
    )
    const absent = mount(KafkaListeners)
    await flushPromises()
    expect(absent.find('.card').exists()).toBe(false)
  })
})
