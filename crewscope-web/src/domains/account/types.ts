import type { AuthCsrfCoordinate } from '../identity/types'

export interface AccountProfile {
  accountId: string
  username: string
  email: string
  displayName: string
  status: 'ACTIVE' | 'DISABLED' | 'LOCKED'
  platformRole: 'USER' | 'OPERATOR'
  securityVersion: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface VersionedAccountProfile {
  value: AccountProfile
  etag: number
}

/** One-way profile command. currentPassword is never copied into Store state. */
export interface AccountProfileUpdateInput {
  username?: string
  email?: string
  displayName?: string
  currentPassword?: string
  securityVersion?: number
}

/** One-way password rotation command. Password values remain page-local. */
export interface AccountPasswordChangeInput {
  currentPassword: string
  newPassword: string
  securityVersion: number
}

/** One-way all-session revocation proof. Session identifiers are server-owned. */
export interface AccountSessionRevocationInput {
  currentPassword: string
  securityVersion: number
}

export interface AccountCommandContext {
  csrf: AuthCsrfCoordinate
  expectedVersion: number
}
