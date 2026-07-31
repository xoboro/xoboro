import { createEventHub } from './sse.js'

/**
 * The application's single event-stream subscription.
 *
 * A module singleton rather than something threaded through props or context: the
 * design allows exactly one stream per session, and any screen that needs live
 * updates can import this. Passing a hub down would let a screen create its own by
 * mistake, and the server allows only four concurrent streams per user before it
 * starts evicting the oldest.
 *
 * The shell owns its lifecycle — started when a session exists, stopped when it
 * does not.
 */
export const eventHub = createEventHub()
