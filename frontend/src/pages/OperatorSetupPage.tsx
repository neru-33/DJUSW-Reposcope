import { Button, Code, Group, PasswordInput, Stack, Stepper, Text, TextInput, Title } from '@mantine/core'
import { QRCodeSVG } from 'qrcode.react'
import { useEffect, useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router'
import { ApiError, api } from '../api/client'
import type { OperatorStage, OperatorStageResponse, OtpSetup } from '../api/types'
import { homePath } from '../auth/access'
import { useAuth } from '../auth/context'
import { OTP_CODE, applyStage, operatorErrorMessage } from '../auth/operator'
import { ErrorState, PageLoader } from '../components/states'
import { notifySuccess } from '../components/notify'

/** V-14 운영자 초기 설정. ID와 비밀번호를 바꾸고 OTP를 등록한다. 기준: docs/feature-spec.md R-2, docs/api-spec.md 4장 */
export function OperatorSetupPage() {
  const { state } = useAuth()
  const [stage, setStage] = useState<OperatorStage | 'none' | null>(null)
  const [loadError, setLoadError] = useState<unknown>(null)

  // 이미 로그인한 운영자는 설정 단계가 아니므로 첫 화면으로 보낸다.
  const signedIn = state.me !== null

  useEffect(() => {
    if (signedIn) return
    let active = true
    api
      .get<OperatorStageResponse>('/auth/admin/state')
      .then((response) => {
        if (active) setStage(applyStage(response))
      })
      .catch((error: unknown) => {
        if (!active) return
        // 설정 단계 세션이 없으면 운영자 로그인부터 한다.
        if (error instanceof ApiError && error.status === 401) setStage('none')
        else setLoadError(error)
      })
    return () => {
      active = false
    }
  }, [signedIn])

  if (signedIn) return <Navigate to={homePath(state.me)} replace />
  if (loadError) return <ErrorState error={loadError} onRetry={() => window.location.reload()} />
  if (stage === null) return <PageLoader />
  if (stage === 'none' || stage === 'ACTIVE') return <Navigate to="/admin/login" replace />
  if (stage === 'OTP') return <Navigate to="/admin/login" replace state={{ stage: 'OTP' }} />

  return (
    <Stack gap="lg">
      <div>
        <Title order={1}>초기 설정</Title>
        <Text c="dimmed" mt={6}>
          처음 로그인했으면 ID와 비밀번호를 바꾸고 OTP를 등록하세요.
        </Text>
      </div>

      <Stepper active={stage === 'PASSWORD_CHANGE' ? 0 : 1} size="sm" allowNextStepsSelect={false}>
        <Stepper.Step label="ID와 비밀번호 변경" />
        <Stepper.Step label="OTP 등록" />
      </Stepper>

      {stage === 'PASSWORD_CHANGE' ? <PasswordStep onDone={setStage} /> : <OtpStep />}
    </Stack>
  )
}

function PasswordStep({ onDone }: { onDone: (stage: OperatorStage) => void }) {
  const [currentPassword, setCurrentPassword] = useState('')
  const [newUsername, setNewUsername] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<{ username?: string; password?: string; confirm?: string }>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    const found: typeof errors = {}
    if (!newUsername.trim()) found.username = '새 ID를 입력하세요.'
    if (!newPassword) found.password = '새 비밀번호를 입력하세요.'
    else if (newPassword !== confirm) found.confirm = '새 비밀번호가 서로 다릅니다.'
    setErrors(found)
    if (!currentPassword) setFormError('임시 비밀번호를 입력하세요.')
    if (Object.keys(found).length > 0 || !currentPassword) return

    setSaving(true)
    try {
      const response = await api.post<OperatorStageResponse>('/auth/admin/password', {
        current_password: currentPassword,
        new_username: newUsername.trim(),
        new_password: newPassword,
      })
      onDone(applyStage(response))
    } catch (error) {
      setSaving(false)
      setFormError(operatorErrorMessage(error))
    }
  }

  return (
    <div className="form-layout">
      <form className="panel panel-body" onSubmit={(event) => void submit(event)} noValidate>
        <Stack gap="md">
          <PasswordInput
            label="임시 비밀번호"
            description="최고관리자에게 받은 비밀번호"
            required
            autoComplete="current-password"
            value={currentPassword}
            onChange={(event) => setCurrentPassword(event.currentTarget.value)}
          />
          <TextInput
            label="새 ID"
            required
            autoComplete="username"
            value={newUsername}
            onChange={(event) => setNewUsername(event.currentTarget.value)}
            error={errors.username}
          />
          <PasswordInput
            label="새 비밀번호"
            required
            autoComplete="new-password"
            value={newPassword}
            onChange={(event) => setNewPassword(event.currentTarget.value)}
            error={errors.password}
          />
          <PasswordInput
            label="새 비밀번호 확인"
            required
            autoComplete="new-password"
            value={confirm}
            onChange={(event) => {
              setConfirm(event.currentTarget.value)
              setErrors((current) => ({ ...current, confirm: undefined }))
            }}
            error={errors.confirm}
          />
          {formError && (
            <Text role="alert" size="sm" c="danger.7">
              {formError}
            </Text>
          )}
          <Group justify="flex-end">
            <Button type="submit" loading={saving}>
              ID와 비밀번호 변경
            </Button>
          </Group>
        </Stack>
      </form>

      <aside className="panel panel-body">
        <Title order={2}>안내</Title>
        <ul className="guide-list">
          <li>임시 비밀번호는 발급 후 72시간 동안 쓸 수 있습니다.</li>
          <li>다음 로그인부터 새 ID와 비밀번호를 씁니다.</li>
        </ul>
      </aside>
    </div>
  )
}

