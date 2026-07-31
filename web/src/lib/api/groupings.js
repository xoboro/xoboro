import { request } from '../http.js'

/**
 * Collections organize series; read lists organize media items.
 *
 * The two are close enough in shape to share this module, and the differences are
 * exactly the ones a caller must not get wrong: a read list also carries a `summary`,
 * and its members are `mediaItemIds` rather than `seriesIds`. The native API says
 * `mediaItemIds` and `/media-items`, never `bookIds` or `/books`.
 *
 * Reading is open to any authenticated caller; every mutation is administrator-only.
 */

export const GROUPINGS = Object.freeze({
  collection: {
    path: 'collections',
    memberPath: 'series',
    memberField: 'seriesIds',
    hasSummary: false,
  },
  readList: {
    path: 'read-lists',
    memberPath: 'media-items',
    memberField: 'mediaItemIds',
    hasSummary: true,
  },
})

function shapeOf(kind) {
  const shape = GROUPINGS[kind]
  if (!shape) throw new Error(`unknown grouping: ${kind}`)
  return shape
}

/**
 * Lists groupings.
 *
 * `memberCount` on each entry is the number of members **visible to the caller**, not
 * the stored total. A restricted reader legitimately sees a smaller number than an
 * administrator does for the same collection, so it must not be presented as the
 * collection's size.
 */
export function listGroupings(kind) {
  return request(`/${shapeOf(kind).path}`)
}

/**
 * Reads one grouping.
 *
 * Answers `404` both when it does not exist and when none of its members are visible
 * to the caller — deliberately the same response, so the difference cannot be used to
 * enumerate groupings someone cannot see. A client must not treat "no visible
 * members" as an empty state it can render.
 */
export function readGrouping(kind, id) {
  return request(`/${shapeOf(kind).path}/${id}`)
}

/**
 * Reads a grouping's members, in stored order.
 *
 * These routes reject `sort` with `400 invalid_query`: the stored member order **is**
 * the contract, which is the whole point of an ordered read list. Visibility
 * filtering removes unauthorized members while preserving the relative order of the
 * rest, so a gap in the sequence is not a bug.
 */
export function listMembers(kind, id, { page = 0, size = 50 } = {}) {
  const shape = shapeOf(kind)
  return request(`/${shape.path}/${id}/${shape.memberPath}`, { query: { page, size } })
}

/**
 * Builds a create or update body.
 *
 * Every field is always sent, because `PUT` is a **full replacement** of the name,
 * the ordering flag, the member list and the member order — not a merge. Omitting the
 * member list would empty the grouping rather than leave it alone.
 */
export function groupingBody(kind, { name, ordered = true, memberIds = [], summary = '' }) {
  const shape = shapeOf(kind)
  return {
    name,
    ordered,
    [shape.memberField]: memberIds,
    ...(shape.hasSummary ? { summary } : {}),
  }
}

export function createGrouping(kind, values) {
  return request(`/${shapeOf(kind).path}`, { method: 'POST', body: groupingBody(kind, values) })
}

export function replaceGrouping(kind, id, values) {
  return request(`/${shapeOf(kind).path}/${id}`, {
    method: 'PUT',
    body: groupingBody(kind, values),
  })
}

export function deleteGrouping(kind, id) {
  return request(`/${shapeOf(kind).path}/${id}`, { method: 'DELETE' })
}

/**
 * Moves a member within an ordered list.
 *
 * Kept here rather than in a component because reordering is the operation the
 * contract cares about — the server stores the order given and returns it, so the
 * client is the only thing that decides what the new order is.
 *
 * @param {string[]} memberIds
 * @param {number} from
 * @param {number} to clamped to the bounds; an out-of-range move is a no-op rather
 *   than a silent truncation of the list.
 */
export function moveMember(memberIds, from, to) {
  if (from < 0 || from >= memberIds.length) return memberIds
  const target = Math.max(0, Math.min(memberIds.length - 1, to))
  if (target === from) return memberIds
  const next = [...memberIds]
  const [moved] = next.splice(from, 1)
  next.splice(target, 0, moved)
  return next
}
