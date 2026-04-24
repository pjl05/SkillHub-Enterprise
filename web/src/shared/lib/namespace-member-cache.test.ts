import { describe, expect, it } from 'vitest'
import { appendNamespaceMember, replaceNamespaceMemberRole } from './namespace-member-cache'
import type { NamespaceMember } from '@/api/types'

const baseMember = (overrides: Partial<NamespaceMember>): NamespaceMember => ({
  id: 1,
  userId: 'user-1',
  role: 'MEMBER',
  createdAt: '2026-03-17T00:00:00',
  ...overrides,
})

describe('appendNamespaceMember', () => {
  it('appends a newly added member with the returned role', () => {
    const members = [baseMember({})]
    const addedMember = baseMember({ id: 2, userId: 'user-2', role: 'ADMIN' })

    expect(appendNamespaceMember(members, addedMember)).toEqual([
      members[0],
      addedMember,
    ])
  })

  it('replaces the existing member when the same user is returned again', () => {
    const members = [baseMember({ role: 'MEMBER' })]
    const updatedMember = baseMember({ role: 'ADMIN' })

    expect(appendNamespaceMember(members, updatedMember)).toEqual([updatedMember])
  })
})

describe('replaceNamespaceMemberRole', () => {
  it('updates the member role in the current list', () => {
    const members = [baseMember({}), baseMember({ id: 2, userId: 'user-2', role: 'MEMBER' })]

    expect(replaceNamespaceMemberRole(members, 'user-2', 'ADMIN')).toEqual([
      members[0],
      { ...members[1], role: 'ADMIN' },
    ])
  })
})
