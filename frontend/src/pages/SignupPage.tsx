import { Button, Checkbox, Divider, Group, Stack, Text, TextInput, Title } from '@mantine/core'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { ApiError, api } from '../api/client'
import { useAuth } from '../auth/context'
import { errorMessage } from '../components/errorMessage'
import { notifyError, notifySuccess } from '../components/notify'

/** POST /api/me/profile 요청 본문. 기준: docs/api-spec.md 3장 */
interface ProfileRequest {
  name: string
  student_id: string
  department: string | null
  consent: boolean
}

type Field = 'student_id' | 'name' | 'consent'
type FieldErrors = Partial<Record<Field, string>>

const STUDENT_ID = /^[0-9]{8}$/

const MESSAGES = {
  studentId: '학번 8자리를 숫자로 입력하세요.',
  name: '이름을 입력하세요.',
  consent: '동의해야 가입할 수 있습니다.',
  duplicate: '이미 가입된 학번입니다. 학번을 다시 확인하세요.',
  forbidden: '가입 정보를 저장할 수 없습니다. 로그아웃한 뒤 다시 로그인해 시도하세요.',
}

/** 개인정보 수집·이용 안내. 기준: docs/feature-spec.md R-1 */
const PRIVACY_NOTICE = [
  { label: '수집 항목', value: '학번, 이름, 학과(선택), GitHub 계정 번호, 로그인 이름, 프로필 사진' },
  { label: '이용 목적', value: '프로젝트 팀 구성, 진행 상태와 저장소 활동 표시' },
  { label: '보유 기간', value: '서비스 운영 종료일(2026년 12월 31일)까지. 종료 후 지체 없이 파기합니다.' },
  {
    label: '다른 학생에게 보이는 정보',
    value: '프로젝트를 학생 모두에게 공개하면 이름, GitHub 로그인 이름, 프로필 사진, 활동 기록이 다른 팀 학생에게도 보입니다.',
  },
  { label: '동의 거부', value: '동의하지 않을 수 있습니다. 이 경우 가입할 수 없습니다.' },
]

function validate(input: ProfileRequest): FieldErrors {
  const errors: FieldErrors = {}
  if (!STUDENT_ID.test(input.student_id)) errors.student_id = MESSAGES.studentId
  if (!input.name) errors.name = MESSAGES.name
  if (!input.consent) errors.consent = MESSAGES.consent
  return errors
}

/** 서버 오류를 입력란 아래 문구로 바꾼다. 입력란에 붙일 수 없는 오류는 null이다. */
function fieldErrorsFrom(error: unknown): FieldErrors | null {
  if (!(error instanceof ApiError)) return null
  if (error.code === 'duplicate_student_id') return { student_id: MESSAGES.duplicate }
  if (error.code === 'consent_required') return { consent: MESSAGES.consent }
  if (error.code === 'invalid_request' && error.fields) {
    const errors: FieldErrors = {}
    for (const field of ['student_id', 'name', 'consent'] as const) {
      if (error.fields[field]) errors[field] = error.fields[field]
    }
    return Object.keys(errors).length > 0 ? errors : null
  }
  return null
}

/** V-2 가입. 기준: docs/screen-flow.md 5장, docs/ui-guide.md 6장 입력 */
export function SignupPage() {
  const { reload } = useAuth()
  const navigate = useNavigate()
  const [studentId, setStudentId] = useState('')
  const [name, setName] = useState('')
  const [department, setDepartment] = useState('')
  const [consent, setConsent] = useState(false)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [saving, setSaving] = useState(false)
  // 입력란에 붙일 수 없는 가입 실패 사유. 폼 안에 표시한다.
  const [formError, setFormError] = useState<string | null>(null)

  function clearError(field: Field) {
    setErrors((current) => ({ ...current, [field]: undefined }))
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    const input: ProfileRequest = {
      name: name.trim(),
      student_id: studentId.trim(),
      department: department.trim() || null,
      consent,
    }
    const found = validate(input)
    setErrors(found)
    setFormError(null)
    if (Object.keys(found).length > 0) return

    setSaving(true)
    try {
      await api.post<void>('/api/me/profile', input)
    } catch (error) {
      setSaving(false)
      const fieldErrors = fieldErrorsFrom(error)
      if (fieldErrors) {
        setErrors(fieldErrors)
        return
      }
      // 이미 가입했으면 현재 사용자를 다시 읽어 맞는 화면으로 보낸다.
      // 다시 읽어도 이 화면에 남으면(비활성화된 계정 등) 사유를 폼 안에 보여 준다.
      if (error instanceof ApiError && error.status === 403) {
        setFormError(error.message && error.message !== error.code ? error.message : MESSAGES.forbidden)
        await reload()
        return
      }
      notifyError(errorMessage(error))
      return
    }

    // 직위가 STUDENT로 바뀐 사용자 정보를 먼저 읽어야 V-19에 들어갈 수 있다.
    await reload()
    notifySuccess('가입했습니다.')
    navigate('/explore', { replace: true })
  }

  return (
    <Stack gap="lg">
      <div>
        <Title order={1}>가입</Title>
        <Text c="dimmed" mt={6}>
          학번과 이름만 입력하면 됩니다.
        </Text>
      </div>

      <div className="form-layout">
        <form className="panel panel-body" onSubmit={(event) => void submit(event)} noValidate>
          <Stack gap="lg">
            <Title order={2}>학생 정보</Title>
            <TextInput
              label="학번"
              placeholder="20261234"
              maxLength={8}
              required
              inputMode="numeric"
              autoComplete="off"
              value={studentId}
              onChange={(event) => {
                setStudentId(event.currentTarget.value)
                clearError('student_id')
              }}
              error={errors.student_id}
            />
            <TextInput
              label="이름"
              required
              autoComplete="name"
              value={name}
              onChange={(event) => {
                setName(event.currentTarget.value)
                clearError('name')
              }}
              error={errors.name}
            />
            <TextInput
              label="학과"
              placeholder="선택"
              value={department}
              onChange={(event) => setDepartment(event.currentTarget.value)}
            />

            <Divider color="var(--mantine-color-default-border)" />

            <Title order={2}>개인정보 수집·이용 동의</Title>
            <Checkbox
              label="개인정보 수집·이용에 동의합니다. (필수)"
              checked={consent}
              onChange={(event) => {
                setConsent(event.currentTarget.checked)
                clearError('consent')
              }}
              error={errors.consent}
            />

            {formError && (
              <Text role="alert" size="sm" c="danger.7">
                {formError}
              </Text>
            )}

            <Group justify="flex-end">
              <Button type="submit" loading={saving}>
                가입
              </Button>
            </Group>
          </Stack>
        </form>

        <aside className="panel panel-body" aria-labelledby="privacy-title">
          <Title order={2} id="privacy-title">
            개인정보 수집·이용 안내
          </Title>
          <dl className="notice-list">
            {PRIVACY_NOTICE.map((item) => (
              <div key={item.label}>
                <dt>{item.label}</dt>
                <dd>{item.value}</dd>
              </div>
            ))}
          </dl>
        </aside>
      </div>
    </Stack>
  )
}
