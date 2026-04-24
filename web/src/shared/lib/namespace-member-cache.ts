import type { NamespaceMember } from '@/api/types'

export function appendNamespaceMember(
  currentMembers: NamespaceMember[] | undefined,
  nextMember: NamespaceMember,
) {
  const members = currentMembers ?? []
  const existingIndex = members.findIndex((member) => member.userId === nextMember.userId)

  if (existingIndex === -1) {
    return [...members, nextMember]
  }

  return members.map((member, index) => (index === existingIndex ? nextMember : member))
}

export function replaceNamespaceMemberRole(
  currentMembers: NamespaceMember[] | undefined,
  userId: string,
  role: string,
) {
  return (currentMembers ?? []).map((member) => (
    member.userId === userId
      ? {
          ...member,
          role,
        }
      : member
  ))
}
