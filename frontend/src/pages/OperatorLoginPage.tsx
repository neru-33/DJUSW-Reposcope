import { Anchor, Button, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core'
import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { ApiError, api } from '../api/client'
import type { OperatorStageResponse } from '../api/types'
import { safeNext } from '../auth/access'
import { useAuth } from '../auth/context'
import { OTP_CODE, applyStage, operatorErrorMessage } from '../auth/operator'

type Step = 'password' | 'otp'

/** V-13 운영자 로그인. ID와 비밀번호를 확인한 뒤 OTP를 받는다. 기준: docs/feature-spec.md R-2, docs/api-spec.md 4장 */
export function OperatorLoginPage() {
  const { reload } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const next = safeNext(searchParams.get('next')) ?? '/admin/projects'
  // V-14에서 비밀번호만 바꾸고 OTP는 이미 등록된 계정이면 OTP 입력부터 시작한다.
  const [step, setStep] = useState<Step>(
    (location.state as { stage?: string } | null)?.stage === 'OTP' ? 'otp' : 'password',
  )
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [codeError, setCodeError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  async function submitPassword(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    if (!username.trim() || !password) {
      setFormError('ID와 비밀번호를 입력하세요.')
      return
    }
    setSaving(true)
    try {
      const stage = applyStage(
        await api.post<OperatorStageResponse>('/auth/admin/login', { username: username.trim(), password }),
      )
      if (stage === 'OTP') {
        setStep('otp')
        setPassword('')
      } else {
        navigate('/admin/setup', { replace: true })
      }
    } catch (error) {
      setFormError(operatorErrorMessage(error))
    } finally {
      setSaving(false)
    }
  }

  async function submitOtp(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    if (!OTP_CODE.test(code.trim())) {
      setCodeError('인증 앱에 표시된 6자리 숫자를 입력하세요.')
      return
    }
    setSaving(true)
    try {
      applyStage(await api.post<OperatorStageResponse>('/auth/admin/otp', { code: code.trim() }))
    } catch (error) {
      setSaving(false)
      setCode('')
      // 로그인한 지 오래되어 확인할 수 없으면 ID와 비밀번호부터 다시 받는다.
      if (error instanceof ApiError && error.code === 'unauthorized') setStep('password')
      setFormError(operatorErrorMessage(error))
      return
    }
    await reload()
    navigate(next, { replace: true })
  }

  function restart() {
    setStep('password')
    setCode('')
    setCodeError(null)
    setFormError(null)
  }

  return (
    <div className="auth-page">
      <div>
        <Title order={1}>운영자 로그인</Title>
        <Text c="dimmed" mt={6}>
          교수와 관리자 계정으로 로그인합니다.
        </Text>
      </div>

      <div className="panel panel-body">
        {step === 'password' ? (
          <form onSubmit={(event) => void submitPassword(event)} noValidate>
            <Stack gap="md">
              <TextInput
                label="ID"
                required
                autoComplete="username"
                value={username}
                onChange={(event) => setUsername(event.currentTarget.value)}
              />
              <PasswordInput
                label="비밀번호"
                required
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.currentTarget.value)}
              />
              {formError && (
                <Text role="alert" size="sm" c="danger.7">
                  {formError}
                </Text>
              )}
              <Button type="submit" loading={saving} fullWidth>
                다음
              </Button>
            </Stack>
          </form>
        ) : (
          <form onSubmit={(event) => void submitOtp(event)} noValidate>
            <Stack gap="md">
              <TextInput
                label="OTP"
                description="인증 앱에 표시된 6자리 숫자"
                required
                inputMode="numeric"
                autoComplete="one-time-code"
                autoFocus
                value={code}
                onChange={(event) => {
                  setCode(event.currentTarget.value)
                  setCodeError(null)
                }}
                error={codeError}
              />
              {formError && (
                <Text role="alert" size="sm" c="danger.7">
                  {formError}
                </Text>
              )}
              <Button type="submit" loading={saving} fullWidth>
                로그인
              </Button>
              <Anchor component="button" type="button" size="sm" onClick={restart}>
                다른 계정으로 로그인
              </Anchor>
            </Stack>
          </form>
        )}
      </div>

      <Text size="sm" c="dimmed" ta="center">
        학생은{' '}
        <Anchor component={Link} to="/" size="sm">
          GitHub으로 로그인
        </Anchor>
        합니다.
      </Text>
    </div>
  )
}
