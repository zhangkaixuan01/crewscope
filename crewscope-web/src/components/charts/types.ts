/**
 * M12-F02 chart primitives' shared vocabulary (S01 freeze: hand-written SVG, two
 * shapes to start — line and bar; anything past the degrade threshold renders as a
 * table because a 200-point picture stops being readable at any viewport).
 */

/** Past this many points/bars the components render the equivalent table instead. */
export const CHART_DEGRADE_THRESHOLD = 200

/** The series tone picks the stroke color: series must never be told apart by color alone. */
export type ChartTone = 'info' | 'agent'

/** One data point; `x` is the shared category axis (a reporting month string). */
export interface ChartPoint {
  x: string
  y: number
}

export interface ChartSeries {
  key: string
  label: string
  tone: ChartTone
  /** Every series of one chart shares the same x sequence in the same order (builder contract). */
  points: ChartPoint[]
}

/** A horizontal dashed reference line (e.g. the currently configured limit). */
export interface ChartReference {
  label: string
  value: number
}

export interface ChartBar {
  key: string
  label: string
  value: number
  tone?: ChartTone
}

export type ChartValueFormatter = (value: number) => string
