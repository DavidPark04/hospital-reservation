import {useId} from 'react';
import {AlertCircle, CheckCircle2} from 'lucide-react';

type Props = {
  password: string;
  confirmation: string;
  onPasswordChange: (value: string) => void;
  onConfirmationChange: (value: string) => void;
  disabled: boolean;
  maxLength: number;
};

export default function SignupPasswordFields({
  password, confirmation, onPasswordChange, onConfirmationChange, disabled, maxLength
}: Props) {
  const statusId = useId();
  const hasConfirmation = confirmation.length > 0;
  const matches = password === confirmation;

  return <div className="auth-password-pair">
    <label className="field">비밀번호
      <input name="password" type="password" autoComplete="new-password" value={password}
        onChange={event => onPasswordChange(event.target.value)} required minLength={8} maxLength={maxLength}
        disabled={disabled} placeholder="8자 이상 입력"/>
    </label>
    <label className="field">비밀번호 확인
      <input name="passwordConfirmation" type="password" autoComplete="new-password" value={confirmation}
        onChange={event => onConfirmationChange(event.target.value)} required maxLength={maxLength}
        aria-invalid={hasConfirmation && !matches} aria-describedby={hasConfirmation ? statusId : undefined}
        disabled={disabled} placeholder="비밀번호 다시 입력"/>
    </label>
    <p id={statusId} role="status" className={`auth-password-status ${matches ? 'is-match' : 'is-mismatch'}`} hidden={!hasConfirmation}>
      {hasConfirmation && (matches
        ? <><CheckCircle2 size={15} aria-hidden="true"/>비밀번호가 일치해요.</>
        : <><AlertCircle size={15} aria-hidden="true"/>비밀번호가 일치하지 않아요.</>)}
    </p>
  </div>;
}
