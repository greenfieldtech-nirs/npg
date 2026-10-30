export type SupportLevel = 'full' | 'partial' | 'development'

export interface ProtocolMessage {
  /** Wire name, as in X-NP-MSG-TYPE */
  name: string
  /** Sender role → receiver role, in the protocol's terms */
  direction: string
  level: SupportLevel
  /** confirmed: body shape seen in a live operator trace; inferred: from the specification, to confirm with peers */
  wire: 'confirmed' | 'inferred'
  /** What the message is for, in the protocol */
  explanation: string
  /** What NPG does when it sends it (null: NPG never sends it) */
  sends: string | null
  /** What NPG does when it receives it (null: NPG never receives it) */
  receives: string | null
  /** Known limitations or assumptions; required for partial and under-development entries */
  limitations?: string[]
  /** A complete example: X-NP-* headers, a blank line, then the XML body */
  example: string
}

export interface ProtocolFamily {
  id: string
  title: string
  summary: string
  messages: ProtocolMessage[]
}