function OtpStep() {
  const { reload } = useAuth()
  const navigate = useNavigate()
  const [setup, setSetup] = useState<OtpSetup | null>(null)
  const [loadError, setLoadError] = useState<unknown>(null)
  const [code, setCode] = useState('')
  const [codeError, setCodeError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let active = true
    api
      .get<OtpSetup>('/auth/admin/otp/setup')
      .then((response) => {
        if (active) setSetup(response)
      })
      .catch((error: unknown) => {
        if (active) setLoadError(error)
      })
    return () => {
      active = false
    }
  }, [])

  async function submit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    if (!OTP_CODE.test(code.trim())) {
      setCodeError('인증 앱에 표시된 6자리 숫자를 입력하세요.')
      return
    }
    setSaving(true)
    try {
      applyStage(await api.post<OperatorStageResponse>('/auth/admin/otp/setup', { code: code.trim() }))
    } catch (error) {
      setSaving(false)
      setCode('')
      setFormError(operatorErrorMessage(error))
      return
    }
    // OTP 등록이 끝나면 로그인된 상태가 된다. 현재 사용자를 읽은 뒤 첫 화면으로 간다.
    await reload()
    notifySuccess('초기 설정을 마쳤습니다.')
    navigate('/admin/projects', { replace: true })
  }

  if (loadError) return <ErrorState error={loadError} onRetry={() => window.location.reload()} />
  if (!setup) return <PageLoader lines={2} />

  return (
    <div className="form-layout">
      <form className="panel panel-body" onSubmit={(event) => void submit(event)} noValidate>
        <Stack gap="md">
          <div className="otp-qr">
            <QRCodeSVG value={setup.otpauth_uri} size={176} marginSize={2} title="OTP 등록 QR 코드" />
          </div>
          <div>
            <Text size="sm" fw={600}>
              비밀키
            </Text>
            <Text size="xs" c="dimmed">
              QR 코드를 찍을 수 없으면 인증 앱에 직접 입력합니다.
            </Text>
            <Code block mt={6} className="otp-secret">
              {setup.secret}
            </Code>
          </div>
          <TextInput
            label="OTP"
            description="인증 앱에 표시된 6자리 숫자"
            required
            inputMode="numeric"
            autoComplete="one-time-code"
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
          <Group justify="flex-end">
            <Button type="submit" loading={saving}>
              OTP 등록
            </Button>
          </Group>
        </Stack>
      </form>

      <aside className="panel panel-body">
        <Title order={2}>등록 방법</Title>
        <ol className="guide-list">
          <li>휴대폰에 Google Authenticator 같은 인증 앱을 설치합니다.</li>
          <li>앱에서 QR 코드를 찍습니다.</li>
          <li>앱에 표시된 6자리 숫자를 입력합니다.</li>
        </ol>
      </aside>
    </div>
  )
}
