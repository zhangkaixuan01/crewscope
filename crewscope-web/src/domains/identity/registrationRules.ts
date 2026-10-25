/**
 * Mirrors the server's registration field rules (Username, NormalizedEmail and PasswordPolicy in
 * crewscope-domain identity) so deterministic rejections surface inline before submit. Where full
 * Unicode parity is impractical the client stays permissive: the server remains authoritative and
 * its invalid_value response marks the same fields inline through registrationInvalidValueField.
 */
export interface RegistrationInputFields {
  username: string
  email: string
  displayName: string
  password: string
}

const USERNAME_LENGTH_MESSAGE = '用户名需要包含 3 至 64 个字符'
const USERNAME_SHAPE_MESSAGE = '用户名只能包含字母、数字和单个 . - _ 分隔符，并以字母或数字开头结尾'
const EMAIL_LENGTH_MESSAGE = '请输入有效且不超过 254 个字符的邮箱'
const EMAIL_SHAPE_MESSAGE = '邮箱需要包含一个可用的本地部分和域名（如 name@example.com）'
const DISPLAY_NAME_LENGTH_MESSAGE = '展示名需要包含 1 至 200 个字符'
const PASSWORD_LENGTH_MESSAGE = '密码需要包含 12 至 128 个字符，且不超过 512 字节'
const COMMON_PASSWORD_MESSAGE = '密码过于常见，请更换一段不易猜到的长密码'

/** Exact-match deny list fixed by ADR-25, compared case-folded exactly like the server. */
const COMMON_REGISTRATION_PASSWORDS = new Set([
  '123456789012',
  'adminadmin123',
  'administrator',
  'changeme1234',
  'iloveyou12345',
  'letmein12345',
  'password1234',
  'passwordpassword',
  'qwerty123456',
  'welcome1234',
])

const EMAIL_LOCAL_ALLOWED_SYMBOLS = ".!#$%&'*+/=?^_`{|}~-"

export function validateRegistrationInput(input: RegistrationInputFields): Record<string, string> {
  const errors: Record<string, string> = {}
  const usernameLength = Array.from(input.username).length
  if (usernameLength < 3 || usernameLength > 64) errors.username = USERNAME_LENGTH_MESSAGE
  else if (usernameShapeError(input.username)) errors.username = USERNAME_SHAPE_MESSAGE
  if (!input.email || input.email.length > 254) errors.email = EMAIL_LENGTH_MESSAGE
  else if (emailShapeError(input.email)) errors.email = EMAIL_SHAPE_MESSAGE
  const displayNameLength = Array.from(input.displayName).length
  if (displayNameLength < 1 || displayNameLength > 200) errors.displayName = DISPLAY_NAME_LENGTH_MESSAGE
  const passwordLength = Array.from(input.password).length
  const passwordBytes = new TextEncoder().encode(input.password).byteLength
  if (passwordLength < 12 || passwordLength > 128 || passwordBytes > 512 || hasUnpairedSurrogate(input.password)) {
    errors.password = PASSWORD_LENGTH_MESSAGE
  } else if (COMMON_REGISTRATION_PASSWORDS.has(input.password.toLowerCase())) {
    errors.password = COMMON_PASSWORD_MESSAGE
  }
  return errors
}

/** First character must be a letter or digit, the last may add a combining mark, and the three
 * separator characters never repeat or neighbor each other — the server's exact state machine. */
function usernameShapeError(value: string): string | null {
  const characters = Array.from(value)
  if (characters.length === 0) return null
  const isAlphanumeric = (character: string) => /\p{L}|\p{N}/u.test(character)
  const isCombiningMark = (character: string) => /\p{M}/u.test(character)
  const isSeparator = (character: string) => character === '.' || character === '-' || character === '_'
  if (!isAlphanumeric(characters[0])) return USERNAME_SHAPE_MESSAGE
  const last = characters[characters.length - 1]
  if (!isAlphanumeric(last) && !isCombiningMark(last)) return USERNAME_SHAPE_MESSAGE
  let afterSeparator = false
  for (const character of characters) {
    if (isAlphanumeric(character)) {
      afterSeparator = false
      continue
    }
    if (isCombiningMark(character) && !afterSeparator) continue
    if (isSeparator(character) && !afterSeparator) {
      afterSeparator = true
      continue
    }
    return USERNAME_SHAPE_MESSAGE
  }
  return null
}

/** One @ with a bounded local part of allowed characters and a dot-position-valid domain. */
function emailShapeError(value: string): string | null {
  const separator = value.indexOf('@')
  if (separator <= 0 || separator !== value.lastIndexOf('@') || separator === value.length - 1) {
    return EMAIL_SHAPE_MESSAGE
  }
  const localPart = value.slice(0, separator)
  const domain = value.slice(separator + 1)
  if (localPart.length > 64
    || localPart.startsWith('.') || localPart.endsWith('.') || localPart.includes('..')) {
    return EMAIL_SHAPE_MESSAGE
  }
  const localAllowed = new RegExp(`^[\\p{L}\\p{N}\\p{M}${escapeForCharacterClass(EMAIL_LOCAL_ALLOWED_SYMBOLS)}]+$`, 'u')
  if (!localAllowed.test(localPart)) return EMAIL_SHAPE_MESSAGE
  if (domain.length === 0 || domain.length > 253
    || domain.startsWith('.') || domain.endsWith('.') || domain.includes('..')
    || /\s/u.test(domain)) {
    return EMAIL_SHAPE_MESSAGE
  }
  return null
}

function escapeForCharacterClass(value: string): string {
  return value.replace(/[-\\^]/g, '\\$&')
}

function hasUnpairedSurrogate(value: string): boolean {
  return Array.from(value).some(character => {
    const codePoint = character.codePointAt(0) ?? 0
    return character.length === 1 && codePoint >= 0xD800 && codePoint <= 0xDFFF
  })
}
