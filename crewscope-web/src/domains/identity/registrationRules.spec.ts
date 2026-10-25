import { validateRegistrationInput } from './registrationRules'

describe('validateRegistrationInput', () => {
  it('accepts the canonical open-registration shape', () => {
    expect(validateRegistrationInput(valid())).toEqual({})
  })

  it.each([
    ['alice-dev_2', '字母、数字与单个分隔符'],
    ['张三丰', 'Unicode 字母'],
    ['a.b-c_d', '混合分隔符'],
  ])('accepts username %s (%s)', username => {
    expect(validateRegistrationInput({ ...valid(), username })).toEqual({})
  })

  it.each([
    ['_alice', '以分隔符开头'],
    ['alice_', '以分隔符结尾'],
    ['ab cdef', '包含空格'],
    ['al--ice', '连续分隔符'],
    ['alice@team', '包含 @'],
    ['😀alice', '以符号开头'],
  ])('rejects username %s (%s)', username => {
    expect(validateRegistrationInput({ ...valid(), username }).username)
      .toBe('用户名只能包含字母、数字和单个 . - _ 分隔符，并以字母或数字开头结尾')
  })

  it.each([
    ['alice@example.com', '标准邮箱'],
    ['alice@localhost', '无点域名'],
    ["o'brien+tag@example-site.co", '本地部分符号'],
    ['用户名@例え.jp', 'Unicode 邮箱'],
  ])('accepts email %s (%s)', email => {
    expect(validateRegistrationInput({ ...valid(), email })).toEqual({})
  })

  it.each([
    ['aliceexample.com', '缺少 @'],
    ['@example.com', '空本地部分'],
    ['alice@', '空域名'],
    ['a@b@c.com', '多个 @'],
    ['..alice@example.com', '本地部分点位置'],
    ['alice..name@example.com', '连续点'],
    ['alice@exa mple.com', '域名空格'],
  ])('rejects email %s (%s)', email => {
    expect(validateRegistrationInput({ ...valid(), email }).email)
      .toBe('邮箱需要包含一个可用的本地部分和域名（如 name@example.com）')
  })

  it.each([
    'password1234',
    'PASSWORD1234',
    'Administrator',
  ])('rejects common password %s before submit', password => {
    expect(validateRegistrationInput({ ...valid(), password }).password)
      .toBe('密码过于常见，请更换一段不易猜到的长密码')
  })

  it('still enforces the original length budgets', () => {
    expect(validateRegistrationInput({ ...valid(), username: 'ab' }).username)
      .toBe('用户名需要包含 3 至 64 个字符')
    expect(validateRegistrationInput({ ...valid(), password: 'short' }).password)
      .toBe('密码需要包含 12 至 128 个字符，且不超过 512 字节')
    expect(validateRegistrationInput({ ...valid(), displayName: '' }).displayName)
      .toBe('展示名需要包含 1 至 200 个字符')
  })
})

function valid() {
  return { username: 'alice', email: 'alice@example.com', displayName: 'Alice', password: 'correct horse battery staple' }
}
